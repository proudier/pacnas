package net.pierreroudier.pacnas.store;

import java.util.List;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import org.xbill.DNS.Record;

public interface Store {

	/**
	 * Look in the Store for Records matching the given name and type. If no such entries are found, the handler
	 * will be provided a null result.
	 */
	public Store getRecords(String name, int type, Handler<AsyncResult<Record[]>> handler);
	
	/**
	 * 
	 * @param records All Records in the given array must have the same Name, Type and TTL
	 */
	public Store putRecords(Record[] records, Handler<AsyncResult<String>> handler);
	
	/**
	 * Drop all the records from the store.
	 */
	public Store discardContent(Handler<AsyncResult<String>> handler);
	
	/**
	 * Return the number of element in the Store
	 */
	public Store countItems(Handler<AsyncResult<Long>> handler);
	
	public List<String> getContentDump();
	
}
