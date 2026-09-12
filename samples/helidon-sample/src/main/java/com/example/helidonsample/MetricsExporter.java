package com.example.helidonsample;

import java.util.logging.Logger;

// Instantiated reflectively by Main from the app.exporter entry in application.yaml.
final class MetricsExporter implements Runnable {

	private static final Logger LOG = Logger.getLogger(MetricsExporter.class.getName());

	@Override
	public void run() {
		LOG.info("metrics exporter started");
	}

}
