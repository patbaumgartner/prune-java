package com.example.micronautsample;

import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
class HelloControllerTest {

	@Inject
	@Client("/")
	HttpClient client;

	@Test
	void greetsTheWorldByDefault() {
		var greeting = client.toBlocking().retrieve("/greeting", Greeting.class);

		assertTrue(greeting.message().startsWith("Hello, World!"), greeting.message());
	}

}
