package net.pierreroudier.pacnas;


public class StatsManager {
	private long queryReceived;
	private long queryAnsweredFromCache;
	private long queryAnsweredFromForwarder;

	public StatsManager() {
		queryReceived = 0;
		queryAnsweredFromCache = 0;
		queryAnsweredFromForwarder = 0;
	}

	public void increaseQueryReceived() {
		queryReceived++;
	}

	public void increaseQueryAnsweredFromCache() {
		queryAnsweredFromCache++;
	}

	public void increaseQueryAnsweredFromForwarder() {
		queryAnsweredFromForwarder++;
	}

	public long getQueryReceived() {
		return queryReceived;
	}

	public long getQueryAnsweredFromCache() {
		return queryAnsweredFromCache;
	}

	public long getQueryAnsweredFromForwarder() {
		return queryAnsweredFromForwarder;
	}

}
