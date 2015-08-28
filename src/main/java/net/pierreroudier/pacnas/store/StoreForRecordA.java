package net.pierreroudier.pacnas.store;

import java.util.List;

import org.xbill.DNS.ARecord;

/**
 * A Store specialized for A resource-record
 * 
 *
 */
public interface StoreForRecordA {

	/**
	 * 
	 * @param queryName
	 * @return Null if no matching records are found in the store
	 */
	public ARecord[] getRecords(String queryName);

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
