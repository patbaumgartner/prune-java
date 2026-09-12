package com.example.helidonsample;

import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;

final class GreetService implements HttpService {

	private final String greeting;

	private static final String LEGACY_PREFIX = "Hello";

	GreetService(String greeting) {
		this.greeting = greeting;
	}

	@Override
	public void routing(HttpRules rules) {
		rules.get("/", this::greetWorld).get("/{name}", this::greetNamed);
	}

	private void greetWorld(ServerRequest request, ServerResponse response) {
		response.send(greeting + " " + GreetingCodes.DEFAULT_NAME + "!");
	}

	private void greetNamed(ServerRequest request, ServerResponse response) {
		response.send(greeting + " " + request.path().pathParameters().get("name") + "!");
	}

	private void legacyGreet(ServerRequest request, ServerResponse response) {
		response.send("Hello " + request.path().pathParameters().get("name") + " (legacy)");
	}

}
