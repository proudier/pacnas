package net.pierreroudier.pacnas.store;

import java.util.List;

import org.xbill.DNS.Record;

public interface Store {

	/**
	 * 
	 * @param queryName
	 * @param queryType
	 * @param queryClass
	 * @return Null if no matching records are found in the store
	 */
	public Record[] getRecords(String queryName, int queryType, int queryClass);
	
	/**
	 * 
	 * @param queryName
	 * @param queryType
	 * @param queryClass
	 * @param records
	 */
	public void putRecords(String queryName, int queryType, int queryClass, Record[] records);
	
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
