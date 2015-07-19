package net.pierreroudier.pacnas.store;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;

/**
 * Store in a Java Collection inside JVM heap space
 */
public class InMemoryJavaHashmapStore implements Store {
	private Map<Integer, StoreEntry> entries;
	private Map<Integer, LocalDateTime> refreshDeadlines;

	public InMemoryJavaHashmapStore() {
		entries = new HashMap<Integer, StoreEntry>();
		refreshDeadlines = new HashMap<Integer, LocalDateTime>();

		// / inject data for testing
		try {
			String query = "free.fr.";
			int queryType = Type.A;
			int queryClass = DClass.IN;
			Record r1 = new ARecord(new Name(query), queryClass, 3600, InetAddress.getByName(query));
			Record[] records = new Record[1];
			//putRecords(query, queryType, queryClass, records);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public Record[] getRecords(String queryName, int queryType, int queryClass) {
		Integer queryHash = ComputeQueryHash(queryName, queryType, queryClass);
		StoreEntry entry = entries.get(queryHash);
		if (entry != null)
			return entry.records;
		else
			return null;
	}

	@Override
	public void putRecords(String queryName, int queryType, int queryClass, Record[] records) {
		int queryHash = ComputeQueryHash(queryName, queryType, queryClass);
		StoreEntry entry = new StoreEntry(queryName, queryType, queryClass, records);
		entries.put(queryHash, entry);

		// LocalDateTime deadline = LocalDateTime.now().plusSeconds(r.getTTL());
		// refreshDeadline.put(queryHash, deadline);
	}

	@Override
	public List<String> getContentDump() {
		List<String> output = new ArrayList<String>();
		StringBuilder sb = new StringBuilder(100);
		for (StoreEntry e : entries.values()) {
			sb.append(e.queryName);
			sb.append(" | ");
			sb.append(Type.string(e.queryType));
			sb.append(" | ");
			sb.append(DClass.string(e.queryClass));
			sb.append("\n");
			output.add(sb.toString());
			sb.setLength(0);

			for (Record r : e.records) {
				sb.append(">> ");
				sb.append(r.toString());
				output.add(sb.toString());
				sb.setLength(0);
			}
		}
		return output;
	}

	protected int ComputeQueryHash(String queryName, int queryType, int queryClass) {
		StringBuilder sb = new StringBuilder();
		sb.append(queryName);
		sb.append('|');
		sb.append(queryType);
		sb.append('|');
		sb.append(queryClass);
		return sb.toString().hashCode();
	}

	protected class StoreEntry {
		String queryName;
		int queryType;
		int queryClass;
		Record[] records;

		public StoreEntry(String queryName, int queryType, int queryClass, Record[] records) {
			this.queryName = queryName;
			this.queryType = queryType;
			this.queryClass = queryClass;
			this.records = records;
		}
	}

}
