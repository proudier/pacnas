package net.pierreroudier.pacnas;

import java.net.InetAddress;

import net.pierreroudier.pacnas.store.InMemoryJavaHashmapStore;
import net.pierreroudier.pacnas.store.Store;

import org.testng.Assert;
import org.testng.annotations.Test;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;

public class StoreTest {

	@Test(description = "Put a record, retrieve it and check all the information are preserved", timeOut = 1000)
	public void testStorePutGet() throws Exception {
		String queryNameAsString = "google.fr.";
		int queryType = Type.A;
		int queryClass = DClass.IN;
		int ttl = 3600;

		Name queryNameAsDnsjavaName = new Name(queryNameAsString);
		InetAddress ipAddress = InetAddress.getByName(queryNameAsString);
		Record r1 = new ARecord(queryNameAsDnsjavaName, queryClass, ttl, ipAddress);
		Record[] records = new Record[1];
		records[0] = r1;

		Store store = new InMemoryJavaHashmapStore();
		store.putRecords(queryNameAsString, queryType, queryClass, records);

		Record[] recordsFromStore = store.getRecords(queryNameAsString, queryType, queryClass);
		Assert.assertNotNull(recordsFromStore, "The store return records");
		Assert.assertEquals(recordsFromStore.length, 1);
		Assert.assertEquals(recordsFromStore[0].getName(), queryNameAsDnsjavaName);
		Assert.assertEquals(recordsFromStore[0].getType(), queryType);
		Assert.assertEquals(recordsFromStore[0].getDClass(), queryClass);
		Assert.assertEquals(recordsFromStore[0].getTTL(), ttl);
		Assert.assertEquals(recordsFromStore[0].rdataToString(), ipAddress.getHostAddress());
	}

	@Test(description = "Test the discard fonction empty the store properly", timeOut = 1000)
	public void testStoreDiscard() throws Exception {
		String queryNameAsString = "google.fr.";
		int queryType = Type.A;
		int queryClass = DClass.IN;
		int ttl = 3600;

		Name queryNameAsDnsjavaName = new Name(queryNameAsString);
		InetAddress ipAddress = InetAddress.getByName(queryNameAsString);
		Record r1 = new ARecord(queryNameAsDnsjavaName, queryClass, ttl, ipAddress);
		Record[] records = new Record[1];
		records[0] = r1;

		Store store = new InMemoryJavaHashmapStore();
		store.putRecords(queryNameAsString, queryType, queryClass, records);
		store.discardContent();

		Record[] recordsFromStore = store.getRecords(queryNameAsString, queryType, queryClass);
		Assert.assertNull(recordsFromStore, "The store not return records because it should be emtpy");
	}
}
