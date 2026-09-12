package com.example.gradlesample;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StockAuditorTest {

	@Test
	void recordsEveryAuditedLevel() {
		var auditor = new StockAuditor();

		auditor.record(new StockLevel(7));

		assertEquals(List.of("7 units in ZRH-1"), auditor.entries());
	}

}
