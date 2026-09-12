package com.patbaumgartner.prune.core.source;

import java.util.List;

public record MemberDeclaration(
        MemberKind kind,
        String name,
        List<String> modifiers,
        boolean annotated,
        int start,
        int nameOffset,
        int end,
        int declaratorCount
) {
    public enum MemberKind {
        FIELD,
        METHOD,
        CONSTRUCTOR,
        INITIALIZER
    }

    public MemberDeclaration {
        modifiers = List.copyOf(modifiers);
    }

    public boolean hasModifier(String modifier) {
        return modifiers.contains(modifier);
    }

    public boolean isPrivate() {
        return hasModifier("private");
    }

    public boolean isStatic() {
        return hasModifier("static");
    }
}
