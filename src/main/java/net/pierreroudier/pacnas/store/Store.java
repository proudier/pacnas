package net.pierreroudier.pacnas.store;

import java.util.List;

import org.xbill.DNS.Record;

public interface Store {

	/**
	 * 
	 * @param queryName
	 * @param queryType
	 * @param queryClass
	 * @return null if not found
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
	 * 
	 * @return
	 */
	public List<String> getContentDump();
	
}
