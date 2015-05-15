package net.pierreroudier.pacnas.dns;

public class DnsException extends Exception {
	private static final long serialVersionUID = 1L;

	public DnsException(int returnCode) {
		super();
		this.returnCode = returnCode;
	}

	private int returnCode;

	public int getReturnCode() {
		return returnCode;
	}

	public void setReturnCode(int returnCode) {
		this.returnCode = returnCode;
	}
}
