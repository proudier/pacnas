package net.pierreroudier.pacnas;

import io.vertx.core.AbstractVerticle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainVerticle extends AbstractVerticle {
	private final Logger logger = LoggerFactory.getLogger(MainVerticle.class);

	public void start() {
		logger.trace("Starting MainVerticle");
		vertx.deployVerticle("net.pierreroudier.pacnas.ResolutionVerticle");
		vertx.deployVerticle("net.pierreroudier.pacnas.UdpListenerVerticle");
	}

	public void stop() {
		logger.trace("Stopping MainVerticle");
	}

}
