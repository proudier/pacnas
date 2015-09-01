package net.pierreroudier.pacnas.store;

import java.util.*;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;

/**
 * Store in a Java Collection inside JVM heap space
 */

public class InMemoryJavaHashmapStore implements Store {
	private Map<String, StoreEntry> entries;

	public InMemoryJavaHashmapStore() {
		entries = new HashMap<String, StoreEntry>();
	}

	@Override
	public Store getRecords(String queryName, int queryType, Handler<AsyncResult<List<Record>>> handler) {
		StoreEntry storeEntry = entries.get(composeKey(queryName, queryType));
		handler.handle(new AsyncResult<List<Record>>() {
			@Override
			public List<Record> result() {
				if (storeEntry != null) {
					return Arrays.asList(storeEntry.records);
				} else {
					return null;
				}
			}

			@Override
			public Throwable cause() {
				return null;
			}

			@Override
			public boolean succeeded() {
				return true;
			}

			@Override
			public boolean failed() {
				return false;
			}
		});

		return this;
	}

	@Override
	public void putRecords(String queryName, int queryType, Record[] records) {
		String key = composeKey(queryName, queryType);
		StoreEntry entry = new StoreEntry(queryName, queryType, records);
		entries.put(key, entry);
	}

	@Override
	public List<String> getContentDump() {
		List<String> output = new ArrayList<String>();
		StringBuilder sb = new StringBuilder(100);
		for (StoreEntry e : entries.values()) {
			sb.append(e.queryName);
			sb.append(" | ");
			sb.append(Type.string(e.queryType));
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

	public void discardContent() {
		entries.clear();
	}

	protected String composeKey(String queryName, int queryType) {
		return (Type.string(queryType) + queryName);
	}

	protected class StoreEntry {
		String queryName;
		int queryType;
		Record[] records;

		public StoreEntry(String queryName, int queryType, Record[] records) {
			this.queryName = queryName;
			this.queryType = queryType;
			this.records = records;
		}
	}

}
