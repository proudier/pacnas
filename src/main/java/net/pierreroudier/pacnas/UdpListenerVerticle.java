package net.pierreroudier.pacnas;

import java.net.InetSocketAddress;

import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Handler;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.datagram.DatagramSocket;
import org.vertx.java.core.datagram.InternetProtocolFamily;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.platform.Verticle;

/**
 * Listen to UDP request and forward them to the ResolutionVerticle via the
 * Vertx bus
 * 
 * @author pierre
 *
 */
public class UdpListenerVerticle extends Verticle {

	private Logger logger;
	private DatagramSocket socket;

	public void start() {
		logger = container.logger();
		logger.trace("Starting UDP Listener..");

		String addr = "0.0.0.0";
		int port = 5353;
		socket = vertx.createDatagramSocket(InternetProtocolFamily.IPv4);
		socket.setReuseAddress(true);
		socket.listen(addr, port, asyncResult -> {
			if (asyncResult.succeeded()) {
				socket.dataHandler(packet -> {
					logger.trace("UDP request received");

					final InetSocketAddress requestSender = packet.sender();

					vertx.eventBus().send(ResolutionVerticle.BUS_ADDRESS, packet.data().getBytes(), new Handler<Message<byte[]>>() {
						public void handle(Message<byte[]> message) {
							logger.trace("Sending response to " + requestSender.getAddress().getHostAddress() + " on port "
									+ requestSender.getPort());

							socket.send(new Buffer(message.body()), requestSender.getAddress().getHostAddress(), requestSender.getPort(),
									new AsyncResultHandler<DatagramSocket>() {
										public void handle(AsyncResult<DatagramSocket> asyncResult) {
											if (asyncResult.succeeded()) {
												logger.trace("Response sent successfully");
											} else {
												logger.error("Failed to send response", asyncResult.cause());
											}

										}

									});
						}
					});
				});
			} else {
				logger.error("Listen failed", asyncResult.cause());
				if (socket != null)
					socket.close();
				container.exit();
			}
		});
	}

	public void stop() {
		logger.info("Closing UdpListenerVerticle..");
		if (socket != null)
			socket.close();
	}

}
