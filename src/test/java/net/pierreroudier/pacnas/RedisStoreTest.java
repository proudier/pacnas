package net.pierreroudier.pacnas;

import java.net.InetAddress;
import java.util.List;
import java.util.Vector;

import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestOptions;
import io.vertx.ext.unit.TestSuite;
import io.vertx.ext.unit.report.ReportOptions;
import net.pierreroudier.pacnas.store.RedisStore;

public class RedisStoreTest {
	private static final long TIMEOUT_MS = 10 * 1000;

	private Vertx vertx;
	private RedisStore redisStore;

	public static void main(String[] args) {
		new RedisStoreTest().run();
	}

	public void run() {
		TestOptions testOptions = new TestOptions().setTimeout(TIMEOUT_MS)
				.addReporter(new ReportOptions().setTo("console"));
		TestSuite suite = TestSuite.create("net.pierreroudier.pacnas.RedisStoreTest");

		suite.before(context -> {
			VertxOptions vertxOption = new VertxOptions();
			vertxOption.setMaxEventLoopExecuteTime(Long.MAX_VALUE);
			vertx = Vertx.vertx(vertxOption);
			redisStore = new RedisStore(vertx);
		});

		suite.after(context -> {
			vertx.close(context.asyncAssertSuccess());
		});

		suite.test("DiscardStoreContentTest", context -> {
			Async async = context.async();

			List<Record> nsRecords = new Vector<Record>();
			List<Record> aRecords = new Vector<Record>();
			try {
				nsRecords.add(new NSRecord(new Name("pierreroudier.net."), DClass.IN, 1800,
						Name.fromString("ns101.ovh.net.")));
				aRecords.add(new ARecord(new Name("pierreroudier.net."), DClass.IN, 3600,
						InetAddress.getByName("192.168.1.5")));
				aRecords.add(new ARecord(new Name("pierreroudier.net."), DClass.IN, 3600,
						InetAddress.getByName("192.168.1.50")));
			} catch (Exception e) {
				context.fail(e);
			}

			redisStore.discardContent(hDiscard -> {
				if (hDiscard.succeeded()) {
					redisStore.countItems(hCount1 -> {
						if (hCount1.succeeded()) {
							context.assertEquals(0L, hCount1.result());

							redisStore.putRecords(nsRecords.toArray(new Record[1]), hPutNsRecords -> {
								if (hPutNsRecords.succeeded()) {
									redisStore.countItems(hCount2 -> {
										if (hCount2.succeeded()) {
											context.assertEquals(1L, hCount2.result());

											redisStore.putRecords(aRecords.toArray(new Record[1]), hPutARecords -> {
												if (hPutARecords.succeeded()) {
													redisStore.countItems(hCount3 -> {
														if (hCount3.succeeded()) {
															context.assertEquals(2L, hCount3.result());

															redisStore.getRecords(nsRecords.get(0).getName().toString(),
																	nsRecords.get(0).getType(), hGetRecords1 -> {
																if (hGetRecords1.succeeded()) {
																	Record[] readRecords1 = hGetRecords1.result();
																	context.assertEquals(1, readRecords1.length);
																	context.assertEquals(nsRecords.get(0), readRecords1[0]);
																	async.complete();	
																} else {
																	context.fail(hGetRecords1.cause());
																}
															});
														} else {
															context.fail(hCount3.cause());
														}
													});
												} else {
													context.fail(hPutARecords.cause());
												}
											});
										} else {
											context.fail(hCount2.cause());
										}
									});
								} else {
									context.fail(hPutNsRecords.cause());
								}
							});
						} else {
							context.fail(hCount1.cause());
						}
					});
				} else {
					context.fail(hDiscard.cause());
				}
			});
		});

		suite.run(testOptions);
	}
}
