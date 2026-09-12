package com.patbaumgartner.prune.core.fix;

import com.patbaumgartner.prune.core.report.AnalysisIssue;

import java.nio.file.Path;
import java.util.Objects;

public record FixAction(
        AnalysisIssue issue,
        Path file,
        int startOffset,
        int endOffset,
        String original,
        String replacement,
        String description
) {
    public FixAction {
        Objects.requireNonNull(issue, "issue");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(replacement, "replacement");
        Objects.requireNonNull(description, "description");
        if (startOffset < 0 || endOffset < startOffset) {
            throw new IllegalArgumentException("invalid span " + startOffset + ".." + endOffset);
        }
        if (original.length() != endOffset - startOffset) {
            throw new IllegalArgumentException("original text does not cover the span " + startOffset + ".." + endOffset);
        }
    }
}
