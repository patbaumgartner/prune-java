package com.example.quarkussample;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GreetingServiceTest {

	@Test
	void greetsEverySampleName() {
		var service = new GreetingService();
		service.suffix = "!";

		for (var name : GreetingSamples.names()) {
			assertEquals("Hello, " + Character.toUpperCase(name.charAt(0)) + name.substring(1) + "! [200]",
					service.greet(name));
		}
	}

}
