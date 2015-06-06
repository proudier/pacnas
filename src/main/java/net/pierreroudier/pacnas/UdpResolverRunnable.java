package net.pierreroudier.pacnas;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Vector;

import net.pierreroudier.pacnas.dns.DnsException;
import net.pierreroudier.pacnas.store.Store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.Address;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Opcode;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

public class UdpResolverRunnable implements Runnable {
	private static int SOCKET_TIMEOUT_AFTER_MS = 10000;
	private Logger logger = LoggerFactory.getLogger(UdpResolverRunnable.class);
	private Store store;
	private StatsManager statsManager;
	private InetAddress targetInetAddress;
	private int targetPort;
	private DatagramSocket socket;
	private byte[] inputBuffer;

	public UdpResolverRunnable(Store store, StatsManager statsManager, InetAddress targetInetAddress,
			int targetPort, DatagramSocket socket, byte[] dataPacketBuffer) {
		this.store = store;
		this.statsManager = statsManager;
		this.targetInetAddress = targetInetAddress;
		this.targetPort = targetPort;
		this.socket = socket;
		inputBuffer = new byte[UdpServerRunnable.DNS_UDP_MAXLENGTH];
		System.arraycopy(dataPacketBuffer, 0, inputBuffer, 0, dataPacketBuffer.length);
	}

	@Override
	public void run() {
		try {
			Message queryMessage = null;
			Record queryRecord = null;
			Record[] answerRS = null;
			byte[] response = null;
			boolean saveAnswersToStore = false;

			try {
				queryMessage = new Message(inputBuffer);
				queryRecord = queryMessage.getQuestion();
				if (queryMessage.getHeader().getFlag(Flags.QR)
						|| queryMessage.getHeader().getRcode() != Rcode.NOERROR) {
					new DnsException(Rcode.FORMERR);
				}
				if (queryMessage.getHeader().getOpcode() != Opcode.QUERY) {
					new DnsException(Rcode.NOTIMP);
				}
				if (logger.isTraceEnabled())
					logger.trace("Request is: \"" + queryRecord.toString() + "\"");

				/*
				 * String queryName = queryRecord.getName().toString(); int
				 * queryType = queryRecord.getType(); int queryClass =
				 * queryRecord.getDClass();
				 * 
				 * Record[] answerRS = null; answerRS =
				 * store.getRecords(queryName, queryType, queryClass); if
				 * (answerRS != null) { logger.trace("Answering from store");
				 * statsManager.increaseQueryAnsweredFromCache(); } else {
				 * logger.trace("Answering from forwarder");
				 * statsManager.increaseQueryAnsweredFromForwarder(); answerRS =
				 * new Lookup(queryName, queryType, queryClass).run(); if
				 * (answerRS != null) { store.putRecords(queryName, queryType,
				 * queryClass, answerRS); } else {
				 * logger.trace("Got nothing from Forwarder"); } }
				 */

				// List<Record> answerRS = resolve(queryRecord);
				answerRS = store.getRecords(queryRecord.getName().toString(), queryRecord.getType(),
						queryRecord.getDClass());
				if (answerRS != null) {
					statsManager.increaseQueryAnsweredFromCache();
				} else {
					answerRS = recurse(queryRecord);
					statsManager.increaseQueryAnsweredByResolution();
					saveAnswersToStore = true;
				}

				// Response
				Message responseMessage = new Message(queryMessage.getHeader().getID());
				responseMessage.getHeader().setFlag(Flags.RA);
				responseMessage.getHeader().setFlag(Flags.QR);
				if (queryMessage.getHeader().getFlag(Flags.RD)) {
					responseMessage.getHeader().setFlag(Flags.RD);
				}
				responseMessage.getHeader().setRcode(Rcode.NOERROR);
				responseMessage.addRecord(queryRecord, Section.QUESTION);
				if (answerRS != null && answerRS.length > 0) {
					for (Record r : answerRS) {
						if (r != null) {
							responseMessage.addRecord(r, Section.ANSWER);
							logger.trace(">> " + r.toString());
						}
					}
				}
				response = responseMessage.toWire(UdpServerRunnable.DNS_UDP_MAXLENGTH);
			} catch (DnsException dnsE) {
				response = generateErrorResponse(queryMessage, queryRecord, dnsE.getReturnCode());
			} catch (SocketTimeoutException e) {
				logger.info("Socket timeout");
				response = generateErrorResponse(queryMessage, queryRecord, Rcode.NOERROR);
			} catch (Exception e) {
				response = generateErrorResponse(queryMessage, queryRecord, Rcode.SERVFAIL);
				logger.error("Oups", e);
			}

			DatagramPacket outdp = new DatagramPacket(response, response.length, targetInetAddress,
					targetPort);
			// outdp.setData(response);
			// outdp.setLength(response.length);
			// outdp.setAddress(targetInetAddress);
			// outdp.setPort(targetPort);
			socket.send(outdp);
			logger.trace("Answer has been sent");

			if (saveAnswersToStore) {
				logger.trace("Saving to store");
				store.putRecords(queryRecord.getName().toString(), queryRecord.getType(),
						queryRecord.getDClass(), answerRS);
			}
		} catch (Throwable e) {
			logger.error("Oups", e);
		}
	}

