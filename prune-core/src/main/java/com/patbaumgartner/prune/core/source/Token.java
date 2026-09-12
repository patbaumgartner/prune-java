package com.patbaumgartner.prune.core.source;

public record Token(TokenKind kind, String text, int start, int end) {

    public boolean is(TokenKind expected, String expectedText) {
        return kind == expected && text.equals(expectedText);
    }

    public boolean isPunctuation(char symbol) {
        return kind == TokenKind.PUNCTUATION && text.length() == 1 && text.charAt(0) == symbol;
    }

    public boolean isKeyword(String keyword) {
        return is(TokenKind.KEYWORD, keyword);
    }

    public boolean isIdentifier(String identifier) {
        return is(TokenKind.IDENTIFIER, identifier);
    }
}
