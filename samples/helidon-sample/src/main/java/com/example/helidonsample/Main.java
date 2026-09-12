package com.example.helidonsample;

import io.helidon.config.Config;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;

import java.util.logging.Logger;

public final class Main {

	private static final Logger LOG = Logger.getLogger(Main.class.getName());

	private Main() {
	}

	public static void main(String[] args) throws ReflectiveOperationException {
		Config config = Config.create();
		WebServer server = WebServer.builder()
			.config(config.get("server"))
			.routing(routing -> routing(routing, config))
			.build()
			.start();
		LOG.info("helidon-sample listening on port " + server.port());
		startExporter(config);
	}

	private static void routing(HttpRouting.Builder routing, Config config) {
		routing.register("/greet",
				new GreetService(config.get("app.greeting").asString().orElse(GreetingCodes.DEFAULT_GREETING)));
	}

	// The exporter class is configured, not compiled in: application.yaml names it.
	private static void startExporter(Config config) throws ReflectiveOperationException {
		String exporterClass = config.get("app.exporter").asString().get();
		Runnable exporter = (Runnable) Class.forName(exporterClass).getDeclaredConstructor().newInstance();
		exporter.run();
	}

}
