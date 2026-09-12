package com.patbaumgartner.prune.core.analyzer;

import com.patbaumgartner.prune.core.config.AnalysisConfig;
import com.patbaumgartner.prune.core.config.Baseline;
import com.patbaumgartner.prune.core.dependency.DeclaredDependency;
import com.patbaumgartner.prune.core.dependency.DependencyUsage;
import com.patbaumgartner.prune.core.dependency.GradleBuildParser;
import com.patbaumgartner.prune.core.dependency.MavenPomParser;
import com.patbaumgartner.prune.core.project.ProjectScanner;
import com.patbaumgartner.prune.core.project.ScannedProject;
import com.patbaumgartner.prune.core.report.AnalysisIssue;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import com.patbaumgartner.prune.core.report.IssueType;
import com.patbaumgartner.prune.core.report.KeptSymbol;
import com.patbaumgartner.prune.core.report.Severity;
import com.patbaumgartner.prune.core.report.SourceLocation;
import com.patbaumgartner.prune.core.source.JavaSourceFile;
import com.patbaumgartner.prune.core.source.MemberDeclaration;
import com.patbaumgartner.prune.core.source.TypeDeclaration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

// A candidate that no Java identifier references is shown to every guard in turn; the
// first guard that keeps it names itself in the explanation, and only a candidate no
// guard keeps is reported.
public final class ConservativeUnusedCodeAnalyzer implements UnusedCodeAnalyzer {

	private static final Comparator<AnalysisIssue> ISSUE_ORDER = Comparator
		.comparing((AnalysisIssue issue) -> SourceLocation.parse(issue.location()).path())
		.thenComparingInt(issue -> SourceLocation.parse(issue.location()).line())
		.thenComparingInt(issue -> SourceLocation.parse(issue.location()).column())
		.thenComparing(AnalysisIssue::type)
		.thenComparing(AnalysisIssue::symbol);

	private static final Comparator<KeptSymbol> KEPT_ORDER = Comparator
		.comparing((KeptSymbol kept) -> SourceLocation.parse(kept.location()).path())
		.thenComparingInt(kept -> SourceLocation.parse(kept.location()).line())
		.thenComparingInt(kept -> SourceLocation.parse(kept.location()).column())
		.thenComparing(KeptSymbol::type)
		.thenComparing(KeptSymbol::symbol);

	private final List<Guard> guards;

	// The built-in guards followed by every guard registered through ServiceLoader.
	public ConservativeUnusedCodeAnalyzer() {
		this(concat(builtInGuards(), discoveredGuards()));
	}

	public ConservativeUnusedCodeAnalyzer(List<Guard> guards) {
		Set<String> ids = new HashSet<>();
		for (Guard guard : guards) {
			if (!ids.add(guard.id())) {
				throw new IllegalStateException("Duplicate guard id: " + guard.id());
			}
		}
		this.guards = List.copyOf(guards);
	}

	public static List<Guard> builtInGuards() {
		return BuiltInGuards.all();
	}

	// Loaded through the class loader that holds prune-core, which is the plugin realm
	// under Maven and the plugin classpath under Gradle; sorted by id so the order is the
	// same in every run.
	public static List<Guard> discoveredGuards() {
		List<Guard> discovered = new ArrayList<>();
		ServiceLoader.load(Guard.class, Guard.class.getClassLoader()).forEach(discovered::add);
		discovered.sort(Comparator.comparing(Guard::id));
		return discovered;
	}

	public List<Guard> guards() {
		return guards;
	}

	@Override
	public AnalysisReport analyze(AnalysisConfig config) {
		ScannedProject project;
		try {
			project = ProjectScanner.scan(config);
		}
		catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}

		ReferenceIndex index = new ReferenceIndex(project, config.includeTestReferences());
		Findings findings = new Findings(config.explain());
		List<String> skipped = new ArrayList<>();
		for (ScannedProject.JavaFile file : project.candidates()) {
			if (file.source().opaque()) {
				skipped.add(file.source().relativePath());
				continue;
			}
			for (TypeDeclaration type : file.source().types()) {
				analyzeTopLevelType(file.source(), type, index, findings);
			}
		}
		analyzeDependencies(project, findings);

