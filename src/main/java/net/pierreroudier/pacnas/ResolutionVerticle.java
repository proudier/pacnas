package net.pierreroudier.pacnas;

import net.pierreroudier.pacnas.store.InMemoryJavaHashmapStore;
import net.pierreroudier.pacnas.store.Store;

import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Handler;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.datagram.DatagramPacket;
import org.vertx.java.core.datagram.DatagramSocket;
import org.vertx.java.core.datagram.InternetProtocolFamily;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.platform.Verticle;
import org.xbill.DNS.Address;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Opcode;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;

public class ResolutionVerticle extends Verticle {
	public static String BUS_ADDRESS = "r";
	private static int MAX_RECURSION_ALLOWED = 10;

	private Logger logger;
	private AsyncResultHandler<DatagramSocket> onSentToRemoteServer;
	private Handler<org.vertx.java.core.eventbus.Message<byte[]>> myHandler;

	private final StatsManager statsManager = new StatsManager();
	private final Store store = new InMemoryJavaHashmapStore();

	public void start() {
		logger = container.logger();
		logger.info("Starting ResolutionVerticle..");

		onSentToRemoteServer = new AsyncResultHandler<DatagramSocket>() {
			public void handle(AsyncResult<DatagramSocket> asyncResult) {
				if (asyncResult.failed()) {
					logger.error("Error sending query to remote server", asyncResult.cause());
					return;
				}
			}
		};

		myHandler = new Handler<org.vertx.java.core.eventbus.Message<byte[]>>() {
			public void handle(org.vertx.java.core.eventbus.Message<byte[]> message) {
				onIncomingDataPacket(message);
			}
		};
		vertx.eventBus().registerHandler(BUS_ADDRESS, myHandler);
	}

	public void stop() {
		logger.info("Closing ResolutionVerticle..");
		vertx.eventBus().unregisterHandler(BUS_ADDRESS, myHandler);
	}

	private void onIncomingDataPacket(org.vertx.java.core.eventbus.Message<byte[]> vertxBusMessage) {
		logger.info("Resolution request received");
		statsManager.increaseQueryReceived();

		ProcessingContext s = new ProcessingContext();

		try {
			// Interpret input data
			s.vertxBusMessage = vertxBusMessage;
			s.queryMessage = new Message(s.vertxBusMessage.body());
			s.queryRecord = s.queryMessage.getQuestion();
			logger.info("Request is: \"" + s.queryRecord.toString() + "\"");

			// Basic error check
			if (s.queryMessage.getHeader().getFlag(Flags.QR) || s.queryMessage.getHeader().getRcode() != Rcode.NOERROR) {
				logger.info("Request has format error");
				s.returnCode = Rcode.FORMERR;
				generateResponse(s);
			}
			if (s.queryMessage.getHeader().getOpcode() != Opcode.QUERY) {
				logger.info("Handling this kind of request is not implemented");
				s.returnCode = Rcode.NOTIMP;
				generateResponse(s);
			}

			// Query cache
			s.answerRS = store.getRecords(s.queryRecord.getName().toString(), s.queryRecord.getType(),
					s.queryRecord.getDClass());
			if (s.answerRS != null) {
				logger.info("Answering from cache");
				statsManager.increaseQueryAnsweredFromCache();
				s.returnCode = Rcode.NOERROR;
				generateResponse(s);
			} else {
				logger.info("Starting recursive resolution..");
				s.saveAnswersToStore = true;

				s.recursionCtx = new RecursionContext();
				s.recursionCtx.socket = vertx.createDatagramSocket(InternetProtocolFamily.IPv4);
				s.recursionCtx.currentNS = RecursionContext.ROOT_NS;
				Record queryRecord = Record.newRecord(s.queryRecord.getName(), s.queryRecord.getType(),
						s.queryRecord.getDClass());
				s.recursionCtx.queryMessage = Message.newQuery(queryRecord);
				s.recursionCtx.queryMessage.getHeader().unsetFlag(Flags.RD);

				foobar(s);

				s.recursionCtx.socket.dataHandler(new Handler<DatagramPacket>() {
					public void handle(DatagramPacket dp) {
						try {
							Message response = new Message(dp.data().getBytes());
							if (s.recursionCtx.queryMessage.getHeader().getID() != response.getHeader().getID())
								throw new Exception("ID mismatch, remote server is broken");
							if (response.getRcode() != response.getHeader().getRcode()) {
								throw new Exception("response.getRcode() != response.getHeader().getRcode()");
							}

							logger.info("Received  " + response.getSectionArray(Section.QUESTION).length
									+ " question, " + response.getSectionArray(Section.ANSWER).length + " answer, "
									+ response.getSectionArray(Section.AUTHORITY).length + " authority, "
									+ response.getSectionArray(Section.ADDITIONAL).length + " additional. Rcode="
									+ Rcode.string(response.getRcode()));

							switch (response.getRcode()) {
							case Rcode.NXDOMAIN:
								s.returnCode = Rcode.NXDOMAIN;
								generateResponse(s);
								s.vertxBusMessage.reply(s.response);
								break;
							case Rcode.NOERROR:
								if (response.getHeader().getFlag(Flags.AA)) {
									if (response.getSectionArray(Section.ANSWER).length > 0) {
										logger.info("Got authoritative answer with ANSWER records");
									} else {
										logger.info("Got empty authoritative answer");
									}
									s.answerRS = response.getSectionArray(Section.ANSWER);
									s.returnCode = Rcode.NOERROR;
									generateResponse(s);
									if (s.saveAnswersToStore) {
										logger.trace("Saving to store");
										store.putRecords(s.queryRecord.getName().toString(), s.queryRecord.getType(),
												s.queryRecord.getDClass(), s.answerRS);
									}
									s.vertxBusMessage.reply(s.response);
								} else {
									if (response.getSectionArray(Section.ANSWER).length == 0
											&& response.getSectionArray(Section.AUTHORITY).length > 0) {
										logger.info("Response is a redirection to referral");

										String server = response.getSectionArray(Section.AUTHORITY)[0]
												.getAdditionalName().toString();
										logger.info("Now using " + server);

										s.recursionCtx.currentNS = Address.getByName(server);
										foobar(s);

										// Record authorityRecord =
										// response.getSectionArray(Section.AUTHORITY)[0];
										// Record authorityIpRecordQuery =
										// Record.newRecord(
										// authorityRecord.getAdditionalName(),
										// Type.A,
										// authorityRecord.getDClass());
										// Record[] records =
										// recurse(authorityIpRecordQuery);
										// if (records != null) {
										// logger.info("Resolved referral " +
										// authorityRecord.getAdditionalName()
										// + " to IP " +
										// records[0].rdataToString());
										// s.recursionCtx.currentNS =
										// Address.getByAddress(records[0].rdataToString());
										// foobar(s);
										// } else {
										// throw new
										// Exception("we\'re not supposed to be here");
										// }

									} else {
										throw new Exception("we\'re not supposed to be here");
									}
								}
								break;

							default:
								throw new Exception("Pacnas does not know what to do (yet) out of this return code: "
										+ Rcode.string(response.getRcode()));
							}
						} catch (Exception e) {
							logger.error(e);
							if (s.recursionCtx.socket != null) {
								s.recursionCtx.socket.close();
							}
						}
					}
				});
			}
		} catch (Exception e) {
			s.returnCode = Rcode.SERVFAIL;
			s.answerRS = null;
			generateResponse(s);
			logger.error("Oups", e);
		}

		if (s.responseReady) {
			s.vertxBusMessage.reply(s.response);
		} else {
			logger.error("Unhandled internal error, this request was not answered.");
		}
	}

