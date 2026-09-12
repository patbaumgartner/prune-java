package com.example.helidonsample;

import io.helidon.webclient.http1.Http1Client;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.testing.junit5.ServerTest;
import io.helidon.webserver.testing.junit5.SetUpRoute;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ServerTest
class GreetServiceTest {

	private final Http1Client client;

	GreetServiceTest(Http1Client client) {
		this.client = client;
	}

	@SetUpRoute
	static void routing(HttpRouting.Builder routing) {
		routing.register("/greet", new GreetService("Hello"));
	}

	@Test
	void greetsEverySampleName() {
		for (var name : SampleNames.all()) {
			assertEquals("Hello " + name + "!", client.get("/greet/" + name).requestEntity(String.class));
		}
	}

}
