package net.pierreroudier.pacnas.store;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;

public class RedisStore implements Store {
	private final Logger logger = LoggerFactory.getLogger(RedisStore.class);
	private RedisStoreForARecord storeA;
	private RedisStoreForNsRecord storeNs;

	public RedisStore(Vertx vertx) {
		storeA = new RedisStoreForARecord(vertx);
		storeNs = new RedisStoreForNsRecord(vertx);
	}

	@Override
	public Store getRecords(String queryName, int queryType, Handler<AsyncResult<Record[]>> handler) {
		switch (queryType) {
			case Type.A:
				storeA.getRecords(queryName, handler);
				break;
			case Type.NS:
				storeNs.getRecords(queryName, handler);
				break;
		}
		return this;
	}

	@Override
	public void putRecords(String queryName, int queryType, Record[] records) {
		// TODO check that all records have the same queryName, queryType and TTL
		long ttl = records[0].getTTL();
		List<String> rdata = new ArrayList<>();
		
		switch (queryType) {
		case Type.A:
			for (Record record : records) {
				ARecord ar = (ARecord) record;
				rdata.add(ar.getAddress().getHostAddress());
			}
			storeA.putRecords(queryName, ttl, rdata);
			break;
			
		case Type.NS:
			for (Record record : records) {
				NSRecord ns = (NSRecord) record;
				rdata.add(ns.getAdditionalName().toString());
			}
			storeNs.putRecords(queryName, ttl, rdata);
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
