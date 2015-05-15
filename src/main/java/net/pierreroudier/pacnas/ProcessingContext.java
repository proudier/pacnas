package net.pierreroudier.pacnas;

import java.net.InetSocketAddress;

import org.vertx.java.core.buffer.Buffer;
import org.xbill.DNS.Message;
import org.xbill.DNS.Record;

public class ProcessingContext {
	public static final int RETURN_CODE_INVALID_VALUE = -1;

	public InetSocketAddress requestSender;

	public Message queryMessage = null;
	public Record queryRecord = null;

	public int returnCode = RETURN_CODE_INVALID_VALUE;
	public Record[] answerRS = null;

	public Buffer responseBuffer = null;
	public boolean responseReady = false;

	public boolean saveAnswersToStore = false;
	
	public RecursionContext recursionCtx = null;

}
