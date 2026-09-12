package com.example.micronautsample;

import java.util.List;

final class LegacyExporter {

	String export(List<Greeting> greetings) {
		return greetings.stream().map(Greeting::message).reduce("", (a, b) -> a + b + System.lineSeparator());
	}

}
