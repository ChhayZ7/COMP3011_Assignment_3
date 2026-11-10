package comp3011.assignment1.error;

/**
 * Thrown when the Cloud STT service could not produce a transcription.
 *
 * The message carried by this exception is written by this application, never copied from the
 * upstream provider response. Upstream bodies can echo request headers, so propagating them to a
 * client would risk disclosing the bearer token.
 */

public class TranscriptionException extends RuntimeException {
	private static final long serialVersionUID = 1L;
	
	public TranscriptionException(String message) {
		super(message);
	}
	
	public TranscriptionException(String message, Throwable cause) {
		super(message, cause);
	}
}
