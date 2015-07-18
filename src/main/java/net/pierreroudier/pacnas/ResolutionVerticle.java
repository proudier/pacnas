package net.pierreroudier.pacnas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Vector;

import net.pierreroudier.pacnas.store.InMemoryJavaHashmapStore;
import net.pierreroudier.pacnas.store.Store;

import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Handler;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.datagram.DatagramSocket;
import org.vertx.java.core.datagram.InternetProtocolFamily;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.platform.Verticle;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
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
		logger.info("Starting ResolutionVerticle..");

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
		logger.trace("Resolution request received via Vertx bus (msg hash=" + vertxBusMessage.hashCode() + ")");
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
				generateResponse(s, Rcode.FORMERR);
			}
			if (s.queryMessage.getHeader().getOpcode() != Opcode.QUERY) {
				logger.info("Handling of the following opcode is not implemented: " + s.queryMessage.getHeader().getOpcode());
				generateResponse(s, Rcode.NOTIMP);
			}
			if (s.queryRecord.getDClass() != DClass.IN) {
				logger.info("Handling of the following class is not implemented: " + s.queryRecord.getDClass());
				generateResponse(s, Rcode.NOTIMP);
			}

			// Query cache
			s.answerRS = store.getRecords(s.queryRecord.getName().toString(), s.queryRecord.getType(), s.queryRecord.getDClass());
			if (s.answerRS != null) {
				logger.trace("Answering from cache");
				statsManager.increaseQueryAnsweredFromCache();
				generateResponse(s, Rcode.NOERROR);
			} else {
				logger.trace("Not found in cache, starting recursive resolution..");
				statsManager.increaseQueryAnsweredByResolution();
				s.saveAnswersToStore = true;
				s.recursionCtx = new RecursionContext();

				searchCacheForBestNameserver(s);

				s.recursionCtx.socket = vertx.createDatagramSocket(InternetProtocolFamily.IPv4);
				Record queryRecord = Record.newRecord(s.queryRecord.getName(), s.queryRecord.getType(), s.queryRecord.getDClass());
				s.recursionCtx.queryMessage = Message.newQuery(queryRecord);
				s.recursionCtx.queryMessage.getHeader().unsetFlag(Flags.RD);

				foobar(s);

				s.recursionCtx.socket.dataHandler(dataPacket -> {
					try {
						Message response = new Message(dataPacket.data().getBytes());

						// Basic error check
						if (s.recursionCtx.queryMessage.getHeader().getID() != response.getHeader().getID()) {
							logger.info("ID mismatch, remote server is broken");
							generateResponse(s, Rcode.NOERROR);
						}
						if (response.getRcode() != response.getHeader().getRcode()) {
							logger.info("response.getRcode() != response.getHeader().getRcode()");
							generateResponse(s, Rcode.NOERROR);
						}

						logger.trace("Received " + response.getSectionArray(Section.QUESTION).length + " question, "
								+ response.getSectionArray(Section.ANSWER).length + " answer, "
								+ response.getSectionArray(Section.AUTHORITY).length + " authority, "
								+ response.getSectionArray(Section.ADDITIONAL).length + " additional. Rcode="
								+ Rcode.string(response.getRcode()));

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
								// Authoritative answer
								if (response.getSectionArray(Section.ANSWER).length > 0) {
									logger.trace("Got authoritative answer with ANSWER records");
								} else {
									logger.trace("Got empty authoritative answer");
								}
								s.answerRS = response.getSectionArray(Section.ANSWER);
								generateResponse(s, Rcode.NOERROR);
								if (s.saveAnswersToStore) {
									logger.trace("Saving to store");
									store.putRecords(s.queryRecord.getName().toString(), s.queryRecord.getType(),
											s.queryRecord.getDClass(), s.answerRS);
								}
							} else {
								// Non Authoritative answer
						if (response.getSectionArray(Section.ANSWER).length == 0 && response.getSectionArray(Section.AUTHORITY).length > 0) {
							logger.trace("Response is a redirection to referral");

							if (response.getSectionArray(Section.ADDITIONAL).length > 0) {
								for (Record authorityRecord : response.getSectionArray(Section.AUTHORITY)) {
									if (authorityRecord.getType() != Type.NS) {
										logger.warn("ignored authority record because type!=NS");
										continue;
									}

									String authorityName = authorityRecord.rdataToString();
									logger.trace("Looking for the following referral's IP in ADDITIONAL section: \"" + authorityName + "\"");

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
										s.recursionCtx.currentNS = authNsIp;
										// TODO handle multiplicity in logger.trace bellow
										logger.trace("Now using remove nameserver \"" + authorityName + "\" ("
												+ s.recursionCtx.currentNS.get(0) + ")");
										foobar(s);
									}
									break;
								}
							} else {
								String server = response.getSectionArray(Section.AUTHORITY)[0].getAdditionalName().toString();
								logger.trace("Referral is \"" + server + "\" but no ADDITIONNAL section provided, starting resolution.. ");

								// Gotta find the authoritative server's IP
								Record authorityRecord = response.getSectionArray(Section.AUTHORITY)[0];
								Record authorityIpRecordQuery = Record.newRecord(authorityRecord.getAdditionalName(), Type.A,
										authorityRecord.getDClass());
								byte[] requestNsBytesArray = Message.newQuery(authorityIpRecordQuery).toWire(
										UdpListenerVerticle.DNS_UDP_MAXLENGTH);

								vertx.eventBus().send(ResolutionVerticle.BUS_ADDRESS, requestNsBytesArray,
										new Handler<org.vertx.java.core.eventbus.Message<byte[]>>() {
											public void handle(org.vertx.java.core.eventbus.Message<byte[]> m) {
												try {
													Message mmm = new Message(m.body());
													Record[] records = mmm.getSectionArray(Section.ANSWER);
													if (records.length > 0) {
														// TODO handle multiplicity in logger.trace bellow
														logger.trace("Resolved referral " + authorityRecord.getAdditionalName() + " to IP "
																+ records[0].rdataToString());
														s.recursionCtx.currentNS.clear();
														for(Record r:records) {													
															s.recursionCtx.currentNS.add(r.rdataToString());
														}
														Collections.shuffle(s.recursionCtx.currentNS);
														foobar(s);
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
											}

										});
							}
						} else {
							logger.error("we\'re not supposed to be here");
						}
					}
							break;

						default:
							throw new Exception("Pacnas does not know what to do (yet) out of this return code: "
									+ Rcode.string(response.getRcode()));
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
				});
			}

		} catch (org.xbill.DNS.WireParseException e) {
			s.answerRS = null;
			generateResponse(s, Rcode.FORMERR);
		} catch (Exception e) {
			logger.error("Oups", e);
			s.answerRS = null;
			generateResponse(s, Rcode.SERVFAIL);
		}

		if (s.responseReady) {
			logger.trace("Replying to message with hash=" + s.vertxBusMessage.hashCode());
			s.vertxBusMessage.reply(s.response);
		}
	}

	private void generateResponse(ProcessingContext s, int returnCodeToSet) {
		if (s.responseReady == true) {
			logger.fatal("Attempted to overwrite a response, but nothing was done. This is probably due to a faulty logic in the code.");
		} else {
			logger.trace("Preparing response..");

			if (s.returnCode != ProcessingContext.RETURN_CODE_INVALID_VALUE) {
				logger.fatal("Return code was already set. It has not been changed but this is probably due to a faulty logic in the code.");
			} else {
				s.returnCode = returnCodeToSet;
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
			s.response = msg.toWire(UdpListenerVerticle.DNS_UDP_MAXLENGTH);
			s.responseReady = true;
			logger.trace("Ready to be transmitted");
		}
	}

	private void foobar(ProcessingContext s) throws Exception {
		if (s.recursionCtx.infiniteLoopProtection == MAX_RECURSION_ALLOWED) {
			throw new Exception("Maximum recursion limit reached");
		} else {
			s.recursionCtx.infiniteLoopProtection++;
		}

		logger.trace("Sending question to remote nameserver " + s.recursionCtx.currentNS.get(0) + ": \""
				+ s.recursionCtx.queryMessage.getQuestion() + "\"");

		Buffer buffer = new Buffer(s.recursionCtx.queryMessage.toWire(UdpListenerVerticle.DNS_UDP_MAXLENGTH));
		s.recursionCtx.socket.send(buffer, s.recursionCtx.currentNS.get(0), 53, onSentToRemoteServer);

	}

	/**
	 * Look up in the cache if we already know some NS best suited to respond to
	 * this query
	 * 
	 * @param s
	 *            ProcessingContext
	 * @throws Exception
	 */
	private void searchCacheForBestNameserver(ProcessingContext s) throws Exception {
		// Find matching NS RR
		List<String> nameServerList = new Vector<String>();
		Name queryRecordName = s.queryRecord.getName();
		for (int i = 0; i < queryRecordName.labels(); i++) {
			StringBuilder sb = new StringBuilder();
			for (int j = i; j < queryRecordName.labels(); j++) {
				sb.append(queryRecordName.getLabelString(j));
				sb.append(".");
			}
			String domainName = sb.toString();
			Record[] records = store.getRecords(domainName, Type.NS, s.queryRecord.getDClass());
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
				Record[] records = store.getRecords(serverName, Type.A, s.queryRecord.getDClass());
				for (Record r : records) {
					s.recursionCtx.currentNS.add(r.rdataToString());
				}
			}
		} else {
			// If no matching NS were found above, use root-servers
			s.recursionCtx.currentNS.add("192.36.148.17");
			s.recursionCtx.currentNS.add("192.58.128.30");
			s.recursionCtx.currentNS.add("199.7.83.42");
			s.recursionCtx.currentNS.add("192.33.4.12");
			s.recursionCtx.currentNS.add("202.12.27.33");
		}
		
		// Shuffle so as to even the load
		 Collections.shuffle(s.recursionCtx.currentNS);
	}
}
