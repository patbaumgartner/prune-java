package com.example.springsample;

import java.util.Locale;

final class GreetingFormatter {

	private static final String LEGACY_SEPARATOR = " - ";

	private GreetingFormatter() {
	}

	static String format(String prefix, String name, Locale locale) {
		return prefix + ", " + name + "!" + (locale.getLanguage().isEmpty() ? "" : " [" + locale.toLanguageTag() + "]");
	}

}
