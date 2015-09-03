package net.pierreroudier.pacnas.store;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
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
	public Store getRecords(String queryName, int queryType, Handler<AsyncResult<Record[]>> handler) {
		switch (queryType) {
			case Type.A:
				storeA.getRecords(queryName, handler);
				break;
		}
		return this;
	}

	@Override
	public void putRecords(String queryName, int queryType, Record[] records) {
		switch (queryType) {
		case Type.A:
			// TODO check that all records have the same queryName, queryType and TTL
			long  ttl = records[0].getTTL();
			List<String> ipAddresses = new ArrayList<>();
			for (Record record : records) {
				ARecord ar = (ARecord) record;
				ipAddresses.add(ar.getAddress().getHostAddress());
			}
			storeA.putRecords(queryName, ttl, ipAddresses);
			break;

		default:
			logger.info("Not adding to RedisStore type {} for '{}'", Type.string(queryType), queryName);
			return;
		}

	}

	@Override
	public void discardContent() {
		storeA.discardContent();
	}

	@Override
	public List<String> getContentDump() {
		// TODO Auto-generated method stub
		return null;
	}

}
