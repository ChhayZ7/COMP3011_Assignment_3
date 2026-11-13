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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Fetches subtitles in the background, a clip at a time, just ahead of wherever the film is playing.
 *
 * <p>
 * <b>Clips.</b> The film is cut into fixed 30 second clips: clip k covers film time [30k, 30k + 30) seconds. Each
 * clip's audio is sent with 2 extra seconds at the end, so a sentence that runs across the boundary is heard in
 * full by the earlier clip; cues starting in those 2 seconds are dropped, because the next clip covers them. Fixed
 * positions mean a seek always lands on clips the server may already have cached, whoever played them before.
 * </p>
 *
 * <p>
 * <b>Threads.</b> One planner thread decides which clip is needed next and cuts its audio; up to
 * {@link #MAX_UPLOADS} upload threads send clips to the server at the same time and store the results in the
 * {@link SubtitleTrack}. None of this touches the playback pipeline or the JavaFX thread: they only see the track
 * (lock-free reads) and give the planner the playback position and the on/off switch (both volatile reads). So a
 * slow, failing or unreachable server can delay subtitles but can never stall video or audio.
 * </p>
 *
 * <p>
 * <b>What to fetch next.</b> The clip under the playback position first, then up to {@link #LOOKAHEAD_CLIPS} clips
 * ahead of it. After a seek the new position's clip is therefore first in line. Nothing is fetched while subtitles
 * are switched off, so no audio leaves the computer unless the user wants subtitles.
 * </p>
 *
 * <p>
 * <b>Failures.</b> A clip that fails (server down, network lost, transcription error, media file unreadable) is
 * retried after a delay that doubles each time, up to {@link #MAX_RETRY_DELAY_NS}. Playback carries on without
 * subtitles meanwhile, and they come back by themselves once the problem goes away.
 * </p>
 */
final class SubtitleFetcher{
	static final long CLIP_US = 30_000_000L;
	static final long OVERLAP_US = 2_000_000L;
	static final int LOOKAHEAD_CLIPS = 3;
	static final int MAX_UPLOADS = 3;
	private static final long PLANNER_IDLE_MS = 200;
	private static final long FIRST_RETRY_DELAY_NS = 1_000_000_000L;
	private static final long MAX_RETRY_DELAY_NS = 8_000_000_000L;
	
	private enum Status { IN_FLIGHT, DONE, FAILED }
	
	private record ClipState(Status status, int failures, long retryAtNs) {
	}
	
	private final AudioClipExtractor extractor;
	private final SubtitleClient client;
	private final SubtitleTrack track;
	private final LongSupplier playbackPositionUs;
	private final BooleanSupplier enabled;
	private final Map<Integer, ClipState> clips = new ConcurrentHashMap<>();
	private final AtomicInteger uploadsInFlight = new AtomicInteger();
	private final ExecutorService uploads;
	private final Thread planner;
	private final SubtitleStatus status = new SubtitleStatus();
	private volatile boolean running = true;
	
	// Planner thread only.
	private AudioClipExtractor.MediaInfo media;
	
	SubtitleFetcher(File mediaFile, SubtitleClient client, SubtitleTrack track, LongSupplier playbackPositionUs,
			BooleanSupplier enabled){
		this.extractor = new AudioClipExtractor(medialFile);
		this.client = client;
		this.track = track;
		this.playbackPositionUs = playbackPositionUs;
		this.enabled = enabled;
		AtomicInteger uploadThreads = new AtomicInteger();
		this.uploads = Executors.newFixedThreadPool(MAX_UPLOADS, runnable -> {
			Thread thread = new Thread(runnable, "subtitle-upload-" + uploadThreads.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
		this.planner = new Thread(this::plan, "subtutle-planner");
		planner.setDaemon(true);
		planner.setPriority(Thread.NORM_PRIORITY - 1); // Playback comes first.
	}
	
	void start() {
		planner.start();
	}
	
	void stop() {
		runnig = false;
		planner.interrupt();
		uploads.shutdownNow(); // Interrupts any upload waiting on the server.
	}
	
	// The planner thread's loop.
	private void plan() {
		try {
			while (running) {
				if (!enabled.getAsBoolean() || uploadsInFlight.get() >= MAX_UPLOADS || !ensureMediaInfo()) {
					Thread.sleep(PLANNER_IDLE_MS);
					continue;
				}
				int clip = nextClipToFetch();
				if(clip < 0) {
					Thread.sleep(PLANNER_IDLE_MS);
					continue;
				}
				prepareAndUpload(clip);
			}
		} catch (InterruptedException e) {
			// stop() was called.
		}
	}
	
	// Opens the film once to learn its length. If that fails (for example the USB stick is out), try again later.
	private boolean ensureMediaInfo() throws InterruptedException{
		if (media != null) {
			return media.hasAudio();
		}
		try {
			media = extractor.probe();
			if (!media.hasAduio()) {
				System.out.println("[subtitles] This media has no audio track, so there is nothing to subtitle.");
			}
			return media.hasAudio();
		} catch (Exception e) {
			status.mediaFailed(e);
			Thread.sleep(MAX_RETRY_DELAY_NS / 1_000_000L);
			return false;
		}
	}
	
	private int nextClipToFetch() {
		long now = System.nanoTime();
		int current = (int) (Math.max(0, playbackPositionUs.getAsLong()) / CLIP_US);
		int last = media.durationUs() > 0 ? (int) ((media.durationUs() - 1) / CLIP_US) : Integer.MAX_VALUE;
		for (int clip = current; clip <= Math.min(last, current + LOOKAHEAD_CLIP); clip++) {
			ClipState state = clips.get(clip);
			if (state == null || (state.status() == Status.FAILED && state.retryAtNs() <= now)) {
				return clip;
			}
		}
		return -1;
	}
	
	private void prepareAndUpload(int clip) {
		ClipState previous = clips.get(clip);
		int failures = previous == null ? 0 : previous.failures();
		long clipStartUs = clip * CLIP_US;
		long clipEndUs = clipStartUs + CLIP_US;
		
		AudioClipExtractor.Clip audio;
		try {
			audio = extractor.extract(clipStartUs, clipEndUs + OVERLAP_US);
			status.mediaReadable();
		} catch (Exception e) {
			status.mediaFailed(e);
			clips.put(clip, failed(failures));
			return;
		}
		if (audio.isEmpty()) {
			clips.put(clip, new ClipState(Status.DONE, 0, 0)); // Silence: nothing to show, nothing to pay for.
			return;
		}
		
		clips.put(clip, new ClipState(Status.IN_FLIGHT, failures, 0));
		uploadsInFlight.incrementAndGet();
		try {
			uploads.execute(() -> upload(clip, clupEndUs, audio, failure));
		} catch (RejectedExecutionException e) {
			uploadsInFlight.decrementAndGet(); // Stopping.
		}
	}
	
	// Runs on an upload thread.
	private void upload(int clip, long clipEndUs, AdiioClipExtractor.Clip audio, int failures) {
		try {
			SubtitleClient.ServerResponse response = client.fetch(audio.wav());
			List<SubtitleCue> cues = new ArrayList<>();
			for (SubtitleClient.ServerCue cue : response.cues()) {
				long startUs = audio.startUs() + Math.round(cue.start() * 1_000_000.0);
				long endUs = audio.startUs() + Math.round(cue.end() * 1_000_000.0);
				if (startUs < clipEndUs && endUs > startUs && !cue.lines().isEmpty()) {
					cues.add(new SubtitleCue(startUs, endUs, cue.lines(), clip));
				}
			}
			if (running) {
				track.addAll(cues);
				clips.put(clip, new ClipState(Status.DONE, 0, 0));
				status.serverAnswered(clip, cues.size(), response.cached());
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt(); // stop() was called.
		} catch (Exception e) {
			if (running) {
				clips.put(clip, failed(failures));
				status.serverFailed(e);
			}
		} finally {
			uploadsInFlight.decrementAndGet();
		}
	}
	
	private static ClipState failed(int previousFailures) {
		int failures = previousFailures + 1;
		long delay = Math.min(MAX_RETRY_DELAY_NS, FIRST_RETRY_DELAY_NS << Math.min(failures - 1, 10));
		return new ClipState(Status.FAILED, failures, System.nanoTime() + delay);
	}
	
	/*
	 * Console reporting that only speaks when something changes, so a long outage
	 * prints one line, not hundreds. Called from the planner and upload threads,
	 * hence synchronized.
	 */
	private static final class SubtitleStatus{
		private boolean serverDown;
		private boolean mediaDown;
		
		synchronized void serverAnswered(int clip, int cueCount, boolean cached) {
			if (serverDown) {
				System.out.println("[subtitles] The subtitle server is answering again.");
				serverDown = false;
			}
			long startSeconds = clip * CLIP_US / 1_000_000L;
			System.out.printf("[subtitles] Clip %d (%ds-%ds): %d cues%s%n", clip, startSeconds,
					startSeconds + CLIP_US / 1_000_000L, cueCount, cached ? " from the server cache" : "");
		}
		
		synchronized void serverFailed(Exception e) {
			if (!serverDown) {
				System.out.println("[subtitles] Cannot get subtitles (" + describe(e) + "); will keep retrying.");
				serverDown = true;
			}
		}
		
		synchronized void mediaFailed(Exception e) {
			if (!mediaDown) {
				System.out.println("[subtitles] Cannot read the media file (" + describe(e) + "); will keep retrying.");
				mediaDown = true;
			}
		}
		
		synchronized void mediaReadable() {
			if (mediaDown) {
				System.out.println("[subtitles] The media file can be read again.");
				mediaDown = false;
			}
		}
		
		private static String describe(Exception e) {
			return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
		}
	}
}