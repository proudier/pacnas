package net.pierreroudier.pacnas;

import org.vertx.java.core.logging.Logger;
import org.vertx.java.platform.Verticle;

public class MainVerticle extends Verticle {
	private Logger logger;

	public void start() {
		logger = container.logger();
		logger.info("Starting MainVerticle..");
		container.deployVerticle("net.pierreroudier.pacnas.ResolutionVerticle");
		container.deployVerticle("net.pierreroudier.pacnas.UdpListenerVerticle");
	}

	public void stop() {
		logger.info("Stopping MainVerticle..");
	}

}
