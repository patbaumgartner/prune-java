package com.patbaumgartner.prune.core.report;

public enum OutputFormat {

	TERMINAL, GITHUB_ANNOTATION, JSON;

	public static OutputFormat parse(String value) {
		return switch (value) {
			case "terminal" -> TERMINAL;
			case "github" -> GITHUB_ANNOTATION;
			case "json" -> JSON;
			default -> throw new IllegalArgumentException("Unsupported format: " + value);
		};
	}

}
