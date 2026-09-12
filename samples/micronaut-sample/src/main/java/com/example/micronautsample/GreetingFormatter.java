package com.example.micronautsample;

import java.util.List;

final class GreetingFormatter {

	private static final String LEGACY_SEPARATOR = " - ";

	private GreetingFormatter() {
	}

	static String format(String prefix, String name) {
		return prefix + ", " + name + "!";
	}

	static final class Samples {

		private Samples() {
		}

		static List<String> names() {
			return List.of("World", "Micronaut");
		}

	}

}
