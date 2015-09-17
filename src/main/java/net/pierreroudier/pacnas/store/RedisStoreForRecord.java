package net.pierreroudier.pacnas.store;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.RedisClient;

import java.net.InetAddress;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.*;

public class RedisStoreForRecord<T extends Record> implements StoreForRecord {
	private final Logger logger = LoggerFactory.getLogger(RedisStoreForRecord.class);
	private RedisClient redis;

	public RedisStoreForRecord(Vertx vertx) {
		JsonObject config = new JsonObject().put("host", "127.0.0.1").put("port", 6379);

		redis = RedisClient.create(vertx, config);
	}

	@Override
	public StoreForRecord getRecords(String queryName, Handler<AsyncResult<Record[]>> handler) {
		final GetQueryRoutine getQueryRoutine = new GetQueryRoutine();
		redis.get(keyForTtl(queryName), resTtl -> {
			getQueryRoutine.resultTtlQuery = resTtl;
			getQueryRoutine.foo(queryName, handler);
		});
		redis.smembers(keyForAddresses(queryName), resAddr -> {
			getQueryRoutine.resultAddrQuery = resAddr;
			getQueryRoutine.foo(queryName, handler);
		});
		return this;
	}

	@Override
	public void putRecords(String queryName, long ttl, List<String> ipAddresses) {
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
		redis.saddMany(keyForAddresses(queryName), ipAddresses, res -> {
			if (res.failed()) {
				logger.error("Saved to Redis failed (Addresses)", res.cause());
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

	@Override
	public void discardContent() {
		redis.flushall(result -> {
			if (result.succeeded()) {
				logger.trace("Discarded content");
			} else {
				logger.trace("Discard content failed");
			}
		});
	}

	@Override
	public List<String> getContentDump() {
		// TODO Auto-generated method stub
		return null;
	}

	protected String keyForTtl(String queryName) {
		return "t_" + queryName;
	}

	protected String keyForAddresses(String queryName) {
		return "a_" + queryName;
	}

	/**
	 * Not thread safe
	 */
	private class GetQueryRoutine {
		boolean alreadyDone = false;
		AsyncResult<String> resultTtlQuery;
		AsyncResult<JsonArray> resultAddrQuery;

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
			if(resultAddrQuery != null) {
				if(resultAddrQuery.succeeded()) {
					if(resultAddrQuery.result() == null || resultAddrQuery.result().size()==0) {
						logger.trace("No Address found in RedisStore for given queryName '{}'", queryName);
						alreadyDone = true;
						handler.handle(Future.succeededFuture());
						return;
					}
				} else {
					logger.trace("Failed retrieving Address from RedisStore", resultAddrQuery.cause());
					alreadyDone = true;
					handler.handle(Future.failedFuture(resultAddrQuery.cause()));
					return;
				}
			}

			if(resultTtlQuery != null && resultAddrQuery != null) {
				Record[] result = null;
				try {
					long ttl = Long.parseLong(resultTtlQuery.result());
					JsonArray jsonArray = resultAddrQuery.result();
					Name name = new Name(queryName);
					result = new Record[jsonArray.size()];
					for(int i=0; i<jsonArray.size(); i++) {
						String address = jsonArray.getString(i);
						result[i] = new ARecord(name, DClass.IN, ttl, InetAddress.getByName(address));
						//result[i] = Record.fromString(name, Type.A, DClass.IN, ttl, address, null);
						logger.trace("Built ARecord from cache");
					}
				} catch (NumberFormatException e) {
					logger.trace("TTL data from RedisStore is invalid", e);
					handler.handle(Future.failedFuture(e));
					return;
				}  catch (Exception e) {
					logger.error("Addresses data from RedisStore is invalid", e);
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
