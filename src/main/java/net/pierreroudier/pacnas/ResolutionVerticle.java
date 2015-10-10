package net.pierreroudier.pacnas;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResultHandler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramPacket;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Vector;

import net.pierreroudier.pacnas.store.RedisStore;
import net.pierreroudier.pacnas.store.Store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Name;
import org.xbill.DNS.Opcode;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

public class ResolutionVerticle extends AbstractVerticle {
  public static final String BUS_ADDRESS = "r";
  private static final int DNS_UDP_STD_PORT = 53;
  private static int MAX_RECURSION_ALLOWED = 10;

  private final Logger logger = LoggerFactory.getLogger(ResolutionVerticle.class);
  private AsyncResultHandler<DatagramSocket> onSentToRemoteServer;

  private static final StatsManager statsManager = new StatsManager();
  //	private final Store store = new InMemoryJavaHashmapStore();
  private Store store;

  public void start() {
    logger.trace("Starting ResolutionVerticle");

    store = new RedisStore(vertx);

    onSentToRemoteServer = asyncResult -> {
      if (asyncResult.failed()) {
        logger.error("Error sending query to remote server", asyncResult.cause());
        // TODO try with next NS. If all fails, eventually response to
        // the user with an error
      }
    };

    vertx.eventBus().consumer(BUS_ADDRESS, message -> {
      onIncomingDataPacket(message);
    });
  }

  public void stop() {
    logger.trace("Stopping ResolutionVerticle");
  }

  private void onIncomingDataPacket(final io.vertx.core.eventbus.Message<Object> vertxBusMessage) {
    logger.trace("Resolution request received via Vertx bus (msg hash={})", vertxBusMessage.hashCode());
    statsManager.increaseQueryReceived();

    final ProcessingContext s = new ProcessingContext();

    try {
      unmarshalBusMessage(vertxBusMessage, s);
      incomingRequestBasicCheck(s);

      // Lookup in cache
      s.answerRS = store.getRecords(s.incomingQueryRecord.getName().toString(), s.incomingQueryRecord.getType());
      if (s.answerRS != null) {
        // From cache
        logger.trace("Answering from cache");
        statsManager.increaseQueryAnsweredFromCache();
        generateResponse(s, Rcode.NOERROR);
      } else {
        // Not from cache
        logger.trace("Not found in cache, starting recursive resolution");
        prepareRecursion(s);
        sendToRemoteServer(s);
        s.recursionCtx.socket.handler(dataPacket -> {
          // ==ASYNC ======================================================================
          // Response from remote server
          try {
            unmarshalRemoteServerResponse(dataPacket, s);
            org.xbill.DNS.Message response = s.recursionCtx.remoteServerResponse;
            remoteServerResponseCheck(s, response);

            switch (response.getRcode()) {
            case Rcode.NXDOMAIN:
              s.answerRS = null;
              generateResponse(s, Rcode.NXDOMAIN);
              break;
            case Rcode.REFUSED:
              s.answerRS = null;
              generateResponse(s, Rcode.REFUSED);
              break;
            case Rcode.SERVFAIL:
              s.answerRS = null;
              generateResponse(s, Rcode.NOERROR);
              break;
            case Rcode.NOERROR:
              if (response.getHeader().getFlag(Flags.AA)) {
                onAuthoritativeResponse(s, response);
              } else {
                onNonAuthoritativeResponse(s, response);
              }
              break;

            default:
              throw new Exception("Pacnas does not know what to do (yet) out of this return code: "
                  + Rcode.string(response.getRcode()));
            }
          } catch (RemoteServerResponseException e) {
            logger.trace(e.getMessage());
            s.answerRS = null;
            generateResponse(s, e.getOutcomeReturnCode());
          } catch (Exception e) {
            logger.error("Oups", e);
            s.answerRS = null;
            generateResponse(s, Rcode.SERVFAIL);
          } finally {
            possiblyConclude(s);
          }
          // ==ASYNC ======================================================================
        });
      }
    } catch (IncomingRequestException e) {
      logger.trace(e.getMessage());
      s.answerRS = null;
      generateResponse(s, e.getOutcomeReturnCode());
    } catch (Exception e) {
      logger.error("Oups", e);
      s.answerRS = null;
      generateResponse(s, Rcode.SERVFAIL);
    } finally {
      possiblyConclude(s);
    }
  }

