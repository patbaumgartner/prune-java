package com.patbaumgartner.prune.core.report;

import java.util.Objects;

// A candidate the analyzer did not report, with the guard that kept it. Only produced
// when explanations were requested; a symbol with a plain reference is never listed.
public record KeptSymbol(IssueType type, String symbol, String location, String guard, String message) {
	public KeptSymbol {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(symbol, "symbol");
		Objects.requireNonNull(location, "location");
		Objects.requireNonNull(guard, "guard");
		Objects.requireNonNull(message, "message");
	}
}
