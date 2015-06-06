package net.pierreroudier.pacnas;

import java.util.ArrayList;
import java.util.List;

import net.pierreroudier.pacnas.store.InMemoryJavaHashmapStore;
import net.pierreroudier.pacnas.store.Store;

import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Handler;
import org.vertx.java.core.buffer.Buffer;
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
import org.xbill.DNS.Type;

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
		logger.trace("Starting ResolutionVerticle..");
		
		onSentToRemoteServer = asyncResult -> {
			if (asyncResult.failed()) {
				logger.error("Error sending query to remote server", asyncResult.cause());
				return;
			}
		};
		
		myHandler = message -> onIncomingDataPacket(message);
		
		vertx.eventBus().registerHandler(BUS_ADDRESS, myHandler);
	}

	public void stop() {
		logger.info("Closing ResolutionVerticle..");
		vertx.eventBus().unregisterHandler(BUS_ADDRESS, myHandler);
	}

	private void onIncomingDataPacket(org.vertx.java.core.eventbus.Message<byte[]> vertxBusMessage) {
		logger.trace("Resolution request received via Vertx bus (msg hash="+vertxBusMessage.hashCode()+")");
		statsManager.increaseQueryReceived();

		final ProcessingContext s = new ProcessingContext();

		try {
			// Interpret input data
			s.vertxBusMessage = vertxBusMessage;
			s.queryMessage = new Message(s.vertxBusMessage.body());
			s.queryRecord = s.queryMessage.getQuestion();
			logger.trace("Request is: \"" + s.queryRecord.toString() + "\"");

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
			s.answerRS = store.getRecords(s.queryRecord.getName().toString(), s.queryRecord.getType(), s.queryRecord.getDClass());
			if (s.answerRS != null) {
				logger.trace("Answering from cache");
				statsManager.increaseQueryAnsweredFromCache();
				s.returnCode = Rcode.NOERROR;
				generateResponse(s);
			} else {
				logger.trace("Not found in cache, starting recursive resolution..");
				s.saveAnswersToStore = true;
				statsManager.increaseQueryAnsweredFromForwarder();

				s.recursionCtx = new RecursionContext();
				s.recursionCtx.socket = vertx.createDatagramSocket(InternetProtocolFamily.IPv4);
				s.recursionCtx.currentNS = RecursionContext.ROOT_NS;
				Record queryRecord = Record.newRecord(s.queryRecord.getName(), s.queryRecord.getType(), s.queryRecord.getDClass());
				s.recursionCtx.queryMessage = Message.newQuery(queryRecord);
				s.recursionCtx.queryMessage.getHeader().unsetFlag(Flags.RD);

				foobar(s);

				s.recursionCtx.socket.dataHandler(dataPacket -> {
					try {
						Message response = new Message(dataPacket.data().getBytes());
						if (s.recursionCtx.queryMessage.getHeader().getID() != response.getHeader().getID())
							throw new Exception("ID mismatch, remote server is broken");
						if (response.getRcode() != response.getHeader().getRcode()) {
							throw new Exception("response.getRcode() != response.getHeader().getRcode()");
						}

						logger.trace("Received " + response.getSectionArray(Section.QUESTION).length + " question, "
								+ response.getSectionArray(Section.ANSWER).length + " answer, "
								+ response.getSectionArray(Section.AUTHORITY).length + " authority, "
								+ response.getSectionArray(Section.ADDITIONAL).length + " additional. Rcode="
								+ Rcode.string(response.getRcode()));

						switch (response.getRcode()) {
						case Rcode.NXDOMAIN:
							s.returnCode = Rcode.NXDOMAIN;
							if (s.recursionCtx.socket != null) {
								s.recursionCtx.socket.close();
							}
							generateResponse(s);
							logger.trace("Replying to message with hash=" + s.vertxBusMessage.hashCode());
							s.vertxBusMessage.reply(s.response);
							break;
						case Rcode.NOERROR:
							if (response.getHeader().getFlag(Flags.AA)) {
								// Authoritative answer
								if (response.getSectionArray(Section.ANSWER).length > 0) {
									logger.trace("Got authoritative answer with ANSWER records");
								} else {
									logger.trace("Got empty authoritative answer");
								}
								s.answerRS = response.getSectionArray(Section.ANSWER);
								s.returnCode = Rcode.NOERROR;
								if (s.recursionCtx.socket != null) {
									s.recursionCtx.socket.close();
								}
								generateResponse(s);
								if (s.saveAnswersToStore) {
									logger.trace("Saving to store");
									store.putRecords(s.queryRecord.getName().toString(), s.queryRecord.getType(),
											s.queryRecord.getDClass(), s.answerRS);
								}
								logger.trace("Replying to message with hash=" + s.vertxBusMessage.hashCode());
								s.vertxBusMessage.reply(s.response);
							} else {
								// Non Authoritative answer
								if (response.getSectionArray(Section.ANSWER).length == 0
										&& response.getSectionArray(Section.AUTHORITY).length > 0) {
									logger.trace("Response is a redirection to referral");

									if (response.getSectionArray(Section.ADDITIONAL).length > 0) {
										for (Record authorityRecord : response.getSectionArray(Section.AUTHORITY)) {
											if (authorityRecord.getType() != Type.NS) {
												logger.warn("ignored authority record because type!=NS");
												continue;
											}

											String authorityName = authorityRecord.rdataToString();
											logger.trace("Looking for the following referral's IP in ADDITIONAL section: \""
													+ authorityName + "\"");

											List<String> authNsIp = new ArrayList<String>();
											for (Record additionnalRecord : response.getSectionArray(Section.ADDITIONAL)) {
												if (additionnalRecord.getName().toString().equals(authorityName)) {
													String ss = additionnalRecord.rdataToString();
													logger.trace("Found referral's IP: \"" + ss + "\"");
													if (ss.indexOf(':') == -1) {
														// ipv4
														authNsIp.add(ss);
													} else {
														// ipv6
														logger.trace("IPv6 address discarded");
													}
												}
											}
											if (authNsIp.size() == 0) {
												logger.trace("Referral IP was not found in ADDITIONNAL section");
											} else {
													s.recursionCtx.currentNS = Address.getByAddress(authNsIp.get(0));
												logger.trace("Now using remove nameserver \"" + authorityName + "\" ("
														+ s.recursionCtx.currentNS.getHostAddress() + ")");
												foobar(s);
											}
											break;
										}
									} else {

										String server = response.getSectionArray(Section.AUTHORITY)[0].getAdditionalName().toString();											
										logger.trace("Referral is \"" + server +"\" but no ADDITIONNAL section provided, starting resolution.. ");

										// Gotta find the authoritative
										// server's IP
										Record authorityRecord = response.getSectionArray(Section.AUTHORITY)[0];
										Record authorityIpRecordQuery = Record.newRecord(authorityRecord.getAdditionalName(), Type.A,
												authorityRecord.getDClass());
										byte[] requestNsBytesArray = Message.newQuery(authorityIpRecordQuery).toWire(
												UdpServerRunnable.DNS_UDP_MAXLENGTH);
										
										vertx.eventBus().send(ResolutionVerticle.BUS_ADDRESS, requestNsBytesArray,
												new Handler<org.vertx.java.core.eventbus.Message<byte[]>>() {
													public void handle(org.vertx.java.core.eventbus.Message<byte[]> m) {
														try {
															Message mmm = new Message(m.body());
															Record[] records = mmm.getSectionArray(Section.ANSWER);
															logger.trace("Resolved referral " + authorityRecord.getAdditionalName()
																	+ " to IP " + records[0].rdataToString());
															s.recursionCtx.currentNS = Address.getByAddress(records[0].rdataToString());
															foobar(s);
														} catch (Exception e) {
															e.printStackTrace();
														}
													}

												});
									}
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
				});
			}
		} catch (Exception e) {
			s.returnCode = Rcode.SERVFAIL;
			s.answerRS = null;
			generateResponse(s);
			logger.error("Oups", e);
		}

		if (s.responseReady) {
			logger.trace("Replying to message with hash=" + s.vertxBusMessage.hashCode());
			s.vertxBusMessage.reply(s.response);
		}
	}

	private void generateResponse(ProcessingContext s) {
		logger.trace("Preparing response..");

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
					logger.trace(">> " + r.toString());
				}
			}
		}
		s.response = msg.toWire(UdpServerRunnable.DNS_UDP_MAXLENGTH);
		s.responseReady = true;
		logger.trace("Ready to be transmitted");
	}

	private void foobar(ProcessingContext s) throws Exception {
		if (s.recursionCtx.infiniteLoopProtection == MAX_RECURSION_ALLOWED) {
			throw new Exception("Maximum recursion limit reached");
		} else {
			s.recursionCtx.infiniteLoopProtection++;
		}

		logger.trace("Sending question to remote nameserver " + s.recursionCtx.currentNS.getHostAddress() + ": \""
				+ s.recursionCtx.queryMessage.getQuestion() + "\"");

		Buffer buffer = new Buffer(s.recursionCtx.queryMessage.toWire(UdpServerRunnable.DNS_UDP_MAXLENGTH));
		s.recursionCtx.socket.send(buffer, s.recursionCtx.currentNS.getHostAddress(), 53, onSentToRemoteServer);

	}
}
