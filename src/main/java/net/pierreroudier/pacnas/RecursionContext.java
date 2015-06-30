package net.pierreroudier.pacnas;

import java.util.List;
import java.util.Vector;

import org.vertx.java.core.datagram.DatagramSocket;
import org.xbill.DNS.Message;

public class RecursionContext {
	List<String> currentNS = new Vector<String>();
	int infiniteLoopProtection;
	Message queryMessage;
	DatagramSocket socket;
}
