package net.pierreroudier.pacnas;


public class StatsManager {
	private long queryReceived;
	private long queryAnsweredFromCache;
	private long queryAnsweredByResolution;

	public StatsManager() {
		queryReceived = 0;
		queryAnsweredFromCache = 0;
		queryAnsweredByResolution = 0;
	}

	public void increaseQueryReceived() {
		queryReceived++;
	}

	public void increaseQueryAnsweredFromCache() {
		queryAnsweredFromCache++;
	}

	public void increaseQueryAnsweredByResolution() {
		queryAnsweredByResolution++;
	}

	public long getQueryReceived() {
		return queryReceived;
	}

	public long getQueryAnsweredFromCache() {
		return queryAnsweredFromCache;
	}

	public long getQueryAnsweredByResolution() {
		return queryAnsweredByResolution;
	}

}
