package com.example.micronautsample;

import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
class StartupLogger {

	private static final Logger LOG = LoggerFactory.getLogger(StartupLogger.class);

	@EventListener
	void onStartup(StartupEvent event) {
		LOG.info("micronaut-sample started");
	}

}
