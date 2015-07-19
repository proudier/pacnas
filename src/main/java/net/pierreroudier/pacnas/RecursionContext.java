package net.pierreroudier.pacnas;

import io.vertx.core.datagram.DatagramSocket;

import java.util.List;
import java.util.Vector;

import org.xbill.DNS.Message;

public class RecursionContext {
	List<String> currentNS = new Vector<String>();
	int infiniteLoopProtection;
	Message queryMessage;
	DatagramSocket socket;
}