	private void generateResponse(ProcessingContext s) {
		logger.info("Preparing response..");

		if (s.returnCode == ProcessingContext.RETURN_CODE_INVALID_VALUE) {
			logger.error("No return code was set, assuming SERVFAIL");
			s.returnCode = Rcode.SERVFAIL;
		}

		Message msg = new Message();
		msg.getHeader().setRcode(s.returnCode);
		msg.getHeader().setFlag(Flags.RA);
		msg.getHeader().setFlag(Flags.QR);

		if (s.queryMessage != null && s.queryMessage.getHeader() != null) {
			if (s.queryMessage.getHeader().getFlag(Flags.RD)) {
				msg.getHeader().setFlag(Flags.RD);
			}
			msg.getHeader().setID(s.queryMessage.getHeader().getID());
		}
		if (s.queryRecord != null) {
			msg.addRecord(s.queryRecord, Section.QUESTION);
		}

		if (s.answerRS != null && s.answerRS.length > 0) {
			for (Record r : s.answerRS) {
				if (r != null) {
					msg.addRecord(r, Section.ANSWER);
					logger.info(">> " + r.toString());
				}
			}
		}
		s.response = msg.toWire(UdpServerRunnable.DNS_UDP_MAXLENGTH);
		s.responseReady = true;
		logger.info("Ready to be transmitted");
	}

	private void foobar(ProcessingContext s) throws Exception {
		if (s.recursionCtx.infiniteLoopProtection == MAX_RECURSION_ALLOWED) {
			throw new Exception("Maximum recursion limit reached");
		} else {
			s.recursionCtx.infiniteLoopProtection++;
		}

		logger.info("Sending question to remote nameserver " + s.recursionCtx.currentNS.getHostAddress() + ": \""
				+ s.recursionCtx.queryMessage.getQuestion() + "\"");

		Buffer buffer = new Buffer(s.recursionCtx.queryMessage.toWire(UdpServerRunnable.DNS_UDP_MAXLENGTH));
		s.recursionCtx.socket.send(buffer, s.recursionCtx.currentNS.getHostAddress(), 53, onSentToRemoteServer);

	}
}