package com.example.quarkussample;

import org.apache.commons.text.WordUtils;

final class GreetingFormatter {

	private static final String LEGACY_SEPARATOR = " - ";

	private GreetingFormatter() {
	}

	static String format(String name, String suffix) {
		return "Hello, " + WordUtils.capitalize(name) + suffix;
	}

}