  private void onNonAuthoritativeResponse(final ProcessingContext s, org.xbill.DNS.Message response) throws Exception {
    if (response.getSectionArray(Section.ANSWER).length > 0) {
      logger.trace("Answer from non-authoritative server will be discarded");
    }

    if (response.getSectionArray(Section.AUTHORITY).length > 0) {
      logger.trace("Populating RecursionContext's nameserver list from AUTHORITY section");

      s.recursionCtx.nameserversToUse.clear();
      for (Record authorityRecord : response.getSectionArray(Section.AUTHORITY)) {
        if (authorityRecord.getType() != Type.NS) {
          logger.info("Ignored authority record because type is not NS");
          continue;
        }

        String authoritativeServerName = authorityRecord.rdataToString();
        RecursionContext.NameserverCoordinate nc = new RecursionContext.NameserverCoordinate(authoritativeServerName);

        if (response.getSectionArray(Section.ADDITIONAL).length > 0) {
          // Lookup in ADDITIONAL section for potential IP address sent by remote server
          logger.trace("Looking for the following server's address in ADDITIONAL section: \"{}\"",
              authoritativeServerName);

          // TODO this is overly complicated for now, as we only support one IP per remoteserver name.
          List<String> authNsIp = new ArrayList<String>();
          for (Record additionnalRecord : response.getSectionArray(Section.ADDITIONAL)) {
            if (additionnalRecord.getName().toString().equals(authoritativeServerName)) {
              String ss = additionnalRecord.rdataToString();
              logger.trace("Found referral's IP: \"{}\"", ss);
              if (ss.indexOf(':') == -1) {
                // ipv4
                authNsIp.add(ss);
              } else {
                // ipv6
                logger.trace("IPv6 address discarded");
              }
            }
          }
          if (authNsIp.size() > 0) {
            nc.setIp(authNsIp.get(0));
          } else {
            logger.trace("Referral IP was not found in ADDITIONNAL section for server {}", authoritativeServerName);
          }
        }

        s.recursionCtx.nameserversToUse.add(nc);
        logger.trace("Added new remote nameserver to recursion context: {}", nc);
      }

      sendToRemoteServer(s);
    } else {
      logger.warn(
          "Got a non-authoritative response without any authority servers therefore initial request will NOT be answered");
    }
  }

  private void onAuthoritativeResponse(final ProcessingContext s, org.xbill.DNS.Message response) {
    if (response.getSectionArray(Section.ANSWER).length > 0) {
      logger.trace("Got authoritative response with ANSWER records");
    } else {
      logger.trace("Got empty authoritative answer");
    }
    s.answerRS = response.getSectionArray(Section.ANSWER);
    generateResponse(s, Rcode.NOERROR);
    if (s.saveAnswersToStore) {
      logger.trace("Saving to store");
      store.putRecords(s.incomingQueryRecord.getName().toString(), s.incomingQueryRecord.getType(), s.answerRS);
    }
  }

  private void remoteServerResponseCheck(final ProcessingContext s, org.xbill.DNS.Message response)
      throws RemoteServerResponseException {
    if (s.recursionCtx.remoteServerQueryMessage.getHeader().getID() != response.getHeader().getID()) {
      throw new RemoteServerResponseException("ID mismatch, remote server is broken", Rcode.NOERROR);
    }
    if (response.getRcode() != response.getHeader().getRcode()) {
      throw new RemoteServerResponseException("response.getRcode() != response.getHeader().getRcode()", Rcode.NOERROR);
    }

    if (logger.isTraceEnabled()) {
      StringBuilder sb = new StringBuilder();
      sb.append("Received response with ");
      sb.append(response.getSectionArray(Section.QUESTION).length);
      sb.append(" question, ");
      sb.append(response.getSectionArray(Section.ANSWER).length);
      sb.append(" answer, ");
      sb.append(response.getSectionArray(Section.AUTHORITY).length);
      sb.append(" authority, ");
      sb.append(response.getSectionArray(Section.ADDITIONAL).length);
      sb.append(" additional. Rcode=");
      sb.append(Rcode.string(response.getRcode()));
      logger.trace(sb.toString());
    }
  }

  private void unmarshalBusMessage(final io.vertx.core.eventbus.Message<Object> vertxBusMessage,
      final ProcessingContext s) throws IncomingRequestException, IOException {
    s.vertxBusMessage = vertxBusMessage;
    s.vertxBusMessageBody = (byte[]) vertxBusMessage.body();
    try {
      s.queryMessage = new org.xbill.DNS.Message(s.vertxBusMessageBody);
    } catch (org.xbill.DNS.WireParseException e) {
      throw new IncomingRequestException("Incoming request has format error", Rcode.FORMERR);
    }

    s.incomingQueryRecord = s.queryMessage.getQuestion();
    logger.trace("Request is: \"{}\"", s.incomingQueryRecord.toString());
  }

