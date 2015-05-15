package net.pierreroudier.pacnas.dns;


public class Recursor {
//
//	private static int SOCKET_TIMEOUT_AFTER_MS = 10000;
//
//	private Vertx vertx;
//	private DatagramSocket socket;
//	private Logger logger;
//	private InetAddress currentNS;
//	private Record queryRecord;
//	private Message queryMessage;
//	private Message responseMessage;
//	private int infiniteLoopProtection;
//
//	public Recursor(Logger logger, Vertx vertx) throws UnknownHostException {
//		this.vertx = vertx;
//		this.socket = vertx.createDatagramSocket(InternetProtocolFamily.IPv4);
//		this.logger = logger;
//		InetAddress ROOT_NS = Address.getByAddress("202.12.27.33");
//
//		currentNS = ROOT_NS;
//		queryRecord = null;
//		queryMessage = null;
//		responseMessage = null;
//		infiniteLoopProtection = 0;
//	}
//
//	/**
//	 * 
//	 * @param query
//	 * @return The records if any; null if no records available from authority.
//	 * @throws Exception
//	 */
//	public Record[] recurse(ProcessingStruct s) throws Exception {
//
//		queryRecord = Record.newRecord(s.queryRecord.getName(), s.queryRecord.getType(), s.queryRecord.getDClass());
//		queryMessage = Message.newQuery(queryRecord);
//		queryMessage.getHeader().unsetFlag(Flags.RD);
//
//		while (true) {
//			logger.info("Sending question to remote nameserver " + currentNS.getHostAddress() + ": \""
//					+ queryMessage.getQuestion() + "\"");
//
//			Buffer buffer = new Buffer(queryRecord.toWire(UdpServerRunnable.DNS_UDP_MAXLENGTH));
//			socket.send(buffer, currentNS.getHostAddress(), 53, new OnRequestSent());
//			socket.dataHandler(new OnIncomingData());
//
//			// ONLY DEATH BELOW ---------------------
//
//			responseMessage = sendMessageToNameserver(queryMessage, currentNS, 53);
//			int returnCode = responseMessage.getHeader().getRcode();
//			switch (returnCode) {
//			case Rcode.FORMERR:
//				throw new Exception("Remote server reported a FormatError on pacnac's query");
//
//			case Rcode.SERVFAIL:
//			case Rcode.REFUSED:
//			case Rcode.NOTIMP:
//			case Rcode.NOTAUTH:
//				// TODO try on another server instead of giving up
//				throw new Exception("Remote server encountered an error: " + Rcode.string(returnCode));
//
//			case Rcode.NXDOMAIN:
//				throw new DnsException(Rcode.NXDOMAIN);
//			case Rcode.NOERROR:
//				if (responseMessage.getHeader().getFlag(Flags.TC)) {
//					logger.warn("Truncated bit is set in response but pacnas does not handle it properly (yet)");
//				}
//
//				if (responseMessage.getHeader().getFlag(Flags.AA)) {
//					// Authoritative answer
//					if (responseMessage.getSectionArray(Section.ANSWER).length > 0) {
//						logger.trace("Got authoritative answer with ANSWER records");
//					} else {
//						logger.trace("Got empty authoritative answer");
//					}
//					return responseMessage.getSectionArray(Section.ANSWER);
//				} else {
//					// Non Authoritative answer
//					if (responseMessage.getSectionArray(Section.ANSWER).length == 0
//							&& responseMessage.getSectionArray(Section.AUTHORITY).length > 0) {
//						logger.trace("Response is a redirection to referral");
//						if (responseMessage.getSectionArray(Section.ADDITIONAL).length > 0) {
//							for (Record authorityRecord : responseMessage.getSectionArray(Section.AUTHORITY)) {
//								if (authorityRecord.getType() != Type.NS) {
//									logger.warn("ignored authority record because type!=NS");
//									continue;
//								}
//
//								String authorityName = authorityRecord.rdataToString();
//								logger.trace("Looking for the following referral's IP in ADDITIONAL section: \""
//										+ authorityName + "\"");
//
//								List<String> authNsIp = new ArrayList<String>();
//								for (Record additionnalRecord : responseMessage.getSectionArray(Section.ADDITIONAL)) {
//									if (additionnalRecord.getName().toString().equals(authorityName)) {
//										String s = additionnalRecord.rdataToString();
//										logger.trace("Found referral's IP: \"" + s + "\"");
//										if (s.indexOf(':') == -1) {
//											// ipv4
//											authNsIp.add(s);
//										} else {
//											// ipv6
//											logger.trace("IPv6 address discarded");
//										}
//									}
//								}
//								if (authNsIp.size() == 0) {
//									logger.trace("Referral IP was not found in ADDITIONNAL section");
//								} else {
//									currentNS = Address.getByAddress(authNsIp.get(0));
//								}
//								break;
//							}
//						} else {
//							Record authorityRecord = responseMessage.getSectionArray(Section.AUTHORITY)[0];
//							// trouver l'IP du serveur authority
//							Record authorityIpRecordQuery = Record.newRecord(authorityRecord.getAdditionalName(),
//									Type.A, authorityRecord.getDClass());
//							Record[] records = recurse(authorityIpRecordQuery);
//							if (records != null) {
//								if (logger.isTraceEnabled()) {
//									logger.trace("Resolved referral " + authorityRecord.getAdditionalName() + " to IP "
//											+ records[0].rdataToString());
//								}
//								currentNS = Address.getByAddress(records[0].rdataToString());
//							} else {
//								throw new Exception("we\'re not supposed to be here");
//							}
//						}
//					} else {
//						throw new Exception("we\'re not supposed to be here");
//					}
//				}
//				break;
//
//			default:
//				throw new Exception("Pacnas does not know what to do (yet) out of this return code: "
//						+ Rcode.string(returnCode));
//			}
//
//			infiniteLoopProtection++;
//			if (infiniteLoopProtection > 20) {
//				return null;
//			}
//		}
//	}
//
//	private class OnRequestSent implements AsyncResultHandler<DatagramSocket> {
//		@Override
//		public void handle(AsyncResult<DatagramSocket> asyncResult) {
//			if (asyncResult.failed()) {
//				logger.error("Error sending", asyncResult.cause());
//				return;
//			}
//		}
//	}
//
//	private class OnIncomingData implements Handler<DatagramPacket> {
//		@Override
//		public void handle(DatagramPacket dp) {
//			Message response = new Message(dp.data().getBytes());
//			if (query.getHeader().getID() != response.getHeader().getID())
//				throw new Exception("ID mismatch, remote server is broken");
//			if (response.getRcode() != response.getHeader().getRcode()) {
//				throw new Exception("response.getRcode() != response.getHeader().getRcode()");
//			}
//			if (logger.isTraceEnabled()) {
//				logger.trace("Received  " + response.getSectionArray(Section.QUESTION).length + " question, "
//						+ response.getSectionArray(Section.ANSWER).length + " answer, "
//						+ response.getSectionArray(Section.AUTHORITY).length + " authority, "
//						+ response.getSectionArray(Section.ADDITIONAL).length + " additional. Rcode="
//						+ Rcode.string(response.getRcode()));
//			}
//			return response;
//
//		}
//
//	}
}
