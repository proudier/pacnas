package net.pierreroudier.pacnas;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BootstrapVerticle extends AbstractVerticle {
	private final Logger logger = LoggerFactory.getLogger(BootstrapVerticle.class);

	public void start() {
		logger.trace("Starting BootstrapVerticle");

		DeploymentOptions rvOptions = new DeploymentOptions().setInstances(8);
		vertx.deployVerticle("net.pierreroudier.pacnas.ResolutionVerticle", rvOptions, rvResult -> {
			if (rvResult.succeeded()) {
				logger.trace("ResolutionVerticle deployment id is: {}", rvResult.result());

				DeploymentOptions ulvOptions = new DeploymentOptions().setHa(true);
				vertx.deployVerticle("net.pierreroudier.pacnas.UdpListenerVerticle", ulvOptions, ulvResult -> {
					if (ulvResult.succeeded()) {
						logger.trace("UdpListenerVerticle deployment id is: {}", ulvResult.result());
					} else {
						logger.error("UdpListenerVerticle deployment failed; exiting");
						vertx.close();
					}
				});

			} else {
				logger.error("ResolutionVerticle deployment failed; exiting");
				vertx.close();
			}
		});

	}

	public void stop() {
		logger.trace("Stopping BootstrapVerticle");
	}

}
