package net.pierreroudier.pacnas.store;

import java.util.List;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import org.xbill.DNS.Record;

public interface Store {

	/**
	 * Look in the Store for Record matching the given queryName and queryType. If no such entries are found, the handler
	 * will be provided a null result.
	 * @param queryName
	 * @param queryType
	 * @param handler if succeeded and result==null means they are no such entries in the store
	 */
	public Store getRecords(String queryName, int queryType, Handler<AsyncResult<Record[]>> handler);
	
	/**
	 * 
	 * @param records Records should have the same Name, Type and TTL
	 */
	public Store putRecords(Record[] records, Handler<AsyncResult<String>> handler);
	
	/**
	 * Drop all the records from the store; mainly for testing purposes
	 */
	public Store discardContent(Handler<AsyncResult<String>> handler);
	
	/**
	 * Return the number of element in the Store
	 * @param handler
	 * @return
	 */
	public Store countItems(Handler<AsyncResult<Long>> handler);
	
	/**
	 * 
	 * @return
	 */
	public List<String> getContentDump();
	
}
