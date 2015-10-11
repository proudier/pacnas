package net.pierreroudier.pacnas.store;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.RedisClient;

public abstract class AbstractRedisStoreForTypedRecord {
	private final Logger logger = LoggerFactory.getLogger(AbstractRedisStoreForTypedRecord.class);
	private RedisClient redis;

	public AbstractRedisStoreForTypedRecord(Vertx vertx) {
		JsonObject config = new JsonObject().put("host", "127.0.0.1").put("port", 6379);
		redis = RedisClient.create(vertx, config);
	}

	public AbstractRedisStoreForTypedRecord getRecords(String queryName, Handler<AsyncResult<Record[]>> handler) {
		final GetQueryRoutine getQueryRoutine = new GetQueryRoutine();
		redis.get(keyForTtl(queryName), resTtl -> {
			getQueryRoutine.resultTtlQuery = resTtl;
			getQueryRoutine.foo(queryName, handler);
		});
		redis.smembers(keyForRecordData(queryName), resRdata -> {
			getQueryRoutine.resultRdataQuery = resRdata;
			getQueryRoutine.foo(queryName, handler);
		});
		return this;
	}

	public void putRecords(String queryName, long ttl, List<String> recordData) {
		logger.trace("Putting record into RedisStore");

		redis.multi(res -> {
			if (res.failed()) {
				logger.error("Starting transaction failed", res.cause());
			}
		});

		// TTL
		redis.set(keyForTtl(queryName), Long.toString(ttl), res -> {
			if (res.failed()) {
				logger.error("Saved to Redis failed (TTL)", res.cause());
			} else {
				logger.trace("TTL saved");
			}
		});
		// IP Addresses
		redis.saddMany(keyForRecordData(queryName), recordData, res -> {
			if (res.failed()) {
				logger.error("Saved to Redis failed (RData)", res.cause());
			} else {
				logger.trace("IPs saved");
			}
		});

		redis.exec(res -> {
			if (res.failed()) {
				logger.error("Commiting transaction failed", res.cause());
			}
		});
	}

	public void discardContent() {
		redis.flushall(result -> {
			if (result.succeeded()) {
				logger.trace("Discarded content");
			} else {
				logger.trace("Discard content failed");
			}
		});
	}

	public List<String> getContentDump() {
		// TODO Auto-generated method stub
		return null;
	}


	protected abstract String keyForTtl(String queryName);
	protected abstract String keyForRecordData(String queryName);
	protected abstract Record makeRecord(long ttl, Name name, String rdata) throws Exception;

	/**
	 * Not thread safe
	 */
	private class GetQueryRoutine {
		boolean alreadyDone = false;
		AsyncResult<String> resultTtlQuery;
		AsyncResult<JsonArray> resultRdataQuery;

		public void foo(final String queryName, final Handler<AsyncResult<Record[]>> handler) {
			//if(alreadyDone)
				//return;

			// Fail-fast on error conditions
			if(resultTtlQuery != null) {
				if(resultTtlQuery.succeeded()) {
					if(resultTtlQuery.result() == null) {
						logger.trace("No TTL found in RedisStore for given queryName '{}'", queryName);
						alreadyDone = true;
						handler.handle(Future.succeededFuture());
						return;
					}
				} else {
					logger.trace("Failed retrieving TTL from RedisStore", resultTtlQuery.cause());
					alreadyDone = true;
					handler.handle(Future.failedFuture(resultTtlQuery.cause()));
					return;
				}
			}
			if(resultRdataQuery != null) {
				if(resultRdataQuery.succeeded()) {
					if(resultRdataQuery.result() == null || resultRdataQuery.result().size()==0) {
						logger.trace("No Rdata found in RedisStore for given queryName '{}'", queryName);
						alreadyDone = true;
						handler.handle(Future.succeededFuture());
						return;
					}
				} else {
					logger.trace("Failed retrieving Rdata from RedisStore", resultRdataQuery.cause());
					alreadyDone = true;
					handler.handle(Future.failedFuture(resultRdataQuery.cause()));
					return;
				}
			}

			if(resultTtlQuery != null && resultRdataQuery != null) {
				Record[] result = null;
				try {
					long ttl = Long.parseLong(resultTtlQuery.result());
					JsonArray jsonArray = resultRdataQuery.result();
					Name name = new Name(queryName);
					result = new Record[jsonArray.size()];
					for(int i=0; i<jsonArray.size(); i++) {
						String address = jsonArray.getString(i);
						result[i] = makeRecord(ttl, name, address);
						logger.trace("Built ARecord from cache");
					}
				} catch (NumberFormatException e) {
					logger.trace("TTL data from RedisStore is invalid", e);
					handler.handle(Future.failedFuture(e));
					return;
				}  catch (Exception e) {
					logger.error("Rdata data from RedisStore is invalid", e);
					handler.handle(Future.failedFuture(e));
					return;
				}

				alreadyDone = true;
				handler.handle(Future.succeededFuture(result));
				return;
			}
		}
	}
}
