package net.pierreroudier.pacnas.store;

import io.vertx.core.Vertx;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;

public class RedisStore implements Store {
	private final Logger logger = LoggerFactory.getLogger(RedisStore.class);
	private StoreForRecordA storeA;

	public RedisStore(Vertx vertx) {
		storeA = new RedisStoreForRecordA(vertx);
	}

	@Override
	public Record[] getRecords(String queryName, int queryType) {
		switch (queryType) {
		case Type.A:
		return storeA.getRecords(queryName);

		default:
			return null;
		}
	}

	@Override
	public void putRecords(String queryName, int queryType, Record[] records) {
		switch (queryType) {
		case Type.A:
			// TODO check that all records have the same queryName, queryType and TTL
			List<String> ipAddresses = new ArrayList<String>();
			for (Record record : records) {
				ARecord ar = (ARecord) record;
				ipAddresses.add(ar.getAddress().getHostAddress());
			}
			storeA.putRecords(queryName, records[0].getTTL(), ipAddresses);
			break;

		default:
			logger.info("Not adding to RedisStore {} {}", queryName, Type.string(queryType));
			return;
		}

	}

	@Override
	public void discardContent() {
		// TODO Auto-generated method stub

	}

	@Override
	public List<String> getContentDump() {
		// TODO Auto-generated method stub
		return null;
	}

}
