package com.patbaumgartner.prune.core.fix;

import java.util.List;

public record FixPlan(List<FixAction> actions) {

    public FixPlan {
        actions = List.copyOf(actions);
    }

    public static FixPlan empty() {
        return new FixPlan(List.of());
    }

    public boolean isEmpty() {
        return actions.isEmpty();
    }
}
