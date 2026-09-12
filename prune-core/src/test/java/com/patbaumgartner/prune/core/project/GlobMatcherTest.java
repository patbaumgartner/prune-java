package com.patbaumgartner.prune.core.project;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobMatcherTest {

    @Test
    void braceGroupsMatchAnyAlternativeAndDoubleStarSpansDirectories() {
        var matcher = GlobMatcher.compile("**/{generated,stubs}/*.java");

        assertTrue(matcher.matches("src/main/java/generated/A.java"));
        assertTrue(matcher.matches("stubs/B.java"));
        assertFalse(matcher.matches("src/main/java/generated/deep/A.java"));
        assertFalse(matcher.matches("src/main/java/other/A.java"));
    }

    @Test
    void anUnclosedBraceGroupIsRejectedWithTheOffendingPattern() {
        var invalid = assertThrows(IllegalArgumentException.class, () -> GlobMatcher.compile("**/{Foo"));

        assertEquals("Invalid glob pattern '**/{Foo': missing '}'", invalid.getMessage());
    }

    @Test
    void aStrayClosingBraceIsLiteral() {
        assertTrue(GlobMatcher.compile("a}b").matches("a}b"));
    }
}
