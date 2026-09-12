package com.example.springsample;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuditTrailTest {

	@Test
	void keepsEveryRecordedGreeting() {
		var trail = new AuditTrail();

		trail.record("Hello, World!");

		assertEquals(List.of("Hello, World!"), trail.entries());
	}

}