		Baseline baseline = config.baseline().map(Baseline::load).orElse(Baseline.empty());
		List<AnalysisIssue> issues = new ArrayList<>();
		int suppressed = 0;
		for (AnalysisIssue issue : findings.issues) {
			if (baseline.contains(issue)) {
				suppressed++;
				findings.kept(issue.type(), issue.symbol(), issue.location(), "baseline",
						"accepted in " + baselineName(config));
			}
			else {
				issues.add(issue);
			}
		}
		issues.sort(ISSUE_ORDER);
		findings.kept.sort(KEPT_ORDER);

		String summary = "Analyzed " + project.candidates().size() + " Java file(s) under " + config.projectRoot()
				+ ": " + issues.size() + " issue(s) found (conservative mode)."
				+ (skipped.isEmpty() ? ""
						: " Skipped " + skipped.size() + " file(s) the parser could not follow: "
								+ String.join(", ", skipped))
				+ (suppressed == 0 ? "" : " " + suppressed + " issue(s) suppressed by " + baselineName(config) + ".");
		return new AnalysisReport(issues, summary, true, findings.kept);
	}

	private void analyzeTopLevelType(JavaSourceFile file, TypeDeclaration type, ReferenceIndex index,
			Findings findings) {
		String qualifiedName = file.qualifiedName(type);

		if (!type.isPublic()) {
			if (index.typeScope(file, type) == ReferenceIndex.Scope.NONE) {
				Candidate candidate = new Candidate.TypeCandidate(IssueType.UNUSED_CLASS, file, type, Optional.empty(),
						qualifiedName, index);
				String message = kindLabel(type) + " " + type.name()
						+ (index.namedInTests(file, type) ? " is only referenced from tests" : " is never referenced");
				if (judge(candidate, kindLabel(type), Severity.WARNING, message, false, findings)) {
					return;
				}
			}
		}
		else if (isVisibilityCandidate(type)
				&& index.typeScopeIncludingTests(file, type) == ReferenceIndex.Scope.SAME_PACKAGE_ONLY
				// A test in another package may load the type by name; dropping public
				// would break it.
				&& !index.namedInTests(type.name())) {
			Candidate candidate = new Candidate.TypeCandidate(IssueType.UNUSED_VISIBILITY, file, type, Optional.empty(),
					qualifiedName, index);
			judge(candidate, "Public class", Severity.INFO, "Public class " + type.name()
					+ " is only used from package " + file.packageName() + " and can be package-private",
					type.publicModifierOffset() >= 0, findings);
		}

		analyzeMembers(file, type, qualifiedName, index, findings);
	}

	// A public class is only offered for visibility reduction when nothing outside it
	// could hold an instance: every constructor is private and every non-private member
	// is static.
	private static boolean isVisibilityCandidate(TypeDeclaration type) {
		if (type.kind() != TypeDeclaration.TypeKind.CLASS || type.constructors().isEmpty()) {
			return false;
		}
		for (MemberDeclaration member : type.members()) {
			boolean constructor = member.kind() == MemberDeclaration.MemberKind.CONSTRUCTOR;
			if (constructor && !member.isPrivate()) {
				return false;
			}
			if (!constructor && member.kind() != MemberDeclaration.MemberKind.INITIALIZER && !member.isPrivate()
					&& !member.isStatic()) {
				return false;
			}
		}
		return true;
	}

	private void analyzeMembers(JavaSourceFile file, TypeDeclaration type, String qualifiedName, ReferenceIndex index,
			Findings findings) {
		for (MemberDeclaration member : type.members()) {
			if (!member.isPrivate() || index.memberReferenced(file, member)) {
				continue;
			}
			String symbol = qualifiedName + "#" + member.name();
			String suffix = index.namedInTests(member.name()) ? " is only referenced from tests" : " is never used";
			switch (member.kind()) {
				case METHOD -> judge(
						new Candidate.MemberCandidate(IssueType.UNUSED_METHOD, file, type, member, symbol, index),
						"Private method", Severity.WARNING, "Private method " + member.name() + suffix, true, findings);
				case FIELD ->
					judge(new Candidate.MemberCandidate(IssueType.UNUSED_FIELD, file, type, member, symbol, index),
							"Private field", Severity.WARNING, "Private field " + member.name() + suffix,
							member.declaratorCount() == 1, findings);
				case CONSTRUCTOR, INITIALIZER -> {
				}
			}
		}
		for (TypeDeclaration nested : type.nestedTypes()) {
			String nestedName = qualifiedName + "." + nested.name();
			if (!nested.isPublic() && !nested.isProtected()
					&& index.typeScope(file, nested) == ReferenceIndex.Scope.NONE) {
				String label = "Nested " + nested.kind().name().toLowerCase(Locale.ROOT);
				Candidate candidate = new Candidate.TypeCandidate(IssueType.UNUSED_CLASS, file, nested,
						Optional.of(type), nestedName, index);
				String message = label + " " + nested.name() + (index.namedInTests(file, nested)
						? " is only referenced from tests" : " is never referenced");
				if (judge(candidate, label, Severity.WARNING, message, false, findings)) {
					continue;
				}
			}
			analyzeMembers(file, nested, nestedName, index, findings);
		}
	}

	// Returns true when the candidate was reported; a kept candidate is explained
	// instead.
	private boolean judge(Candidate candidate, String label, Severity severity, String unusedMessage,
			boolean autoFixable, Findings findings) {
		int offset = candidate instanceof Candidate.MemberCandidate member ? member.member().nameOffset()
				: candidate.type().nameOffset();
		String location = locationOf(candidate.file(), offset);
		for (Guard guard : guards) {
			Optional<String> reason = guard.keep(candidate);
			if (reason.isPresent()) {
				findings.kept(candidate.issueType(), candidate.symbol(), location, guard.id(),
						label + " " + candidate.name() + " is kept: " + reason.get());
				return false;
			}
		}
		findings.issues.add(new AnalysisIssue(candidate.issueType(), severity, candidate.symbol(), location,
				unusedMessage, autoFixable));
		return true;
	}

	private static void analyzeDependencies(ScannedProject project, Findings findings) {
		if (project.buildFiles().isEmpty()) {
			return;
		}
		DependencyUsage usage = new DependencyUsage(project);
		Set<String> seen = new HashSet<>();
		for (ScannedProject.BuildFile buildFile : project.buildFiles()) {
			List<DeclaredDependency> declared = switch (buildFile.tool()) {
				case MAVEN -> MavenPomParser.parse(buildFile.relativePath(), buildFile.content());
				case GRADLE -> GradleBuildParser.parse(buildFile.relativePath(), buildFile.content());
			};
			for (DeclaredDependency dependency : declared) {
				if (!seen.add(buildFile.relativePath() + "::" + dependency.coordinates())) {
					continue;
				}
				String location = SourceLocation.of(buildFile.relativePath(), dependency.artifactLine(), 0).format();
				Optional<DependencyUsage.Reason> reason = DependencyUsage.keepReason(dependency);
				if (reason.isPresent()) {
					findings.kept(IssueType.UNUSED_DEPENDENCY, dependency.coordinates(), location, reason.get().guard(),
							"Dependency " + dependency.coordinates() + " is kept: " + reason.get().message());
				}
				else if (!usage.isUsed(dependency)) {
					findings.issues.add(new AnalysisIssue(IssueType.UNUSED_DEPENDENCY, Severity.WARNING,
							dependency.coordinates(), location,
							"Dependency " + dependency.coordinates() + " is declared but never imported", true));
				}
			}
		}
	}

	private static String baselineName(AnalysisConfig config) {
		Path file = config.baseline().orElseThrow().toAbsolutePath().normalize();
		Path root = config.projectRoot().toAbsolutePath().normalize();
		return file.startsWith(root) ? root.relativize(file).toString() : file.toString();
	}

	private static String locationOf(JavaSourceFile file, int offset) {
		return SourceLocation.of(file.relativePath(), file.lineMap().lineOf(offset), file.lineMap().columnOf(offset))
			.format();
	}

	private static String kindLabel(TypeDeclaration type) {
		String kind = type.kind().name().toLowerCase(Locale.ROOT);
		return Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
	}

	private static List<Guard> concat(List<Guard> first, List<Guard> second) {
		List<Guard> all = new ArrayList<>(first);
		all.addAll(second);
		return all;
	}

	private static final class Findings {

		final List<AnalysisIssue> issues = new ArrayList<>();

		final List<KeptSymbol> kept = new ArrayList<>();

		private final boolean explain;

		Findings(boolean explain) {
			this.explain = explain;
		}

		void kept(IssueType type, String symbol, String location, String guard, String message) {
			if (explain) {
				kept.add(new KeptSymbol(type, symbol, location, guard, message));
			}
		}

	}

}
