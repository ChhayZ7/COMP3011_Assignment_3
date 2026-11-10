package comp3011.assignment1.error;

/*
* Thrown when a shutdown is requested while one has already been accepted.
* Mapped to HTTP 409 by {@link GlobalExceptionHandler), as required by the YAML specification.
*/
public class ShutdownInProgressException extends RuntimeException{
	private static final long serialVersionUID = 1L;
	
	public ShutdownInProgressException() {
		super("Graceful shutdown in already in progress.");
	}
}
