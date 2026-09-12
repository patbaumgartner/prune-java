package com.patbaumgartner.prune.core.source;

import java.util.Arrays;

// Lines and columns are one-based; columns count UTF-16 code units, like javac and LSP clients.
public final class LineMap {

    private final int[] lineStarts;
    private final int length;

    public LineMap(String text) {
        this.length = text.length();
        int[] starts = new int[Math.max(16, text.length() / 32)];
        int count = 0;
        starts[count++] = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || (c == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n'))) {
                if (count == starts.length) {
                    starts = Arrays.copyOf(starts, count * 2);
                }
                starts[count++] = i + 1;
            }
        }
        this.lineStarts = Arrays.copyOf(starts, count);
    }

    public int lineCount() {
        return lineStarts.length;
    }

    public int lineOf(int offset) {
        int clamped = Math.max(0, Math.min(offset, length));
        int index = Arrays.binarySearch(lineStarts, clamped);
        return (index >= 0 ? index : -index - 2) + 1;
    }

    public int columnOf(int offset) {
        int clamped = Math.max(0, Math.min(offset, length));
        return clamped - lineStarts[lineOf(clamped) - 1] + 1;
    }

    public int startOf(int line) {
        if (line < 1) {
            return 0;
        }
        return line > lineStarts.length ? length : lineStarts[line - 1];
    }
}
