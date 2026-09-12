package com.patbaumgartner.prune.core.project;

import java.util.regex.Pattern;

public final class GlobMatcher {

    private final Pattern pattern;
    private final String glob;

    private GlobMatcher(String glob, Pattern pattern) {
        this.glob = glob;
        this.pattern = pattern;
    }

    public static GlobMatcher compile(String glob) {
        String normalized = glob.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        StringBuilder regex = new StringBuilder("^");
        int i = 0;
        boolean inGroup = false;
        while (i < normalized.length()) {
            char c = normalized.charAt(i);
            if (c == '*' && normalized.startsWith("**/", i)) {
                regex.append("(?:.*/)?");
                i += 3;
            } else if (c == '*' && normalized.startsWith("**", i)) {
                regex.append(".*");
                i += 2;
            } else if (c == '*') {
                regex.append("[^/]*");
                i++;
            } else if (c == '?') {
                regex.append("[^/]");
                i++;
            } else if (c == '{') {
                regex.append("(?:");
                inGroup = true;
                i++;
            } else if (c == '}' && inGroup) {
                regex.append(')');
                inGroup = false;
                i++;
            } else if (c == ',' && inGroup) {
                regex.append('|');
                i++;
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        if (inGroup) {
            throw new IllegalArgumentException("Invalid glob pattern '" + glob + "': missing '}'");
        }
        regex.append('$');
        return new GlobMatcher(glob, Pattern.compile(regex.toString()));
    }

    public boolean matches(String relativePath) {
        return pattern.matcher(relativePath.replace('\\', '/')).matches();
    }

    @Override
    public String toString() {
        return glob;
    }
}
