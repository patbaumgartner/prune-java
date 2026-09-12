package com.example.quarkussample;

import java.util.List;

final class GreetingSamples {

	private GreetingSamples() {
	}

	static List<String> names() {
		return List.of("world", "quarkus");
	}

}
