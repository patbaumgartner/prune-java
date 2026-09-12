package com.example.gradlesample;

import java.util.ArrayList;
import java.util.List;

final class StockAuditor {

	private final List<String> entries = new ArrayList<>();

	void record(StockLevel level) {
		entries.add(level.available());
	}

	List<String> entries() {
		return List.copyOf(entries);
	}

}
