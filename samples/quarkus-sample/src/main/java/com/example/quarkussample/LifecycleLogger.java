package com.example.quarkussample;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

@ApplicationScoped
class LifecycleLogger {

	private static final Logger LOG = Logger.getLogger(LifecycleLogger.class);

	void onStart(@Observes StartupEvent event) {
		LOG.info("quarkus-sample starting");
	}

	// CDI resolves observers by the @Observes parameter; the method itself carries no
	// annotation.
	private void onStop(@Observes ShutdownEvent event) {
		LOG.info("quarkus-sample stopping");
	}

}
