package comp3011.assignment1.model;

/**
 * Response body returned to the browser after a successful transcription.
 *
 * Deliberately narrower than {@link TranscriptionResult}. The internal result also carries
 * token usage, which is accounting data the client has no need for: it feeds
 * {@code /api/v1/global/stats} instead. Keeping the two types separate stops internal
 * bookkeeping from leaking into the public contract.
 *
 * @param text the transcribed text
 */

public record TranscriptionResponse(String text){

}