	private byte[] generateErrorResponse(Message queryMessage, Record queryRecord, int returnCode) {
		logger.trace("preparing error response");

		Message msg = new Message();
		msg.getHeader().setRcode(returnCode);
		msg.getHeader().setFlag(Flags.RA);
		msg.getHeader().setFlag(Flags.QR);
		if (queryMessage != null) {
			msg.getHeader().setID(queryMessage.getHeader().getID());
			if (queryMessage.getHeader().getFlag(Flags.RD)) {
				msg.getHeader().setFlag(Flags.RD);
			}
		}
		if (queryRecord != null)
			msg.addRecord(queryRecord, Section.QUESTION);
		return msg.toWire(UdpServerRunnable.DNS_UDP_MAXLENGTH);
	}

	private Message sendMessageToNameserver(Message query, InetAddress nameServer, int port) throws Exception {
		// TODO this method should take a list of Address instead of just one
		// and failover through the list
		DatagramSocket socket = null;
		try {
			socket = new DatagramSocket();
			socket.setSoTimeout(SOCKET_TIMEOUT_AFTER_MS);

			byte[] wireData = query.toWire(UdpServerRunnable.DNS_UDP_MAXLENGTH);
			DatagramPacket outPacket = new DatagramPacket(wireData, wireData.length, nameServer, port);
			if (logger.isTraceEnabled()) {
				logger.trace("Sending question to remote nameserver " + nameServer.getHostAddress() + ": \""
						+ query.getQuestion() + "\"");
			}
			socket.send(outPacket);

			byte[] inBuffer = new byte[UdpServerRunnable.DNS_UDP_MAXLENGTH];
			DatagramPacket inPacket = new DatagramPacket(inBuffer, inBuffer.length);
			socket.receive(inPacket);

			Message response = new Message(inBuffer);
			if (query.getHeader().getID() != response.getHeader().getID())
				throw new Exception("ID mismatch, remote server is broken");
			if (response.getRcode() != response.getHeader().getRcode()) {
				throw new Exception("response.getRcode() != response.getHeader().getRcode()");
			}
			if (logger.isTraceEnabled()) {
				logger.trace("Received  " + response.getSectionArray(Section.QUESTION).length + " question, "
						+ response.getSectionArray(Section.ANSWER).length + " answer, "
						+ response.getSectionArray(Section.AUTHORITY).length + " authority, "
						+ response.getSectionArray(Section.ADDITIONAL).length + " additional. Rcode="
						+ Rcode.string(response.getRcode()));
			}
			return response;
		} finally {
			if (socket != null && !socket.isClosed())
				socket.close();
		}
	}