  private void unmarshalRemoteServerResponse(final DatagramPacket dataPacket, final ProcessingContext s)
      throws RemoteServerResponseException, IOException {
    try {
      s.recursionCtx.remoteServerResponse = new org.xbill.DNS.Message(dataPacket.data().getBytes());
    } catch (org.xbill.DNS.WireParseException e) {
      throw new RemoteServerResponseException("Response from remote server has format error", Rcode.NOERROR);
    }
  }

  private void incomingRequestBasicCheck(final ProcessingContext s) throws IncomingRequestException {
    if (s.queryMessage.getHeader().getFlag(Flags.QR) || s.queryMessage.getHeader().getRcode() != Rcode.NOERROR) {
      throw new IncomingRequestException("Incoming request has format error", Rcode.FORMERR);
    }
    if (s.queryMessage.getHeader().getOpcode() != Opcode.QUERY) {
      throw new IncomingRequestException(
          "The following opcode is not supported by Pacnas: " + s.queryMessage.getHeader().getOpcode(), Rcode.NOTIMP);
    }
    if (s.incomingQueryRecord.getDClass() != DClass.IN) {
      throw new IncomingRequestException(
          "The following class is not supported by Pacnas: " + s.incomingQueryRecord.getDClass(), Rcode.NOTIMP);
    }
  }

  private void generateResponse(final ProcessingContext s, int returnCodeToSet) {
    if (s.responseReady == true) {
      logger.error(
          "Attempted to overwrite a response, but nothing was done. This is probably due to a faulty logic in the code.");
    } else {
      logger.trace("Preparing response");

      if (s.returnCode != ProcessingContext.RETURN_CODE_INVALID_VALUE) {
        logger.error(
            "Return code was already set. It has not been changed but this is probably due to a faulty logic in the code.");
      } else {
        s.returnCode = returnCodeToSet;
      }

      org.xbill.DNS.Message msg = new org.xbill.DNS.Message();
      msg.getHeader().setRcode(s.returnCode);
      msg.getHeader().setFlag(Flags.RA);
      msg.getHeader().setFlag(Flags.QR);

      if (s.queryMessage != null && s.queryMessage.getHeader() != null) {
        if (s.queryMessage.getHeader().getFlag(Flags.RD)) {
          msg.getHeader().setFlag(Flags.RD);
        }
        msg.getHeader().setID(s.queryMessage.getHeader().getID());
      }
      if (s.incomingQueryRecord != null) {
        msg.addRecord(s.incomingQueryRecord, Section.QUESTION);
      }

      if (s.answerRS != null && s.answerRS.length > 0) {
        for (Record r : s.answerRS) {
          if (r != null) {
            msg.addRecord(r, Section.ANSWER);
            logger.trace(">> " + r.toString());
          }
        }
      }
      s.response = msg.toWire(UdpListenerVerticle.DNS_UDP_MAXLENGTH);
      s.responseReady = true;
      logger.trace("Ready to be transmitted");
    }
  }

  private void possiblyConclude(final ProcessingContext s) {
    if (s.responseReady) {
      logger.trace("Replying to message with hash=" + s.vertxBusMessage.hashCode());
      s.vertxBusMessage.reply(s.response);
      if (s.recursionCtx != null && s.recursionCtx.socket != null) {
        s.recursionCtx.socket.close();
      }
    }
  }

  private void prepareRecursion(final ProcessingContext s) throws Exception {
    statsManager.increaseQueryAnsweredByResolution();
    s.saveAnswersToStore = true;
    s.recursionCtx = new RecursionContext();
    s.recursionCtx.socket = vertx.createDatagramSocket(new DatagramSocketOptions());
    Record remoteServerQueryRecord = Record.newRecord(s.incomingQueryRecord.getName(), s.incomingQueryRecord.getType(),
        s.incomingQueryRecord.getDClass());
    s.recursionCtx.remoteServerQueryMessage = org.xbill.DNS.Message.newQuery(remoteServerQueryRecord);
    s.recursionCtx.remoteServerQueryMessage.getHeader().unsetFlag(Flags.RD);
    searchCacheForBestNameserver(s);
  }

