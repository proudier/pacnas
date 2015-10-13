package net.pierreroudier.pacnas.store;

import java.net.InetAddress;

import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;

import io.vertx.core.Vertx;

public class RedisStoreForARecord extends AbstractRedisStoreForTypedRecord {

	private static final String PREFIX = "a";

	public RedisStoreForARecord(Vertx vertx) {
		super(vertx);
	}
	@Override
	protected Record makeRecord(long ttl, Name name, String rdata) throws Exception {
		return new ARecord(name, DClass.IN, ttl, InetAddress.getByName(rdata));
	}
	
	@Override
	protected String getPrefixForThisType() {
		return PREFIX;
	}
}
