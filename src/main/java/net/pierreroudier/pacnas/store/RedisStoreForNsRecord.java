package net.pierreroudier.pacnas.store;

import org.xbill.DNS.DClass;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;

import io.vertx.core.Vertx;

public class RedisStoreForNsRecord extends AbstractRedisStoreForTypedRecord {

	public RedisStoreForNsRecord(Vertx vertx) {
		super(vertx);
	}

	@Override
	protected String keyForTtl(String queryName) {
		return "ns-" + queryName;
	}

	@Override
	protected String keyForRecordData(String queryName) {
		return "ns_" + queryName;
	}

	@Override
	protected Record makeRecord(long ttl, Name name, String rdata) throws Exception {
		// return new NsRecord(name, DClass.IN, ttl,
		// InetAddress.getByName(rdata));
		return new NSRecord(name, DClass.IN, ttl, Name.fromString(rdata));
	}

}
