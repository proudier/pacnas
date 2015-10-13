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
import io.vertx.redis.RedisClient;
import io.vertx.redis.RedisOptions;

public abstract class AbstractRedisStoreForTypedRecord {
	private final Logger logger = LoggerFactory.getLogger(AbstractRedisStoreForTypedRecord.class);
	private RedisClient redis;

	public AbstractRedisStoreForTypedRecord(Vertx vertx) {
		RedisOptions config = new RedisOptions().setHost("127.0.0.1").setPort(6379);
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

	public void putRecords(String name, long ttl, List<String> recordData, Handler<AsyncResult<String>> handler) {
		logger.trace("Putting record into RedisStore");

		redis.multi(hMulti -> {
			if (hMulti.succeeded()) {
				logger.trace("Beginning Redis transaction");

				// TTL
				redis.set(keyForTtl(name), Long.toString(ttl), hSetTtl -> {
					if (hSetTtl.succeeded()) {
						logger.trace("TTL saved");

						// IP Addresses
						redis.saddMany(keyForRecordData(name), recordData, hSetRdata -> {
							if (hSetRdata.succeeded()) {
								logger.trace("IPs saved");

								redis.exec(hCommit -> {
									if (hCommit.succeeded()) {
										handler.handle(Future.succeededFuture());
									} else {
										logger.error("Commiting transaction failed", hCommit.cause());
										handler.handle(Future.failedFuture(hCommit.cause()));
									}
								});
							} else {
								logger.error("Saved to Redis failed (RData)", hSetRdata.cause());
								handler.handle(Future.failedFuture(hSetRdata.cause()));
							}
						});
					} else {
						logger.error("Saved to Redis failed (TTL)", hSetTtl.cause());
						handler.handle(Future.failedFuture(hSetTtl.cause()));
					}
				});
			} else {
				logger.error("Starting transaction failed", hMulti.cause());
				handler.handle(Future.failedFuture(hMulti.cause()));
			}
		});
	}

	public AbstractRedisStoreForTypedRecord discardContent(Handler<AsyncResult<String>> handler) {
		redis.flushall(hFlush -> {
			if (hFlush.succeeded()) {
				logger.trace("Discarded content");
				handler.handle(Future.succeededFuture());
			} else {
				logger.trace("Discard content failed");
				handler.handle(Future.failedFuture(hFlush.cause()));
			}
		});
		return this;
	}

	public List<String> getContentDump() {
		// TODO Auto-generated method stub
		return null;
	}

	public AbstractRedisStoreForTypedRecord countItems(Handler<AsyncResult<Integer>> handler) {
		redis.keys(getPrefixForThisType() + "*", hKeys -> {
			if (hKeys.succeeded()) {
				JsonArray jsonArray = hKeys.result();
				handler.handle(Future.succeededFuture(jsonArray.size()));
			} else {
				handler.handle(Future.failedFuture(hKeys.cause()));
			}
		});
		return this;
	}

	protected String keyForTtl(String queryName) {
		return getPrefixForThisType() + "-" + queryName;
	}

	protected String keyForRecordData(String queryName) {
		return getPrefixForThisType() + "_" + queryName;
	}

	protected abstract Record makeRecord(long ttl, Name name, String rdata) throws Exception;

	protected abstract String getPrefixForThisType();

	/**
	 * Not thread safe
	 */
	private class GetQueryRoutine {
		AsyncResult<String> resultTtlQuery;
		AsyncResult<JsonArray> resultRdataQuery;

		public void foo(final String queryName, final Handler<AsyncResult<Record[]>> handler) {
			// Fail-fast on error conditions
			if (resultTtlQuery != null) {
				if (resultTtlQuery.succeeded()) {
					if (resultTtlQuery.result() == null) {
						logger.trace("No TTL found in RedisStore for given queryName '{}'", queryName);
						handler.handle(Future.succeededFuture());
						return;
					}
				} else {
					logger.trace("Failed retrieving TTL from RedisStore", resultTtlQuery.cause());
					handler.handle(Future.failedFuture(resultTtlQuery.cause()));
					return;
				}
			}
			if (resultRdataQuery != null) {
				if (resultRdataQuery.succeeded()) {
					if (resultRdataQuery.result() == null || resultRdataQuery.result().size() == 0) {
						logger.trace("No Rdata found in RedisStore for given queryName '{}'", queryName);
						handler.handle(Future.succeededFuture());
						return;
					}
				} else {
					logger.trace("Failed retrieving Rdata from RedisStore", resultRdataQuery.cause());
					handler.handle(Future.failedFuture(resultRdataQuery.cause()));
					return;
				}
			}

			if (resultTtlQuery != null && resultRdataQuery != null) {
				Record[] result = null;
				try {
					long ttl = Long.parseLong(resultTtlQuery.result());
					JsonArray jsonArray = resultRdataQuery.result();
					Name name = new Name(queryName);
					result = new Record[jsonArray.size()];
					for (int i = 0; i < jsonArray.size(); i++) {
						String address = jsonArray.getString(i);
						result[i] = makeRecord(ttl, name, address);
						logger.trace("Built ARecord from cache");
					}
				} catch (NumberFormatException e) {
					logger.trace("TTL data from RedisStore is invalid", e);
					handler.handle(Future.failedFuture(e));
					return;
				} catch (Exception e) {
					logger.error("Rdata data from RedisStore is invalid", e);
					handler.handle(Future.failedFuture(e));
					return;
				}

				handler.handle(Future.succeededFuture(result));
				return;
			}
		}
	}
}
