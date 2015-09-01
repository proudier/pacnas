package net.pierreroudier.pacnas.store;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.RedisClient;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;

public class RedisStoreForRecordA implements StoreForRecordA {
	private final Logger logger = LoggerFactory.getLogger(RedisStoreForRecordA.class);
	private RedisClient redis;

	public RedisStoreForRecordA(Vertx vertx) {
		JsonObject config = new JsonObject().put("host", "127.0.0.1").put("port", 6379);

		redis = RedisClient.create(vertx, config);
	}

	@Override
	public StoreForRecordA getRecords(String queryName, Handler<AsyncResult<List<Record>>> handler) {
		redis.get(keyForTtl(queryName), resTtl -> {
			if (resTtl.failed() || resTtl.result() == null) {
				logger.trace("No TTL retrieved from RedisStore", resTtl.cause());
				handler.handle(Future.failedFuture(resTtl.cause()));
			} else {
				redis.smembers(keyForAddresses(queryName), resAddr -> {
					if (resAddr.failed() || resTtl.result() == null) {
						logger.trace("No Addresses retrieved from RedisStore", resAddr.cause());
						handler.handle(Future.failedFuture(resAddr.cause()));
					} else {
						List<Record> records = null;
						try {
							long ttl = Long.parseLong(resTtl.result());

							JsonArray jsonArray = (JsonArray) resAddr.result();
							if(jsonArray.size() == 0) {
								handler.handle(Future.succeededFuture());
							}
							List<String> addresses = jsonArray.getList();
							records = new ArrayList<Record>(addresses.size());
							Name name = new Name(queryName);
							for (String address : addresses) {
								records.add(new ARecord(name, DClass.IN, ttl, InetAddress.getByName(address)));
								logger.trace("Built ARecord from cache");
							}
						} catch (NumberFormatException e) {
							logger.trace("TTL data from RedisStore is invalid", e);
							handler.handle(Future.failedFuture(e));
						}  catch (Exception e) {
							logger.error("Addresses data from RedisStore is invalid", e);
							handler.handle(Future.failedFuture(e));
						}

						handler.handle(Future.succeededFuture(records));
					}
				});
			}
		});
		return this;
	}

	@Override
	public void putRecords(String queryName, long ttl, List<String> ipAddresses) {
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
}
