package com.patbaumgartner.prune.core.analyzer;

import com.patbaumgartner.prune.core.config.AnalysisConfig;
import com.patbaumgartner.prune.core.dependency.DeclaredDependency;
import com.patbaumgartner.prune.core.dependency.DependencyUsage;
import com.patbaumgartner.prune.core.dependency.GradleBuildParser;
import com.patbaumgartner.prune.core.dependency.MavenPomParser;
import com.patbaumgartner.prune.core.project.ProjectScanner;
import com.patbaumgartner.prune.core.project.ScannedProject;
import com.patbaumgartner.prune.core.report.AnalysisIssue;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import com.patbaumgartner.prune.core.report.IssueType;
import com.patbaumgartner.prune.core.report.Severity;
import com.patbaumgartner.prune.core.report.SourceLocation;
import com.patbaumgartner.prune.core.source.JavaSourceFile;
import com.patbaumgartner.prune.core.source.MemberDeclaration;
import com.patbaumgartner.prune.core.source.TypeDeclaration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ConservativeUnusedCodeAnalyzer implements UnusedCodeAnalyzer {

    private static final Set<String> SERIALIZATION_METHODS = Set.of(
            "writeObject", "readObject", "readObjectNoData", "writeReplace", "readResolve", "writeExternal", "readExternal");
    private static final Set<String> SERIALIZATION_FIELDS = Set.of("serialVersionUID", "serialPersistentFields");
    private static final Comparator<AnalysisIssue> ISSUE_ORDER = Comparator
            .comparing((AnalysisIssue issue) -> SourceLocation.parse(issue.location()).path())
            .thenComparingInt(issue -> SourceLocation.parse(issue.location()).line())
            .thenComparingInt(issue -> SourceLocation.parse(issue.location()).column())
            .thenComparing(AnalysisIssue::type)
            .thenComparing(AnalysisIssue::symbol);

    @Override
    public AnalysisReport analyze(AnalysisConfig config) {
        ScannedProject project;
        try {
            project = ProjectScanner.scan(config);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        ReferenceIndex index = new ReferenceIndex(project);
        List<AnalysisIssue> issues = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (ScannedProject.JavaFile file : project.candidates()) {
            if (file.source().opaque()) {
                skipped.add(file.source().relativePath());
                continue;
            }
            for (TypeDeclaration type : file.source().types()) {
                analyzeTopLevelType(file.source(), type, index, issues);
            }
        }
        analyzeDependencies(project, issues);
        issues.sort(ISSUE_ORDER);

        String summary = "Analyzed " + project.candidates().size() + " Java file(s) under " + config.projectRoot()
                + ": " + issues.size() + " issue(s) found (conservative mode)."
                + (skipped.isEmpty() ? "" : " Skipped " + skipped.size() + " file(s) the parser could not follow: "
                + String.join(", ", skipped));
        return new AnalysisReport(issues, summary, true);
    }

    private static void analyzeTopLevelType(JavaSourceFile file, TypeDeclaration type, ReferenceIndex index,
                                            List<AnalysisIssue> issues) {
        String qualifiedName = file.qualifiedName(type);
        boolean frameworkOrEntryPoint = type.annotated() || type.hasMainMethod();

        if (!type.isPublic() && !frameworkOrEntryPoint && !index.typeReferenced(file, type)) {
            issues.add(new AnalysisIssue(IssueType.UNUSED_CLASS, Severity.WARNING, qualifiedName,
                    locationOf(file, type.nameOffset()),
                    kindLabel(type) + " " + type.name() + " is never referenced", false));
            return;
        }

        if (type.isPublic() && !frameworkOrEntryPoint && !type.documented() && isVisibilityCandidate(type)
                && !index.moduleDescriptorPresent()
                && index.typeReferenceScope(file, type) == ReferenceIndex.Scope.SAME_PACKAGE_ONLY) {
            issues.add(new AnalysisIssue(IssueType.UNUSED_VISIBILITY, Severity.INFO, qualifiedName,
                    locationOf(file, type.nameOffset()),
                    "Public class " + type.name() + " is only used from package " + file.packageName()
                            + " and can be package-private", type.publicModifierOffset() >= 0));
        }

        analyzeMembers(file, type, qualifiedName, index, issues);
    }

    // A public class is only offered for visibility reduction when nothing outside it could hold
    // an instance: every constructor is private and every non-private member is static.
    private static boolean isVisibilityCandidate(TypeDeclaration type) {
        if (type.kind() != TypeDeclaration.TypeKind.CLASS || type.constructors().isEmpty()) {
            return false;
        }
        for (MemberDeclaration member : type.members()) {
            boolean constructor = member.kind() == MemberDeclaration.MemberKind.CONSTRUCTOR;
            if (constructor && !member.isPrivate()) {
                return false;
            }
            if (!constructor && member.kind() != MemberDeclaration.MemberKind.INITIALIZER
                    && !member.isPrivate() && !member.isStatic()) {
                return false;
            }
        }
        return true;
    }

    private static void analyzeMembers(JavaSourceFile file, TypeDeclaration type, String qualifiedName,
                                       ReferenceIndex index, List<AnalysisIssue> issues) {
        boolean fieldsReadReflectively = type.annotated() || isSerializable(type);
        for (MemberDeclaration member : type.members()) {
            if (!member.isPrivate() || member.annotated()) {
                continue;
            }
            switch (member.kind()) {
                case METHOD -> {
                    if (SERIALIZATION_METHODS.contains(member.name()) || member.hasModifier("native")) {
                        continue;
                    }
                    if (!index.memberReferenced(file, member)) {
                        issues.add(new AnalysisIssue(IssueType.UNUSED_METHOD, Severity.WARNING,
                                qualifiedName + "#" + member.name(), locationOf(file, member.nameOffset()),
                                "Private method " + member.name() + " is never used", true));
                    }
                }
                case FIELD -> {
                    if (SERIALIZATION_FIELDS.contains(member.name()) || fieldsReadReflectively
                            || (!member.isStatic() && index.reflectiveSerializationInUse())) {
                        continue;
                    }
                    if (!index.memberReferenced(file, member)) {
                        issues.add(new AnalysisIssue(IssueType.UNUSED_FIELD, Severity.WARNING,
                                qualifiedName + "#" + member.name(), locationOf(file, member.nameOffset()),
                                "Private field " + member.name() + " is never used", member.declaratorCount() == 1));
                    }
                }
                case CONSTRUCTOR, INITIALIZER -> {
                }
            }
        }
        for (TypeDeclaration nested : type.nestedTypes()) {
            String nestedName = qualifiedName + "." + nested.name();
            if (isDeadNestedType(file, type, nested, index)) {
                issues.add(new AnalysisIssue(IssueType.UNUSED_CLASS, Severity.WARNING, nestedName,
                        locationOf(file, nested.nameOffset()),
                        "Nested " + kindLabel(nested).toLowerCase(Locale.ROOT) + " " + nested.name()
                                + " is never referenced", false));
                continue;
            }
            analyzeMembers(file, nested, nestedName, index, issues);
        }
    }

    // Members of interfaces and annotation types are implicitly public, and an annotated outer type
    // may have its nested types picked up by a framework (Lombok builders, JPA embeddables).
    private static boolean isDeadNestedType(JavaSourceFile file, TypeDeclaration outer, TypeDeclaration nested,
                                            ReferenceIndex index) {
        boolean implicitlyPublic = outer.kind() == TypeDeclaration.TypeKind.INTERFACE
                || outer.kind() == TypeDeclaration.TypeKind.ANNOTATION;
        if (implicitlyPublic || nested.isPublic() || nested.isProtected() || nested.annotated() || outer.annotated()
                || nested.hasMainMethod()) {
            return false;
        }
        return !index.typeReferenced(file, nested);
    }

    private static boolean isSerializable(TypeDeclaration type) {
        return type.superTypes().contains("Serializable") || type.superTypes().contains("Externalizable");
    }

    private static void analyzeDependencies(ScannedProject project, List<AnalysisIssue> issues) {
        if (project.buildFiles().isEmpty()) {
            return;
        }
        DependencyUsage usage = new DependencyUsage(project);
        Set<String> reported = new HashSet<>();
        for (ScannedProject.BuildFile buildFile : project.buildFiles()) {
            List<DeclaredDependency> declared = switch (buildFile.tool()) {
                case MAVEN -> MavenPomParser.parse(buildFile.relativePath(), buildFile.content());
                case GRADLE -> GradleBuildParser.parse(buildFile.relativePath(), buildFile.content());
            };
            for (DeclaredDependency dependency : declared) {
                if (!DependencyUsage.isAnalyzable(dependency) || usage.isUsed(dependency)
                        || !reported.add(buildFile.relativePath() + "::" + dependency.coordinates())) {
                    continue;
                }
                issues.add(new AnalysisIssue(IssueType.UNUSED_DEPENDENCY, Severity.WARNING, dependency.coordinates(),
                        SourceLocation.of(buildFile.relativePath(), dependency.artifactLine(), 0).format(),
                        "Dependency " + dependency.coordinates() + " is declared but never imported", true));
            }
        }
    }

    private static String locationOf(JavaSourceFile file, int offset) {
        return SourceLocation.of(file.relativePath(), file.lineMap().lineOf(offset), file.lineMap().columnOf(offset))
                .format();
    }

    private static String kindLabel(TypeDeclaration type) {
        String kind = type.kind().name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
    }
}
