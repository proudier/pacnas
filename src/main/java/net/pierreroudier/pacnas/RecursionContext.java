package net.pierreroudier.pacnas;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.vertx.java.core.datagram.DatagramSocket;
import org.xbill.DNS.Address;
import org.xbill.DNS.Message;

public class RecursionContext {
	static InetAddress ROOT_NS;

	static {
		try {
			ROOT_NS = Address.getByAddress("202.12.27.33"); // Root
			//ROOT_NS = Address.getByAddress("192.5.4.2"); // .fr
			//ROOT_NS = Address.getByAddress("185.26.230.9"); // lemonde.fr
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
	}

	InetAddress currentNS;
	int infiniteLoopProtection;
	Message queryMessage;
	DatagramSocket socket;
}
