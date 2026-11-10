package comp3011.assignment1.web;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import comp3011.assignment1.error.InvalidAudioUploadException;
import comp3011.assignment1.error.TranscriptionException;
import comp3011.assignment1.model.TranscriptionResponse;
import comp3011.assignment1.model.TranscriptionResult;
import comp3011.assignment1.service.StatsService;
import comp3011.assignment1.service.TranscriptionService;

/**
 * Receives recorded audio from the browser and returns its transcription.
 *
 * This endpoint is not part of the supplied YAML specification, which covers only the
 * administration and statistics API. The contract here is defined by this application: a
 * multipart upload under the form field {@code file}, answered with a JSON body carrying the
 * transcribed text.
 *
 * The method blocks while the Cloud STT call is in flight. That is intentional and safe:
 * {@code spring.threads.virtual.enabled=true} means each request runs on a virtual thread, which
 * parks rather than occupying an OS thread for the duration of the wait. Blocking code that reads
 * top to bottom is easier to reason about than a reactive pipeline, and with virtual threads it
 * carries no throughput penalty at the concurrency levels this assignment targets.
 *
 * The controller holds no mutable state, so a single instance serves every concurrent request
 * without synchronisation.
 */
@RestController
@RequestMapping("/api/v1")
public class AudioController {
	private static final Logger log = LoggerFactory.getLogger(AudioController.class);
	
	//Fallbacks for the rare client that omits multipart metadata.
	private static final String DEFAULT_FILENAME = "audio.webm";
	private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
	
	private final TranscriptionService transcriptionService;
	private final StatsService statsService;
	
    /**
     * Constructor injection. Both collaborators are final and supplied by Spring, which keeps the
     * controller trivially constructible in a unit test with a hand-made stub for either one.
     */
	public AudioController(TranscriptionService transcriptionService, StatsService statsService) {
		this.transcriptionService = transcriptionService;
		this.statsService = statsService;
	}
	
    /**
     * POST /api/v1/transcribe
     *
     * @param file recorded audio, sent as multipart form field {@code file}
     * @return the transcribed text
     */
	@PostMapping(path = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public TranscriptionResponse transcribe(@RequestParam("file") MultipartFile file) {
		if (file.isEmpty()) {
			throw new InvalidAudioUploadException("No audio data was supplied.");
		}
		
		byte[] audio = readBytes(file);
		String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : DEFAULT_FILENAME;
		String contentType = file.getContentType() != null ? file.getContentType() : DEFAULT_CONTENT_TYPE;
		
		log.debug("Transcribing upload: {} bytes, filename={}, contentType={}", audio.length, filename, contentType);
		
		TranscriptionResult result = transcriptionService.transcribe(audio, filename, contentType);
		
		//Usage is recorded before the response is built, so a client that disconnects mid-response
		//still leaves the global counters accurate. StatsService is safe to call concurrently.
		statsService.recordUsage(result.inputTokens(), result.outputTokens());
		
		return new TranscriptionResponse(result.text());
	}
	
    /**
     * Reads the upload into memory, converting the checked {@link IOException} into this
     * application's own unchecked exception type.
     *
     * Keeping every failure inside one exception vocabulary means {@code GlobalExceptionHandler}
     * has a single, specific case to match rather than falling through to the generic catch-all.
     */
	private byte[] readBytes(MultipartFile file) {
		try {
			return file.getBytes();
		} catch (IOException e) {
			throw new TranscriptionException("Failed to read the uploaded audio.", e);
		}
	}
}
