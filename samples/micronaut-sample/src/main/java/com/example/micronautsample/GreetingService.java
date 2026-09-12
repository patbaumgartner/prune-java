package com.example.micronautsample;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;

import java.time.Clock;

@Singleton
class GreetingService {

	private final Clock clock;

	private final Cache<String, String> greetings = Caffeine.newBuilder().maximumSize(100).build();

	@Property(name = "greeting.prefix", defaultValue = "Hello")
	protected String prefix;

	GreetingService(Clock clock) {
		this.clock = clock;
	}

	String greet(String name) {
		return greetings.get(name, key -> GreetingFormatter.format(prefix, key)) + " (" + clock.instant() + ")";
	}

	private String legacyGreeting(String name) {
		return "Hello " + name + " (legacy)";
	}

}
