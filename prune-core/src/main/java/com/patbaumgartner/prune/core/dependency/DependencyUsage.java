package com.patbaumgartner.prune.core.dependency;

import com.patbaumgartner.prune.core.project.ScannedProject;
import com.patbaumgartner.prune.core.source.JavaSourceFile;
import com.patbaumgartner.prune.core.source.Token;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

// Usage is decided from imports, qualified names, string literals, and resources.
// Libraries with a known package root must be referenced under that root; anything else
// counts as used when any distinctive coordinate token appears as a package segment.
// Doubt resolves towards "used".
public final class DependencyUsage {

	private static final Pattern TOKEN_SEPARATOR = Pattern.compile("[.\\-_]+");

	private static final Set<String> GENERIC_TOKENS = Set.of("api", "core", "impl", "all", "lib", "libs", "library",
			"common", "base", "main", "java", "jvm", "jre", "jdk", "org", "com", "net", "io", "de", "ch", "uk", "co",
			"edu", "gov", "me", "github", "gitlab", "code", "googlecode", "sourceforge", "bitbucket", "www", "apache",
			"google", "eclipse", "jakarta", "javax", "sun", "oracle", "microsoft", "utils", "util", "tools", "client",
			"server", "web", "data");

	private static final List<String> RUNTIME_MARKERS = List.of("starter", "bom", "parent", "dependencies", "bundle",
			"runtime", "agent", "devtools", "processor", "native", "platform", "webjar", "driver", "compiler", "embed",
			"jdbc", "provider", "binding", "bindings");

	private static final Set<String> RUNTIME_ONLY_ARTIFACTS = Set.of("logback-classic", "logback-core", "log4j-core",
			"log4j-slf4j-impl", "log4j-slf4j2-impl", "log4j-to-slf4j", "log4j-jul", "log4j-1.2-api", "slf4j-simple",
			"slf4j-nop", "slf4j-jdk14", "slf4j-log4j12", "slf4j-reload4j", "jul-to-slf4j", "jcl-over-slf4j",
			"commons-logging", "jaxb-impl", "hibernate-validator", "h2", "hsqldb", "derby", "postgresql",
			"mysql-connector-java", "mysql-connector-j", "mariadb-java-client", "mssql-jdbc", "sqlite-jdbc", "ojdbc8",
			"ojdbc11", "liquibase-core", "aspectjweaver", "aspectjrt", "kotlin-stdlib", "kotlin-stdlib-jdk8",
			"kotlin-reflect", "scala-library", "groovy", "groovy-all", "jansi", "snakeyaml", "jboss-logging",
			"byte-buddy", "javassist", "jakarta.el", "expressly", "hibernate-core", "hibernate-entitymanager",
			"hibernate-jpamodelgen", "eclipselink", "jaxws-rt", "saaj-impl", "woodstox-core", "bcprov-jdk18on",
			"bcprov-jdk15on", "bcpkix-jdk18on", "bcpkix-jdk15on", "failureaccess", "listenablefuture",
			"error_prone_annotations", "checker-qual", "j2objc-annotations");

	private static final List<String> RUNTIME_ONLY_PREFIXES = List.of("flyway-", "micrometer-registry-",
			"jackson-datatype-", "jackson-module-", "jackson-dataformat-", "jjwt-impl", "jjwt-jackson", "jjwt-gson",
			"spring-boot-starter", "opentelemetry-exporter-", "netty-transport-native-", "tomcat-embed-", "jetty-",
			"undertow-", "grpc-netty", "quarkus-", "micronaut-");

	private static final Set<String> RUNTIME_ONLY_GROUPS = Set.of("org.webjars", "org.webjars.npm",
			"org.webjars.bower");

	// Lombok's logging annotations generate a field whose type the source never names.
	private static final Map<String, List<String>> LOMBOK_LOGGERS = Map.of("lombok.extern.slf4j", List.of("org.slf4j"),
			"lombok.extern.log4j", List.of("org.apache.log4j", "org.apache.logging.log4j"),
			"lombok.extern.apachecommons", List.of("org.apache.commons.logging"), "lombok.extern.jbosslog",
			List.of("org.jboss.logging"), "lombok.extern.flogger", List.of("com.google.common.flogger"));

	private final Set<String> segments;

	private final Set<String> dottedNames;

	public DependencyUsage(ScannedProject project) {
		Set<String> segmentEvidence = new HashSet<>();
		Set<String> nameEvidence = new HashSet<>();
		collectEvidence(project, segmentEvidence, nameEvidence);
		this.segments = segmentEvidence;
		this.dottedNames = nameEvidence;
	}

	public static boolean isAnalyzable(DeclaredDependency dependency) {
		return keepReason(dependency).isEmpty();
	}

