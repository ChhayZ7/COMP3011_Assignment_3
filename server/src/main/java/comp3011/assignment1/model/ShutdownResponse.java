package comp3011.assignment1.model;

/**
 * Response body for a successfully accepted POST /api/v1/admin/shutdown request.
 *
 * @param message human-readable shutdown acknowledgement
 */

public record ShutdownResponse(String message) {

}
