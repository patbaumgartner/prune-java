package com.example.springsample;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
class GreetingConfiguration {

	private static final String LEGACY_ZONE = "Europe/Zurich";

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
