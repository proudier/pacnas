package net.pierreroudier.pacnas;

/**
 * Thrown when the response from a remote server is malformed or incoherent
 *
 */
public class RemoteServerResponseException extends Exception {

	private static final long serialVersionUID = 1L;
	private int outcomeReturnCode;

	public RemoteServerResponseException(String message, int outcomeReturnCode) {
		super(message);
		this.outcomeReturnCode = outcomeReturnCode;
	}

	public int getOutcomeReturnCode() {
		return outcomeReturnCode;
	}

	public void setOutcomeReturnCode(int outcomeReturnCode) {
		this.outcomeReturnCode = outcomeReturnCode;
	}

}
