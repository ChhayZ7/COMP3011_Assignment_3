package comp3011.assignment1.model;

/**
 * Response body for GET /api/v1/global/usage: the total audio, in seconds, sent to the
 * transcription service since the server started.
 *
 * @param usage cumulative billed seconds of transcription
 */
public record UsageResponse(double usage) {
}