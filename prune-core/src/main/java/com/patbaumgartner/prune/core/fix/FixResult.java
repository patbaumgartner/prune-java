package com.patbaumgartner.prune.core.fix;

public record FixResult(int filesChanged, int actionsApplied) {

	public static FixResult nothing() {
		return new FixResult(0, 0);
	}
}
