package net.pierreroudier.pacnas;

import org.xbill.DNS.Message;
import org.xbill.DNS.Record;

public class ProcessingContext {
	public static final int RETURN_CODE_INVALID_VALUE = -1;

	public org.vertx.java.core.eventbus.Message<byte[]> vertxBusMessage;

	public Message queryMessage = null;
	public Record queryRecord = null;

	public int returnCode = RETURN_CODE_INVALID_VALUE;
	public Record[] answerRS = null;

	public byte[] response = null;
	public boolean responseReady = false;

	public boolean saveAnswersToStore = false;

	public RecursionContext recursionCtx = null;

}
