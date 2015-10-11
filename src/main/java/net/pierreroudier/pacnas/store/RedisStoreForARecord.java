package net.pierreroudier.pacnas.store;

import java.net.InetAddress;

import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;

import io.vertx.core.Vertx;

public class RedisStoreForARecord extends AbstractRedisStoreForTypedRecord {

	public RedisStoreForARecord(Vertx vertx) {
		super(vertx);
	}

	@Override
	protected String keyForTtl(String queryName) {
		return "a-" + queryName;
	}

	@Override
	protected String keyForRecordData(String queryName) {
		return "a_" + queryName;
	}

	@Override
	protected Record makeRecord(long ttl, Name name, String rdata) throws Exception {
		return new ARecord(name, DClass.IN, ttl, InetAddress.getByName(rdata));
	}
}
