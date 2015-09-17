package net.pierreroudier.pacnas.store;

import java.util.List;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import org.xbill.DNS.Record;

/**
 * A Store specialized for A resource-record
 */
public interface StoreForRecord {

	/**
	 * @see Store#getRecords(String, int, Handler)
	 */
	public StoreForRecord getRecords(String queryName,  Handler<AsyncResult<Record[]>> handler);

	/**
	 * 
	 * @param queryName
	 * @param queryType
	 * @param records
	 */
	public void putRecords(String queryName, long ttl, List<String> ipAddresses);

	/**
	 * Drop all the records from the store; mainly for testing purposes
	 */
	public void discardContent();

	/**
	 * 
	 * @return
	 */
	public List<String> getContentDump();

}
