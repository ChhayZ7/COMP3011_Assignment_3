package comp3011.assignment1.web;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import comp3011.assignment1.error.InvalidAudioUploadException;
import comp3011.assignment1.error.TranscriptionException;
import comp3011.assignment1.model.SubtitleResponse;
import comp3011.assignment1.service.SubtitleService;

/**
 * POST /api/v1/subtitles: receives a clip of a film's original audio from the video player and
 * returns subtitle cues for it, timed from the start of the clip.
 *
 * The request is {@code multipart/form-data} with the audio in a {@code file} field, the same
 * contract as {@code /api/v1/transcribe}. Like {@link AudioController} it blocks on a virtual
 * thread while Whisper works, and holds no state.
 */
@RestController
@RequestMapping("/api/v1")
public class SubtitleController {
	private static final String DEFAULT_FILENAME = "clip.wav";
	private static final String DEFAULT_CONTENT_TYPE = "audio/wav";
	
	private final SubtitleService subtitleService;
	
	public SubtitleController(SubtitleService subtitleService) {
		this.subtitleService = subtitleService;
	}
	
	@PostMapping(path = "/subtitles", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public SubtitleResponse subtitles(@RequestParam("file") MultipartFile file) {
		if (file.isEmpty()) {
			throw new InvalidAudioUploadException("No audio data was supplied.");
		}
		byte[] audio;
		try {
			audio = file.getBytes();
		} catch (IOException e) {
			throw new TranscriptionException("Failed to read the uploaded audio.", e);
		}
		String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : DEFAULT_FILENAME;
		String contentType = file.getContentType() != null ? file.getContentType() : DEFAULT_CONTENT_TYPE;
		return subtitleService.subtitlesFor(audio, filename, contentType);
	}
}
