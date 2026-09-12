package com.patbaumgartner.prune.core.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class JavaLexer {

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "_");

    private JavaLexer() {
    }

    public static List<Token> tokenize(String source) {
        List<Token> tokens = new ArrayList<>();
        int length = source.length();
        int i = 0;
        while (i < length) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c) || c == '\uFEFF') {
                i++;
            } else if (c == '/' && i + 1 < length && source.charAt(i + 1) == '/') {
                int end = i;
                while (end < length && source.charAt(end) != '\n' && source.charAt(end) != '\r') {
                    end++;
                }
                tokens.add(new Token(TokenKind.COMMENT, source.substring(i, end), i, end));
                i = end;
            } else if (c == '/' && i + 1 < length && source.charAt(i + 1) == '*') {
                int close = source.indexOf("*/", i + 2);
                int end = close < 0 ? length : close + 2;
                tokens.add(new Token(TokenKind.COMMENT, source.substring(i, end), i, end));
                i = end;
            } else if (c == '"') {
                int end = source.startsWith("\"\"\"", i) ? textBlockEnd(source, i + 3) : stringEnd(source, i + 1, '"');
                tokens.add(new Token(TokenKind.STRING, source.substring(i, end), i, end));
                i = end;
            } else if (c == '\'') {
                int end = stringEnd(source, i + 1, '\'');
                tokens.add(new Token(TokenKind.CHAR, source.substring(i, end), i, end));
                i = end;
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < length && Character.isDigit(source.charAt(i + 1)))) {
                int end = numberEnd(source, i);
                tokens.add(new Token(TokenKind.NUMBER, source.substring(i, end), i, end));
                i = end;
            } else if (Character.isJavaIdentifierStart(c)) {
                int end = i + 1;
                while (end < length && Character.isJavaIdentifierPart(source.charAt(end))) {
                    end++;
                }
                String word = source.substring(i, end);
                tokens.add(new Token(KEYWORDS.contains(word) ? TokenKind.KEYWORD : TokenKind.IDENTIFIER, word, i, end));
                i = end;
            } else {
                tokens.add(new Token(TokenKind.PUNCTUATION, String.valueOf(c), i, i + 1));
                i++;
            }
        }
        return tokens;
    }

    private static int stringEnd(String source, int from, char quote) {
        int i = from;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == quote) {
                return i + 1;
            } else if (c == '\n' || c == '\r') {
                return i;
            } else {
                i++;
            }
        }
        return source.length();
    }

    private static int textBlockEnd(String source, int from) {
        int i = from;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (source.startsWith("\"\"\"", i)) {
                return i + 3;
            } else {
                i++;
            }
        }
        return source.length();
    }

    private static int numberEnd(String source, int from) {
        int i = from;
        boolean hex = source.startsWith("0x", from) || source.startsWith("0X", from);
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
                i++;
            } else if ((c == '+' || c == '-') && i > from && isExponentMarker(source.charAt(i - 1), hex)) {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    private static boolean isExponentMarker(char c, boolean hex) {
        return hex ? c == 'p' || c == 'P' : c == 'e' || c == 'E';
    }
}