	/**
	 * 
	 * @param query
	 * @return The records if any; null if no records available from authority.
	 * @throws Exception
	 */
	private Record[] recurse(Record query) throws Exception {
		InetAddress ROOT_NS = Address.getByAddress("202.12.27.33");

		InetAddress currentNS = ROOT_NS;
		Record queryRecord = null;
		Message queryMessage = null;
		Message responseMessage = null;
		int infiniteLoopProtection = 0;

		queryRecord = Record.newRecord(query.getName(), query.getType(), query.getDClass());
		queryMessage = Message.newQuery(queryRecord);
		queryMessage.getHeader().unsetFlag(Flags.RD);

		while (true) {
			responseMessage = sendMessageToNameserver(queryMessage, currentNS, 53);
			int returnCode = responseMessage.getHeader().getRcode();
			switch (returnCode) {
			case Rcode.FORMERR:
				throw new Exception("Remote server reported a FormatError on pacnac's query");

			case Rcode.SERVFAIL:
			case Rcode.REFUSED:
			case Rcode.NOTIMP:
			case Rcode.NOTAUTH:
				// TODO try on another server instead of giving up
				throw new Exception("Remote server encountered an error: " + Rcode.string(returnCode));

			case Rcode.NXDOMAIN:
				throw new DnsException(Rcode.NXDOMAIN);
			case Rcode.NOERROR:
				if (responseMessage.getHeader().getFlag(Flags.TC)) {
					logger.warn("Truncated bit is set in response but pacnas does not handle it properly (yet)");
				}

				if (responseMessage.getHeader().getFlag(Flags.AA)) {
					// Authoritative answer
					if (responseMessage.getSectionArray(Section.ANSWER).length > 0) {
						logger.trace("Got authoritative answer with ANSWER records");
					} else {
						logger.trace("Got empty authoritative answer");
					}
					return responseMessage.getSectionArray(Section.ANSWER);
				} else {
					// Non Authoritative answer
					if (responseMessage.getSectionArray(Section.ANSWER).length == 0
							&& responseMessage.getSectionArray(Section.AUTHORITY).length > 0) {
						logger.trace("Response is a redirection to referral");
						if (responseMessage.getSectionArray(Section.ADDITIONAL).length > 0) {
							for (Record authorityRecord : responseMessage.getSectionArray(Section.AUTHORITY)) {
								if (authorityRecord.getType() != Type.NS) {
									logger.warn("ignored authority record because type!=NS");
									continue;
								}

								String authorityName = authorityRecord.rdataToString();
								logger.trace("Looking for the following referral's IP in ADDITIONAL section: \""
										+ authorityName + "\"");

								List<String> authNsIp = new ArrayList<String>();
								for (Record additionnalRecord : responseMessage
										.getSectionArray(Section.ADDITIONAL)) {
									if (additionnalRecord.getName().toString().equals(authorityName)) {
										String s = additionnalRecord.rdataToString();
										logger.trace("Found referral's IP: \"" + s + "\"");
										if (s.indexOf(':') == -1) {
											// ipv4
											authNsIp.add(s);
										} else {
											// ipv6
											logger.trace("IPv6 address discarded");
										}
									}
								}
								if (authNsIp.size() == 0) {
									logger.trace("Referral IP was not found in ADDITIONNAL section");
								} else {
									currentNS = Address.getByAddress(authNsIp.get(0));
								}
								break;
							}
						} else {
							Record authorityRecord = responseMessage.getSectionArray(Section.AUTHORITY)[0];
							// trouver l'IP du serveur authority
							Record authorityIpRecordQuery = Record.newRecord(
									authorityRecord.getAdditionalName(), Type.A, authorityRecord.getDClass());
							Record[] records = recurse(authorityIpRecordQuery);
							if (records != null) {
								if (logger.isTraceEnabled()) {
									logger.trace("Resolved referral " + authorityRecord.getAdditionalName()
											+ " to IP " + records[0].rdataToString());
								}
								currentNS = Address.getByAddress(records[0].rdataToString());
							} else {
								throw new Exception("we\'re not supposed to be here");
							}
						}
					} else {
						throw new Exception("we\'re not supposed to be here");
					}
				}
				break;

			default:
				throw new Exception("Pacnas does not know what to do (yet) out of this return code: "
						+ Rcode.string(returnCode));
			}

			infiniteLoopProtection++;
			if (infiniteLoopProtection > 20) {
				return null;
			}
		}
	}

	// hyp: Flags.RD always on in query
	private List<Record> resolve(Record inboundQueryRecord) throws Exception {
		List<Record> recordList = null;

		// Do we have it in store?
		recordList = null; // TODO store.getRecords(inboundQueryRecord);
		if (recordList != null) {
			logger.trace("answering from store");
		} else {
			// what is the authority server for this zone
			InetAddress authoritativeNameServer = authoritativeNameserverForRequest(inboundQueryRecord);

			// prepare query message for this server
			Message outMessage = Message.newQuery(inboundQueryRecord);
			outMessage.getHeader().unsetFlag(Flags.RD);

			// send and process answer
			Message inMessage = sendMessageToNameserver(outMessage, authoritativeNameServer, 53);
			Record[] records = inMessage.getSectionArray(Section.ANSWER);

			recordList = new Vector<Record>(Arrays.asList(records));
			logger.trace("answering from remote nameserver");
		}

		return recordList;
	}

	private InetAddress authoritativeNameserverForRequest(Record queryRecord) throws UnknownHostException {
		// return Address.getByAddress("202.12.27.33"); // ROOT
		// return Address.getByAddress("194.0.36.1");
		return Address.getByAddress("212.27.60.19"); // NS FREE.Fr
	}

	private Message composeQueryForNameserverForName(Name name) {
		Record queryRecord = Record.newRecord(name, Type.NS, DClass.IN);
		Message queryMessage = Message.newQuery(queryRecord);
		queryMessage.getHeader().unsetFlag(Flags.RD);
		return queryMessage;
	}

}
