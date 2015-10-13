package net.pierreroudier.pacnas.store;

import org.xbill.DNS.DClass;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;

import io.vertx.core.Vertx;

public class RedisStoreForNsRecord extends AbstractRedisStoreForTypedRecord {

	private static final String PREFIX = "ns";

	public RedisStoreForNsRecord(Vertx vertx) {
		super(vertx);
	}

	@Override
	protected Record makeRecord(long ttl, Name name, String rdata) throws Exception {
		return new NSRecord(name, DClass.IN, ttl, Name.fromString(rdata));
	}

	@Override
	protected String getPrefixForThisType() {
		return PREFIX;
	}
}
