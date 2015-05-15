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

public class ListenerVerticle extends Verticle {

	private static int MAX_RECURSION_ALLOWED = 10;

	private Logger logger;
	private DatagramSocket socket;
	private AsyncResultHandler<DatagramSocket> onSentToRemoteServer;
	private final StatsManager statsManager = new StatsManager();
	private final Store store = new InMemoryJavaHashmapStore();

	public void start() {
		logger = container.logger();
		logger.info("Starting Listener..");

		onSentToRemoteServer = new AsyncResultHandler<DatagramSocket>() {
			public void handle(AsyncResult<DatagramSocket> asyncResult) {
				if (asyncResult.failed()) {
					logger.error("Error sending query to remote server", asyncResult.cause());
					return;
				}
			}
		};

		String addr = "0.0.0.0";
		int port = 5353;
		socket = vertx.createDatagramSocket(InternetProtocolFamily.IPv4);
		socket.setReuseAddress(true);
		socket.listen(addr, port, new AsyncResultHandler<DatagramSocket>() {
			public void handle(AsyncResult<DatagramSocket> asyncResult) {
				if (asyncResult.succeeded()) {
					socket.dataHandler(new Handler<DatagramPacket>() {
						public void handle(DatagramPacket packet) {
							onIncomingDataPacket(packet);
						}
					});
					logger.info("Pacnas is waiting for requests");
				} else {
					logger.error("Listen failed", asyncResult.cause());
					if (socket != null)
						socket.close();
					container.exit();
				}
			}
		});
	}

	public void stop() {
		logger.info("Closing");
		if (socket != null)
			socket.close();
	}

	private void onIncomingDataPacket(DatagramPacket packet) {
		logger.info("UDP request received");
		statsManager.increaseQueryReceived();

		ProcessingContext s = new ProcessingContext();

		try {
			s.requestSender = packet.sender();
			s.queryMessage = new Message(packet.data().getBytes());
			s.queryRecord = s.queryMessage.getQuestion();
			logger.info("Request is: \"" + s.queryRecord.toString() + "\"");

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

			s.answerRS = store.getRecords(s.queryRecord.getName().toString(), s.queryRecord.getType(),
					s.queryRecord.getDClass());
			if (s.answerRS != null) {
				logger.info("Answering from cache");
				statsManager.increaseQueryAnsweredFromCache();
				s.returnCode = Rcode.NOERROR;
				generateResponse(s);
			} else {
				logger.info("Starting recursive resolution..");

				s.recursionCtx = new RecursionContext();
				s.recursionCtx.currentNS = RecursionContext.ROOT_NS;
				s.recursionCtx.socket = vertx.createDatagramSocket(InternetProtocolFamily.IPv4);

				Record queryRecord = Record.newRecord(s.queryRecord.getName(), s.queryRecord.getType(),
						s.queryRecord.getDClass());
				s.recursionCtx.queryMessage = Message.newQuery(queryRecord);
				s.recursionCtx.queryMessage.getHeader().unsetFlag(Flags.RD);

				s.saveAnswersToStore = true;
				
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
								sendResponse(s);
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
									sendResponse(s);
								} else {
									if (response.getSectionArray(Section.ANSWER).length == 0
											&& response.getSectionArray(Section.AUTHORITY).length > 0) {
										logger.info("Response is a redirection to referral");
										
										String server = response
												.getSectionArray(Section.AUTHORITY)[0].getAdditionalName().toString();
										logger.info(server);

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
			sendResponse(s);
		} else {
			if (s.answerRS == null) {

			} else {
				logger.error("Unhandled internal error, this request was not answered.");
			}

		}
	}

	// public void onRecursionCompleted(ProcessingContext s) {
	// s.saveAnswersToStore = true;
	// s.returnCode = Rcode.NOERROR;
	// generateResponse(s);
	// sendResponse(s);
	// }

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
		byte[] response = msg.toWire(UdpServerRunnable.DNS_UDP_MAXLENGTH);
		s.responseBuffer = new Buffer(response);
		s.responseReady = true;
		logger.info("Ready to be transmitted");
	}

	private void sendResponse(ProcessingContext s) {
		logger.info("Sending response to " + s.requestSender.getAddress().getHostAddress() + " on port "
				+ s.requestSender.getPort());
		socket.send(s.responseBuffer, s.requestSender.getAddress().getHostAddress(), s.requestSender.getPort(),
				new AsyncResultHandler<DatagramSocket>() {
					public void handle(AsyncResult<DatagramSocket> asyncResult) {
						if (asyncResult.succeeded()) {
							logger.info("Response sent successfully" + " " + s.queryRecord);
							if (s.saveAnswersToStore) {
								logger.info("Saving to store");
								store.putRecords(s.queryRecord.getName().toString(), s.queryRecord.getType(),
										s.queryRecord.getDClass(), s.answerRS);
							}
						} else {
							logger.error("Failed to send response", asyncResult.cause());
						}

					}

				});
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
