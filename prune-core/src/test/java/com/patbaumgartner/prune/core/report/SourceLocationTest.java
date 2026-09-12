package com.patbaumgartner.prune.core.report;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceLocationTest {

	@Test
	void parsesPathLineAndColumn() {
		var location = SourceLocation.parse("src/main/java/A.java:12:5");

		assertEquals("src/main/java/A.java", location.path());
		assertEquals(12, location.line());
		assertEquals(5, location.column());
		assertTrue(location.hasLine());
		assertTrue(location.hasColumn());
	}

	@Test
	void parsesBarePathsAndLineOnlyLocations() {
		var bare = SourceLocation.parse("pom.xml");
		var lineOnly = SourceLocation.parse("pom.xml:30");

		assertEquals(new SourceLocation("pom.xml", 0, 0), bare);
		assertFalse(bare.hasLine());
		assertEquals(new SourceLocation("pom.xml", 30, 0), lineOnly);
		assertFalse(lineOnly.hasColumn());
	}

	@Test
	void keepsDriveLettersAndOverlongDigitRunsInThePath() {
		assertEquals(new SourceLocation("C:\\src\\A.java", 12, 0), SourceLocation.parse("C:\\src\\A.java:12"));
		assertEquals(new SourceLocation("A.java:99999999999", 0, 0), SourceLocation.parse("A.java:99999999999"));
		assertEquals(new SourceLocation("we\nird.java", 2, 0), SourceLocation.parse("we\nird.java:2"));
	}

	@Test
	void formatsBackToTheSameSuffixShape() {
		assertEquals("A.java", SourceLocation.of("A.java", 0, 0).format());
		assertEquals("A.java:3", SourceLocation.of("A.java", 3, 0).format());
		assertEquals("A.java:3:9", SourceLocation.of("A.java", 3, 9).format());
		assertEquals("A.java", SourceLocation.of("A.java", 0, 9).format());
		for (var text : new String[] { "A.java", "A.java:3", "A.java:3:9", "x:1:2:3" }) {
			assertEquals(text, SourceLocation.parse(text).format());
		}
	}

	@Test
	void parseFormatsOfEveryOutputFormatName() {
		assertEquals(OutputFormat.TERMINAL, OutputFormat.parse("terminal"));
		assertEquals(OutputFormat.GITHUB_ANNOTATION, OutputFormat.parse("github"));
		assertEquals(OutputFormat.JSON, OutputFormat.parse("json"));
		assertEquals(OutputFormat.SARIF, OutputFormat.parse("sarif"));
		var error = assertThrows(IllegalArgumentException.class, () -> OutputFormat.parse("xml"));
		assertEquals("Unsupported format: xml", error.getMessage());
	}

}
