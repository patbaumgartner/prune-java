package com.patbaumgartner.prune.core.dependency;

public record DeclaredDependency(
        String groupId,
        String artifactId,
        String scope,
        String type,
        String classifier,
        String buildFile,
        int artifactLine,
        int startLine,
        int endLine
) {
    public String coordinates() {
        return groupId + ":" + artifactId;
    }

    public boolean spans(int line) {
        return line >= startLine && line <= endLine;
    }

    public boolean isAnalyzableScope() {
        return switch (scope) {
            case "", "compile", "provided" -> true;
            default -> false;
        };
    }

    public boolean isPlainJar() {
        return (type.isEmpty() || type.equals("jar")) && classifier.isEmpty();
    }
}
