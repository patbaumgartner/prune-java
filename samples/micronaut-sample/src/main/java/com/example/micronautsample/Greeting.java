package com.example.micronautsample;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record Greeting(String message) {
}
