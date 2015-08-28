package net.pierreroudier.pacnas.store;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.RedisClient;

import java.net.InetAddress;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Name;

public class RedisStoreForRecordA implements StoreForRecordA {
	private final Logger logger = LoggerFactory.getLogger(RedisStoreForRecordA.class);
	private RedisClient redis;

	public RedisStoreForRecordA(Vertx vertx) {
		JsonObject config = new JsonObject().put("host", "127.0.0.1").put("port", 63790);

		redis = RedisClient.create(vertx, config);
	}

	@Override
	public ARecord[] getRecords(String queryName) {
		redis.get(keyForTtl(queryName), resTtl -> {
			if (resTtl.succeeded()) {
				final long ttl = Long.parseLong(resTtl.result());

				redis.smembers(keyForAddresses(queryName), resAddr -> {
					if (resAddr.succeeded()) {
						final List<String> addresses = (List<String>) ((JsonArray) resAddr.result()).getList();
						ARecord[] records = new ARecord[addresses.size()];
						try {
							Name name = new Name(queryName);
							int i=0;
							for (String address : addresses) {
								records[i] = new ARecord(name, DClass.IN, ttl, InetAddress.getByName(address));
								logger.trace("Built ARecord from cache: {}", records[i].toString());
								i++;
							}
							//return records;
						} catch (Exception e) {
							logger.error("Error while interpreting data from Redis", e);
							// return null
						}
					}
				});
			}
		});
		return null;
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
