package comp3011.assignment1.model;

/**
 * Standard JSON error body defined by the YAML specification. Every non-2xx response produced
 * by this application uses this shape, assembled centrally in
 * {@link comp3011.assignment1.error.GlobalExceptionHandler}.
 *
 * @param timestamp UTC timestamp at which the error was generated (RFC 3339)
 * @param status    HTTP status code
 * @param error     short HTTP reason phrase
 * @param message   human-readable description of the failure
 * @param path      request path that produced the error
 */

public record ErrorResponse(String timestamp, int status, String error, String message, String path) {

}
