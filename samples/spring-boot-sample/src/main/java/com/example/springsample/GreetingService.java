package com.example.springsample;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.commons.text.WordUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Locale;

@Service
class GreetingService {

	private final Clock clock;

	@Value("${greeting.prefix:Hello}")
	private String prefix;

	GreetingService(Clock clock) {
		this.clock = clock;
	}

	String greet(String name) {
		return GreetingFormatter.format(prefix, WordUtils.capitalize(name),
				Locale.forLanguageTag(SupportCodes.DEFAULT_LOCALE)) + " (" + clock.instant() + ")";
	}

	@PostConstruct
	private void warmUp() {
		greet("warm-up");
	}

	@PreDestroy
	private void release() {
		prefix = null;
	}

	private String legacyGreeting(String name) {
		return "Hello " + name + " (legacy)";
	}

}
