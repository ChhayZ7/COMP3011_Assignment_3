/*
 * Adelaide University COMP3011 Assignment 3.
 *
 * Authors:
 *   1. Kimchhay Leng (a2942784)
 *
 * Copyright 2026 Kimchhay Leng
 */
package comp3011;

import java.io.File;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The player's subtitle feature in one place: the on/off switch, the subtitles fetched so far and the background
 * fetcher. Owned by the {@link VideoPlayerController}, and shared with every {@link FrameSubtitler}.
 *
 * <p>
 * Threading: the JavaFX thread switches subtitles on and off and reports the playback position; the pipeline's
 * subtitle stage and the fetcher's threads read them. Both are single values written by one thread and read by
 * others, so an {@link AtomicBoolean} and a volatile long are enough: no locks, and nothing here can ever make the
 * JavaFX thread or the pipeline wait. Toggling is just flipping the switch, so it cannot interrupt video or audio.
 * </p>
 */
final class SubtitleManager{
	private final AtomicBoolean enabled;
	private final SubtitleTrack track = new SubtitleTrack();
	private final SubtitleClient client;
	private volatile long playbackPositionUs;
	private SubtitleFetcher fetcher; // JavaFx thread only.
	
	SubtitleManager(boolean initiallyEnabled, URI server){
		this.enabled = new AtomicBoolean(initiallyEnabled);
		this.client = new SubtitleClient(server);
	}
	
	/* Starts fetching subtitles for a newly opened film. */
	void open(File mediaFile) {
		stopFetcher();
		track.clear();
		fetcher = new SubtitleFetcher(mediaFile, client, track, () -> playbackPositionUs, enabled::get);
		fetcher.start();
	}
	
	void close() {
		stopFetcher();
		client.close();
	}
	
	boolean isEnabled() {
		return enabled.get();
	}
	
	/* Flips subtitles on or off and returns the new state. */
	boolean toggle() {
		boolean nowEnabled = !enabled.get();
		enabled.set(nowEnabled); // Only the JavaFX thread toggles, so a plain get-then-set is safe.
		return nowEnabled;
	}
	
	/* Called by the model whenever a frame is shown for the user seeks. */
	void updatePlaybackPosition(long timestampUs) {
		playbackPositionUs = timestampUs;
	}
	
	/*
	 * The subtitle to draw on a frame with the given film time, or null. Safe from
	 * any thread.
	 */
	SubtitleCue cueAt(long timestampUs) {
		return track.cueAt(timestampUs);
	}
	
	private void stopFetcher() {
		if (fetcher != null) {
			fetcher.stop();
			fetcher = null;
		}
	}
}