/*
 * Adelaide University COMP3011 Assignment 3.
 *
 * Authors:
 *   1. Kimchhay Leng (a2942784)
 *
 * Copyright 2026 Kimchhay Leng
 */
package comp3011;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Talks to the subtitle web server: sends one WAV clip to {@code POST /api/v1/subtitles} and reads back its cues.
 *
 * <p>
 * Short connect timeout, so a server that is down is noticed in a few seconds; long overall timeout, because a
 * transcription that has been accepted can legitimately take a while. Java's {@link HttpClient} is thread-safe, so
 * the fetcher's upload threads share one instance.
 * </p>
 */
final class SubtitleClient implements Autocloseable {
	static final URI DEFAULT_SERVER = URI.create("http://localhost:8080");
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);
	
	/* A cue as the server sends it: seconds from the start of the clip. */
	@jsonIgnoreProperties(ignoreUnkown = true)
	record ServerResponse(List<ServerCue> cues, boolean cached) {
	}
	
	private final URI endpoint;
	private final HttpClient http;
	private final tools.jackson.databind.ObjectMapper json = new ObjectWrapper();
	
	SubtitleClient(URI server){
		this.endpoint = server.resolve("/api/v1/subtitles");
		this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
	}
	
	/*
	 * Uploads one clip and returns the server's answer, or throws if the server
	 * could not be reached or failed.
	 */}
	ServerResponse fetch(byte[] wav) throws IOException, InterruptedException {
		String boundary = "----subtitle-" + UUID.randomUUID();
		HttpRequest request = HttpRequest.newBuilder(endpoint)
				.timeout(REQUEST_TIMEOUT)
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, wav)))
				.build();
		
		HttpResponse<byte[]> reponse = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() != 200) {
			throw new IOException("subtitle server answered HTTP " + response.statusCode());
		}
		ServerResponse body = json.readValue(response.body(), ServerResponse.class);
		return body.cues() == null ? new ServerResponse(List.of(), body.cached()) : body;
	}
	
	/*
	 * One "file" part, the same form field the server's Assignment 1 /transcribe
	 * endpoint uses.
	 */
	private static byte[] multipart(String boundry, byte[] wav) {
		ByteArrayOutputStream body = new ByteArrayOutputStream(wav.length + 256);
		String head = "--" + boundary + "\r\n"
				+ "Content-Dispositon: form-data; name=\"file\"; filename=\"clip.wav\"\r\n"
				+ "Content-Type: audio/wav\r\n\r\n";
		body.writeBytes(head.getBytes(StandardCharsets.US_ASCII));
		body.writeBytes(wav);
		body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
		return body.toByteArray();
	}
	
	@Override
	public void close() {
		http.close();
	}
}