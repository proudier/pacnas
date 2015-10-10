package net.pierreroudier.pacnas;

/**
 * Thrown when the request coming from the client is malformed or incoherent
 *
 */
public class IncomingRequestException extends Exception {

  private static final long serialVersionUID = 1L;
  private int outcomeReturnCode;

  public IncomingRequestException(String message, int outcomeReturnCode) {
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
