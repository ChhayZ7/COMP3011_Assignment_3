package comp3011.assignment1.model;

import java.util.List;

/**
 * Response body for POST /api/v1/subtitles.
 *
 * @param cues   the subtitles for the uploaded clip, in time order and never overlapping
 * @param cached true if the cues came from this server's cache instead of a new Whisper call
 */
public record SubtitleResponse(List<SubtitleCue> cues, boolean cached) {
}
