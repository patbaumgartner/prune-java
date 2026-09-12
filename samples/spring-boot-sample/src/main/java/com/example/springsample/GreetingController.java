package com.example.springsample;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class GreetingController {

	private final GreetingService service;

	GreetingController(GreetingService service) {
		this.service = service;
	}

	@GetMapping("/greeting")
	String greeting(@RequestParam(defaultValue = "world") String name) {
		return service.greet(name);
	}

}
