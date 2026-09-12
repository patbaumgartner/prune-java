package com.example.springsample;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

// Registered in META-INF/spring.factories; no Java source names this class.
final class StartupListener implements ApplicationListener<ApplicationReadyEvent> {

	@Override
	public void onApplicationEvent(ApplicationReadyEvent event) {
		System.out.println("spring-boot-sample ready after " + event.getTimeTaken());
	}

}
