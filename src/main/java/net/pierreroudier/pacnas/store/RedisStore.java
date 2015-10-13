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
import io.vertx.core.Future;
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
	public Store putRecords(Record[] records, Handler<AsyncResult<String>> handler) {
		// TODO check that all Records have the same Name, Type and TTL
		
		String name = records[0].getName().toString();
		int type = records[0].getType();
		long ttl = records[0].getTTL();
		List<String> rdata = new ArrayList<>();
		
		switch (type) {
		case Type.A:
			for (Record record : records) {
				ARecord ar = (ARecord) record;
				rdata.add(ar.getAddress().getHostAddress());
			}
			storeA.putRecords(name, ttl, rdata, handler);
			break;
			
		case Type.NS:
			for (Record record : records) {
				NSRecord ns = (NSRecord) record;
				rdata.add(ns.getAdditionalName().toString());
			}
			storeNs.putRecords(name, ttl, rdata, handler);
			break;			

		default:
			logger.info("Not adding type {} for '{}' to RedisStore", Type.string(type), name);
			handler.handle((Future.failedFuture("Unsupported Record Type")));
		}
		return this;
	}

	@Override
	public Store discardContent(Handler<AsyncResult<String>> handler) {
		storeA.discardContent(resA -> {
			if(resA.succeeded()) {
				storeNs.discardContent(resNs -> {
					if(resNs.succeeded()) {
						handler.handle(Future.succeededFuture());
					} else {
						handler.handle(Future.failedFuture(resNs.cause()));
					}
				});
			} else {
				handler.handle(Future.failedFuture(resA.cause()));
			}
		});
		return this;
	}

	@Override
	public List<String> getContentDump() {
		// TODO Auto-generated method stub
		return null;
	}
	
	@Override
	public Store countItems(Handler<AsyncResult<Long>> handler) {
		storeA.countItems(resA -> {
			if(resA.succeeded()) {
				final long l1 = resA.result();
				storeNs.countItems(resNs -> {
					if(resNs.succeeded()) {
						final long l2 = (l1 + resNs.result()) / 2;
						handler.handle(Future.succeededFuture(l2));
					} else {
						handler.handle(Future.failedFuture(resNs.cause()));
					}
				});
			} else {
				handler.handle(Future.failedFuture(resA.cause()));
			}
		});
		return this;
	}

}
