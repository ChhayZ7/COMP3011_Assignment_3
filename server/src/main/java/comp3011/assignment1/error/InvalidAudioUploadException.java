package comp3011.assignment1.error;

/**
 * Thrown when an upload cannot be processed because the client sent nothing usable.
 * Mapped to HTTP 400 by {@link GlobalExceptionHandler}: the request is malformed, so retrying
 * it unchanged will not help.
 */

public class InvalidAudioUploadException extends RuntimeException {
	private static final long serialVersionUID = 1L;
	
	public InvalidAudioUploadException(String message) {
		super(message);
	}
}