	// Why a declared dependency is never a candidate, named like a guard so explanations
	// can show it.
	public static Optional<Reason> keepReason(DeclaredDependency dependency) {
		if (!dependency.isAnalyzableScope()) {
			return Optional.of(new Reason("dependency-scope",
					"it is declared in scope " + dependency.scope() + ", which is not analyzed"));
		}
		if (!dependency.isPlainJar()) {
			return Optional.of(new Reason("dependency-type", "it is not a plain jar dependency"));
		}
		String artifact = dependency.artifactId().toLowerCase(Locale.ROOT);
		if (RUNTIME_ONLY_ARTIFACTS.contains(artifact) || RUNTIME_ONLY_GROUPS.contains(dependency.groupId())
				|| RUNTIME_ONLY_PREFIXES.stream().anyMatch(artifact::startsWith)) {
			return Optional
				.of(new Reason("runtime-only", "it is a runtime-only artifact that is loaded, not imported"));
		}
		for (String token : TOKEN_SEPARATOR.split(artifact, -1)) {
			if (RUNTIME_MARKERS.contains(token)) {
				return Optional
					.of(new Reason("runtime-only", "its artifact name marks it as runtime-only (" + token + ")"));
			}
		}
		if (KnownPackages.rootsOf(dependency) == null && coordinateTokens(dependency).isEmpty()) {
			return Optional.of(new Reason("ambiguous-coordinates",
					"its coordinates carry no distinctive token to match against imports"));
		}
		return Optional.empty();
	}

	public record Reason(String guard, String message) {
	}

	public boolean isUsed(DeclaredDependency dependency) {
		List<String> roots = KnownPackages.rootsOf(dependency);
		if (roots != null) {
			for (String name : dottedNames) {
				for (String root : roots) {
					if (name.equals(root) || name.startsWith(root + ".")) {
						return true;
					}
				}
			}
			return false;
		}
		Set<String> tokens = coordinateTokens(dependency);
		if (tokens.isEmpty()) {
			return true;
		}
		for (String token : tokens) {
			if (segments.contains(token)) {
				return true;
			}
		}
		return false;
	}

	static Set<String> coordinateTokens(DeclaredDependency dependency) {
		Set<String> tokens = new HashSet<>();
		for (String raw : List.of(dependency.groupId(), dependency.artifactId())) {
			for (String token : TOKEN_SEPARATOR.split(raw.toLowerCase(Locale.ROOT), -1)) {
				if (token.length() >= 2 && !GENERIC_TOKENS.contains(token)
						&& !token.chars().allMatch(Character::isDigit)) {
					tokens.add(token);
				}
			}
		}
		return tokens;
	}

	private static void collectEvidence(ScannedProject project, Set<String> segments, Set<String> names) {
		for (ScannedProject.JavaFile file : project.javaFiles()) {
			JavaSourceFile source = file.source();
			for (String name : source.imports()) {
				add(segments, names, name);
				addGeneratedLoggerUsage(segments, names, name);
			}
			for (String name : source.qualifiedNames()) {
				add(segments, names, name);
			}
			for (Token string : source.strings()) {
				addDottedNames(segments, names, string.text());
			}
		}
		for (ScannedProject.ResourceFile resource : project.resources()) {
			addDottedNames(segments, names, resource.content());
			String fileName = resource.relativePath().substring(resource.relativePath().lastIndexOf('/') + 1);
			for (String token : TOKEN_SEPARATOR.split(fileName.toLowerCase(Locale.ROOT), -1)) {
				if (!token.isEmpty()) {
					segments.add(token);
				}
			}
		}
	}

	private static void addGeneratedLoggerUsage(Set<String> segments, Set<String> names, String importName) {
		for (Map.Entry<String, List<String>> logger : LOMBOK_LOGGERS.entrySet()) {
			String annotationPackage = logger.getKey();
			if (importName.equals(annotationPackage) || importName.startsWith(annotationPackage + ".")) {
				for (String root : logger.getValue()) {
					add(segments, names, root);
				}
			}
		}
	}

	// A dotted name is two or more ASCII identifiers joined by single dots, found
	// anywhere in free text: "see org.example.Api." yields org.example.Api. Scanned by
	// hand so the cost stays linear in the text, whatever it contains.
	private static void addDottedNames(Set<String> segments, Set<String> names, String text) {
		int length = text.length();
		int position = 0;
		while (position < length) {
			if (!isNameStart(text.charAt(position))) {
				position++;
				continue;
			}
			int end = nameEnd(text, position);
			int dots = 0;
			while (end + 1 < length && text.charAt(end) == '.' && isNameStart(text.charAt(end + 1))) {
				end = nameEnd(text, end + 1);
				dots++;
			}
			if (dots > 0) {
				add(segments, names, text.substring(position, end));
				position = end;
			}
			else {
				position++;
			}
		}
	}

	private static int nameEnd(String text, int start) {
		int end = start + 1;
		while (end < text.length() && isNamePart(text.charAt(end))) {
			end++;
		}
		return end;
	}

	private static boolean isNameStart(char c) {
		return c == '_' || c == '$' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
	}

	private static boolean isNamePart(char c) {
		return isNameStart(c) || (c >= '0' && c <= '9');
	}

	private static void add(Set<String> segments, Set<String> names, String qualifiedName) {
		if (qualifiedName.startsWith("java.") || qualifiedName.startsWith("jdk.")) {
			return;
		}
		names.add(qualifiedName);
		for (String segment : qualifiedName.split("\\.", -1)) {
			if (!segment.isEmpty() && !"*".equals(segment)) {
				segments.add(segment.toLowerCase(Locale.ROOT));
			}
		}
	}

}
