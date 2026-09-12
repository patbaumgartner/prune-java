package com.example.micronautsample;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

import java.time.Clock;

@Factory
class ClockFactory {

	@Singleton
	Clock clock() {
		return Clock.systemUTC();
	}

}
