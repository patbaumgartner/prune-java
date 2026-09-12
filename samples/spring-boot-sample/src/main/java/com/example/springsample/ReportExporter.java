package com.example.springsample;

import java.util.List;

final class ReportExporter {

	String export(List<String> greetings) {
		return String.join(System.lineSeparator(), greetings);
	}

}
