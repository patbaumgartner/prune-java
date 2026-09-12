package com.patbaumgartner.prune.core.source;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.patbaumgartner.prune.core.config.Baseline;
import com.patbaumgartner.prune.core.dependency.DeclaredDependency;
import com.patbaumgartner.prune.core.dependency.GradleBuildParser;
import com.patbaumgartner.prune.core.dependency.MavenPomParser;

// Jazzer entry point for every hand-rolled parser in prune-core. Fuzz with `./mvnw -pl
// prune-core -Pfuzz test-compile exec:exec`; ParserFuzzerTest replays the committed
// inputs on every build. A parser may reject input, but only in the way its contract
// allows.
public final class ParserFuzzer {

	private ParserFuzzer() {
	}

	public static void fuzzerTestOneInput(FuzzedDataProvider data) {
		check(data.consumeRemainingAsString());
	}

	static void check(String text) {
		checkJavaSource(text);
		checkBuildFiles(text);
		checkBaseline(text);
	}

	private static void checkJavaSource(String text) {
		JavaSourceFile file = JavaSourceParser.parse("Fuzzed.java", text);
		for (TypeDeclaration type : file.types()) {
			checkSpans(type, text);
		}
		for (Token token : file.identifiers()) {
			require(0 <= token.start() && token.start() < token.end() && token.end() <= text.length(),
					"identifier span " + token.start() + ".." + token.end());
		}
		if (!text.isEmpty()) {
			LineMap lines = file.lineMap();
			int last = lines.lineOf(text.length() - 1);
			require(1 <= last && last <= lines.lineCount(), "line of last offset " + last);
		}
	}

	static void checkSpans(TypeDeclaration type, String text) {
		require(0 <= type.start() && type.start() <= type.nameOffset() && type.nameOffset() < type.end()
				&& type.end() <= text.length(), "type span of " + type.name());
		require(text.startsWith(type.name(), type.nameOffset()), "type name at offset of " + type.name());
		for (MemberDeclaration member : type.members()) {
			require(type.start() <= member.start() && member.start() <= member.nameOffset()
					&& member.nameOffset() <= member.end() && member.end() <= type.end(),
					"member span of " + member.name());
		}
		for (TypeDeclaration nested : type.nestedTypes()) {
			checkSpans(nested, text);
		}
	}

	private static void checkBuildFiles(String text) {
		int lines = (int) text.lines().count() + 1;
		for (DeclaredDependency dependency : MavenPomParser.parse("pom.xml", text)) {
			checkAnchored(dependency, lines);
		}
		for (DeclaredDependency dependency : GradleBuildParser.parse("build.gradle", text)) {
			checkAnchored(dependency, lines);
		}
	}

	private static void checkAnchored(DeclaredDependency dependency, int lines) {
		require(!dependency.groupId().isEmpty() && !dependency.artifactId().isEmpty(),
				"coordinates " + dependency.coordinates());
		require(1 <= dependency.startLine() && dependency.startLine() <= dependency.artifactLine()
				&& dependency.artifactLine() <= dependency.endLine() && dependency.endLine() <= lines,
				"lines of " + dependency.coordinates());
	}

	private static void checkBaseline(String text) {
		try {
			Baseline.parse(text, "fuzzed.txt");
		}
		catch (IllegalArgumentException rejected) {
			require(rejected.getMessage().startsWith("fuzzed.txt:"), "baseline rejection " + rejected.getMessage());
		}
	}

	private static void require(boolean condition, String what) {
		if (!condition) {
			throw new IllegalStateException("parser invariant violated: " + what);
		}
	}

}
