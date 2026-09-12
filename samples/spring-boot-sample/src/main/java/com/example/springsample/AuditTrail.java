package com.example.springsample;

import java.util.ArrayList;
import java.util.List;

final class AuditTrail {

	private final List<String> entries = new ArrayList<>();

	void record(String greeting) {
		entries.add(greeting);
	}

	List<String> entries() {
		return List.copyOf(entries);
	}

}
