package com.example.quarkussample;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
class GreetingService {

	@ConfigProperty(name = "greeting.suffix", defaultValue = "!")
	String suffix;

	String greet(String name) {
		return GreetingFormatter.format(name, suffix) + " [" + StatusCodes.OK + "]";
	}

	private String legacyGreeting(String name) {
		return "Hello " + name + " (legacy)";
	}

}
