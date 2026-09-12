package com.example.springsample;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GreetingServiceTest {

	@Test
	void greetsWithTheCapitalizedNameAndTheDefaultLocale() {
		var service = new GreetingService(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

		assertEquals("null, World! [de-CH] (1970-01-01T00:00:00Z)", service.greet("world"));
	}

}
