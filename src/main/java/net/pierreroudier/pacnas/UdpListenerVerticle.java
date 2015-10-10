package net.pierreroudier.pacnas;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.net.SocketAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listen to UDP request and forward them to the ResolutionVerticle via the
 * Vertx bus
 */
public class UdpListenerVerticle extends AbstractVerticle {

  public static final int DNS_UDP_MAXLENGTH = 512;

  private final Logger logger = LoggerFactory.getLogger(UdpListenerVerticle.class);
  private DatagramSocket socket;

  public void start() {
    logger.trace("Starting UDP Listener");

    // configuration.setThrowExceptionOnMissing();
    //  String getEncodedString(String key, ConfigurationDecoder decoder);
    //		Parameters params = new Parameters();
    //		FileBasedConfigurationBuilder<XMLConfiguration> builder =
    //		    new FileBasedConfigurationBuilder<XMLConfiguration>(XMLConfiguration.class)
    //		    .configure(params.xml()
    //		        .setFileName("myconfig.xml")
    //		        .setValidating(true));

    // TODO should be stored in a configuration repository somewhere
    String addr = "0.0.0.0";
    int port = 5353;

    DatagramSocketOptions dso = new DatagramSocketOptions();
    dso.setReuseAddress(true);
    socket = vertx.createDatagramSocket(dso);

    socket.listen(port, addr, asyncResult -> {
      if (asyncResult.succeeded()) {
        logger.info("Pacnas is listing for UDP request");
        socket.handler(packet -> {
          final SocketAddress requestSender = packet.sender();
          logger.trace("UDP request received from {}", requestSender.host());

          vertx.eventBus().send(ResolutionVerticle.BUS_ADDRESS, packet.data().getBytes(), busSendResult -> {
            if (busSendResult.succeeded()) {
              logger.trace("Sending response to {} on port {}", requestSender.host(), requestSender.port());
              Buffer buffer = Buffer.buffer((byte[]) busSendResult.result().body());
              socket.send(buffer, requestSender.port(), requestSender.host(), respSendResult -> {
                if (respSendResult.succeeded()) {
                  logger.trace("Response sent successfully");
                } else {
                  logger.warn("Failed to send response", respSendResult.cause());
                }
              });
            } else {
              logger.error("Error while sending to Vertx bus and/or handling the message", busSendResult.cause());
            }
          });
        });
      } else {
        logger.error("Listening failed; shutting down", asyncResult.cause());
        if (socket != null)
          socket.close();
        vertx.close();
      }
    });
  }

  public void stop() {
    logger.trace("Stopping UdpListenerVerticle");
    if (socket != null)
      socket.close();
  }

}
