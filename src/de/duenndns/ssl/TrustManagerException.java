package de.duenndns.ssl;

public class TrustManagerException extends Exception {

	/**
	 * 
	 */
	private static final long serialVersionUID = 7327871362205183601L;

	public TrustManagerException() {
		super();
	}

	public TrustManagerException(String detailMessage, Throwable throwable) {
		super(detailMessage, throwable);
	}

	public TrustManagerException(String detailMessage) {
		super(detailMessage);
	}

	public TrustManagerException(Throwable throwable) {
		super(throwable);
	}

}
