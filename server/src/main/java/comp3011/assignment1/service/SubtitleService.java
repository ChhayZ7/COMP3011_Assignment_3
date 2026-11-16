package comp3011.assignment1.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import comp3001.assignment1.model.SubtitleResponse;
import comp3011.assignment1.model.TimedTranscript;

/**
 * Makes subtitles for one uploaded audio clip: looks in the cache, and on a miss asks Whisper,
 * records the billed seconds and lays the transcript out as cues.
 *
 * Holds no mutable state of its own; the cache and the usage counter are both safe for
 * concurrent use.
 */
@Service
public class SubtitleService {
	private static final Logger log = LoggerFactory.getLogger(SubtitleService.class);
	
	private final TimedTranscriptionService transcriptionService;
	private final SubtitleBuilder subtitleBuilder;
	private final SubtitleCache cache;
	private final UsageService usageService;
	
	public SubtitleService(TimedTranscriptService transcriptionService, SubtitleBuilder subtitleBuilder,
			SubtitleCache cache, UsageService usageService) {
		this.transcriptionService = transcriptionService;
		this.subtitleBuilder = subtitleBuilder;
		this.cache = cache;
		this.usageService = usageService;
	}
	
	public SubtitleResponse subtitlesFor(byte[] audio, String filename, String contentType) {
		String key = sha256(audio);
		SubtitleCache.Lookup lookup = cache.getOrCompute(key, () -> {
			TimedTranscript transcript = transcriptionService.transcribe(audio, filename, contentType);
			usageService.recordSeconds(transcript.billedSeconds());
			return subtitleBuilder.build(transcript);
		});
		log.debug("Subtitles for clip {}: {} cues ({})", key.substring(0, 12), lookup.cues().size(),
				lookup.cached() ? "cache hit" : "transcribed");
		return new SubtitleResponse(lookup.cues(), lookup.cached());
	}
	
	private static String sha256(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is always available", e);
		}
	}
}
