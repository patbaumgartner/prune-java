package com.example.micronautsample;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GreetingFormatterTest {

	@Test
	void formatsEverySampleName() {
		for (var name : GreetingFormatter.Samples.names()) {
			assertEquals("Hello, " + name + "!", GreetingFormatter.format("Hello", name));
		}
	}

}
