package com.patbaumgartner.prune.core.fix;

import java.nio.file.Path;

public record FixAction(
        Path file,
        int startOffset,
        int endOffset,
        String replacement,
        String description
) {
}