  private void sendToRemoteServer(final ProcessingContext s) throws Exception {
    if (s.recursionCtx.infiniteLoopProtection == MAX_RECURSION_ALLOWED) {
      throw new Exception("Maximum recursion limit reached");
    } else {
      s.recursionCtx.infiniteLoopProtection++;
    }

    // Shuffle so as to even the load on remote servers
    Collections.shuffle(s.recursionCtx.nameserversToUse);

    RecursionContext.NameserverCoordinate nc = s.recursionCtx.nameserversToUse.get(0);
    if (nc.getIp() == null) {
      //resolve(s);
      throw new Exception("plouf");
    }

    logger.trace("Sending question to remote nameserver {}: {}", nc.toString(),
        s.recursionCtx.remoteServerQueryMessage.getQuestion());

    Buffer buffer = Buffer
        .buffer(s.recursionCtx.remoteServerQueryMessage.toWire(UdpListenerVerticle.DNS_UDP_MAXLENGTH));
    s.recursionCtx.socket.send(buffer, DNS_UDP_STD_PORT, nc.getIp(), onSentToRemoteServer);

  }

  /**
   * Look up in the cache if we already know some NS best suited to respond to
   * this query
   * 
   * @param s
   *          ProcessingContext
   * @throws Exception
   */
  private void searchCacheForBestNameserver(final ProcessingContext s) throws Exception {
    // Find matching NS RR
    List<String> nameServerList = new Vector<String>();
    Name queryRecordName = s.incomingQueryRecord.getName();
    for (int i = 0; i < queryRecordName.labels(); i++) {
      StringBuilder sb = new StringBuilder();
      for (int j = i; j < queryRecordName.labels(); j++) {
        sb.append(queryRecordName.getLabelString(j));
        sb.append(".");
      }
      String domainName = sb.toString();
      Record[] records = store.getRecords(domainName, Type.NS);
      if (records != null) {
        for (Record r : records) {
          nameServerList.add(r.getName().toString());
        }
        break;
      }
    }

    if (nameServerList.size() > 0) {
      // Find IP of matching NS
      for (String serverName : nameServerList) {
        Record[] records = store.getRecords(serverName, Type.A);
        for (Record r : records) {
          // TODO do the real stuff
          // s.recursionCtx.nameserversToUse.add(r.rdataToString());
        }
      }
    } else {
      // If no matching NS were found above, use the root-servers
      s.recursionCtx.nameserversToUse
          .add(new RecursionContext.NameserverCoordinate("i.root-servers.net", "192.36.148.17"));
      s.recursionCtx.nameserversToUse
          .add(new RecursionContext.NameserverCoordinate("j.root-servers.net", "192.58.128.30"));
      s.recursionCtx.nameserversToUse
          .add(new RecursionContext.NameserverCoordinate("l.root-servers.net", "199.7.83.42"));
      s.recursionCtx.nameserversToUse
          .add(new RecursionContext.NameserverCoordinate("c.root-servers.net", "192.33.4.12"));
    }
  }

  /*
  private void resolve(final ProcessingContext s) {
  	Record authorityRecord = response.getSectionArray(Section.AUTHORITY)[0];
  	Record authorityIpRecordQuery = Record
  			.newRecord(authorityRecord.getAdditionalName(), Type.A, authorityRecord.getDClass());
  	byte[] requestNsBytesArray = org.xbill.DNS.Message.newQuery(authorityIpRecordQuery).toWire(
  			UdpListenerVerticle.DNS_UDP_MAXLENGTH);
  
  	vertx.eventBus()
  			.send(ResolutionVerticle.BUS_ADDRESS, requestNsBytesArray, busSendResult -> {
  				try {
  					org.xbill.DNS.Message mmm = new org.xbill.DNS.Message((byte[]) busSendResult.result().body());
  					Record[] records = mmm.getSectionArray(Section.ANSWER);
  					if (records.length > 0) {
  						// TODO handle
  						// multiplicity in
  						// logger.trace bellow
  					logger.trace("Resolved referral " + authorityRecord.getAdditionalName() + " to IP "
  							+ records[0].rdataToString());
  					s.recursionCtx.nameserversToUse.clear();
  					for (Record r : records) {
  						s.recursionCtx.nameserversToUse.add(r.rdataToString());
  					}
  					Collections.shuffle(s.recursionCtx.nameserversToUse);
  					sendToRemoteServer(s);
  				} else {
  					logger.trace("Found no IP for referral");
  					s.answerRS = null;
  					generateResponse(s, Rcode.NOERROR);
  				}
  			} catch (Exception e) {
  				logger.error("Oups", e);
  				s.answerRS = null;
  				generateResponse(s, Rcode.SERVFAIL);
  			}
  
  			if (s.responseReady) {
  				if (s.recursionCtx.socket != null) {
  					s.recursionCtx.socket.close();
  				}
  				logger.trace("Replying to message with hash=" + s.vertxBusMessage.hashCode());
  				s.vertxBusMessage.reply(s.response);
  			}
  		}	);
  }*/
}
