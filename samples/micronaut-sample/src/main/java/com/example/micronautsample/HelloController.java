package com.example.micronautsample;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;

import java.util.Optional;

@Controller("/greeting")
class HelloController {

	private final GreetingService service;

	HelloController(GreetingService service) {
		this.service = service;
	}

	@Get
	public Greeting greet(@QueryValue Optional<String> name) {
		return new Greeting(service.greet(name.orElse(Defaults.NAME)));
	}

}
