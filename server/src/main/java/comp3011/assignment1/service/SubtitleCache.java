package comp3011.assignment1.service;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import comp3011.assignment1.model.SubtitleCue;

/**
 * Remembers the subtitles already made for each audio clip, so a clip is only ever sent to
 * Whisper once, however many players ask for it.
 *
 * <p>
 * <b>Key.</b> The SHA-256 hash of the uploaded audio bytes. Every copy of the player cuts the same
 * film into the same clips and decodes them the same way, so the same part of the same film gives
 * the same bytes and the same key, whichever client sends it. Because the key is computed by the
 * server from the audio itself, a client cannot put wrong subtitles under another clip's key.
 * Clips are aligned to fixed positions in the film, so seeking anywhere still lands on clips that
 * may already be cached.
 * </p>
 *
 * <p>
 * <b>Concurrency.</b> Each entry is a {@link CompletableFuture}. The first request for a clip puts
 * an unfinished future in the map with {@code putIfAbsent}, which is atomic, and does the Whisper
 * call itself. Any other request for the same clip that arrives meanwhile finds that future and
 * waits for it instead of making a second paid call. If the call fails, the entry is removed so a
 * later request can try again, and the requests that were waiting get the same failure.
 * </p>
 *
 * <p>
 * <b>Size.</b> At most {@code subtitles.cache.max-entries} clips are kept (about 10 KB each), the
 * oldest being dropped first. Only text is stored, never audio.
 * </p>
 */
@Component
public class SubtitleCache {
	/** What {@link #getOrCompute} returns: the cues, and whether they were already cached. */
	public record Lookup(List<SubtitleCue> cues, boolean cached) {
	}
	
	private final Map<String, CompletableFuture<List<SubtitleCue>>> entries = new ConcurrentHashMap<>();
	private final Queue<String> insertionOrder = new ConcurrentLinkedQueue<>();
	private final int maxEntries;
	
	public SubtitileCache(@Value("${subtitles.cache.max-entries:5000") int maxEntries) {
		this.maxEntries = maxEntries;
	}
	
    /**
     * Returns the cues for {@code key}, computing them with {@code compute} only if no other
     * request has already done so or is doing so now.
     *
     * @throws RuntimeException whatever {@code compute} threw, here or in the request that ran it
     */
	public Lookup getOrCompute(String key, Supplier<List<SubtitleCue>> compute) {
		CompletableFuture<List<SubtitleCue>> mine = new CompletableFuture<>();
		CompletableFuture<List<SubtitleCue>> existing = entries.putIfAbsent(key, mine);
		if (existing != null) {
			return new Lookup(waitFor(existing), true);
		}
		
		try {
			List<SubtitleCue> cues = compute.get();
			mine.complete(cues);
			insertionOrder.add(key);
			evictOldest();
			return new Lookup(cues, false);
		} catch (RuntimeException e) {
			entries.remove(key, mine); // Let the next request try again.
			mine.completeExceptionally(e);
			throw e;
		}
	}
	
	public int size() {
		return entries.size();
	}
	
	private void evictOldest() {
		while (entries.size() > maxEntries) {
			String oldest = insertionOrder.poll();
			if (oldest == null) {
				return;
			}
			entries.remove(oldest);
		}
	}
	
	private static List<SubtitleCue> waitFor(CompletableFuture<List<SubtitleCue>> future){
		try {
			return future.join();
		} catch (CompletionException e) {
			if (e.getCause() instanceof RuntimeException cause) {
				throw cause;
			}
			throw e;
		}
	}
}
