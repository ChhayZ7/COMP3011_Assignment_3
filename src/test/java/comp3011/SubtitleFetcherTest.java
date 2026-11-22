/*
 * Adelaide University COMP3011 Assignment 3.
 *
 * Authors:
 *   1. Kimchhay Leng (a2942784)
 *
 * Copyright 2026 Kimchhay Leng
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

/**
 * End-to-end tests of subtitle fetching against a fake subtitle server: real audio is cut from a real file by FFmpeg,
 * uploaded over HTTP, and the answers land in the {@link SubtitleTrack} at the right film times.
 */
class SubtitleFetcherTest {
	private static final int RATE = 44_100;
	// The fake server answers every clip with one cue from 1s to 2.5s into the clip.
	private static final String ONE_CUE = "{\"cues\":[{\"start\":1.0,\"end\":2.5,\"lines\":[\"Hello there.\"]}],"
			+ "\"cached\":false}";

	@TempDir
	Path tempDir;

	private HttpServer server;
	private final List<Integer> uploadSizes = new CopyOnWriteArrayList<>();
	private final AtomicInteger failuresLeft = new AtomicInteger();
	private SubtitleClient client;

	@BeforeEach
	void startFakeServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/v1/subtitles", exchange -> {
			byte[] body = exchange.getRequestBody().readAllBytes();
			uploadSizes.add(body.length);
			boolean fail = failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0;
			byte[] reply = (fail ? "{}" : ONE_CUE).getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(fail ? 503 : 200, reply.length);
			exchange.getResponseBody().write(reply);
			exchange.close();
		});
		server.start();
		client = new SubtitleClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
	}

	@AfterEach
	void stopFakeServer() {
		server.stop(0);
		client.close();
	}

	@Test
	void fetchesClipsAroundThePlaybackPositionAndPlacesCuesOnTheFilmTimeline() throws Exception {
		File film = tone(tempDir.resolve("film.wav"), 75);
		SubtitleTrack track = new SubtitleTrack();
		SubtitleFetcher fetcher = new SubtitleFetcher(film, client, track, () -> 0L, () -> true);
		fetcher.start();
		try {
			waitFor(() -> track.size() == 3); // 75 seconds is three 30 second clips.
		} finally {
			fetcher.stop();
		}

		assertNotNull(track.cueAt(1_500_000), "clip 0's cue at 1s");
		assertNotNull(track.cueAt(31_500_000), "clip 1's cue at 31s");
		assertNotNull(track.cueAt(61_500_000), "clip 2's cue at 61s");
		assertNull(track.cueAt(10_000_000));
		// 32 seconds (a clip plus its overlap) of 16 kHz 16-bit mono, plus the WAV and multipart headers.
		assertTrue(uploadSizes.get(0) > 32 * 16_000 * 2 && uploadSizes.get(0) < 32 * 16_000 * 2 + 1_000,
				"upload was " + uploadSizes.get(0) + " bytes");
	}

	@Test
	void sendsNothingWhileSubtitlesAreOff() throws Exception {
		File film = tone(tempDir.resolve("film.wav"), 40);
		AtomicBoolean enabled = new AtomicBoolean(false);
		SubtitleTrack track = new SubtitleTrack();
		SubtitleFetcher fetcher = new SubtitleFetcher(film, client, track, () -> 0L, enabled::get);
		fetcher.start();
		try {
			Thread.sleep(1_000);
			assertTrue(uploadSizes.isEmpty(), "no audio leaves the computer while subtitles are off");
			enabled.set(true);
			waitFor(() -> track.size() == 2);
		} finally {
			fetcher.stop();
		}
	}

	@Test
	void keepsRetryingUntilTheServerAnswers() throws Exception {
		failuresLeft.set(2); // The server is "down" for the first two requests.
		File film = tone(tempDir.resolve("film.wav"), 20);
		SubtitleTrack track = new SubtitleTrack();
		SubtitleFetcher fetcher = new SubtitleFetcher(film, client, track, () -> 0L, () -> true);
		fetcher.start();
		try {
			waitFor(() -> track.size() == 1);
		} finally {
			fetcher.stop();
		}
		assertEquals(3, uploadSizes.size());
	}

	@Test
	void theSameClipAlwaysGivesTheSameBytesSoTheServerCacheCanMatchIt() throws Exception {
		File film = tone(tempDir.resolve("film.wav"), 75);
		AudioClipExtractor extractor = new AudioClipExtractor(film);

		AudioClipExtractor.Clip first = extractor.extract(30_000_000L, 62_000_000L);
		extractor.extract(0, 32_000_000L); // Reading another clip in between must not change anything.
		AudioClipExtractor.Clip again = extractor.extract(30_000_000L, 62_000_000L);

		assertArrayEquals(first.wav(), again.wav());
		assertTrue(Math.abs(first.startUs() - 30_000_000L) < 50_000, "clip starts at 30s, not " + first.startUs());
	}

	private interface Condition {
		boolean met();
	}

	private static void waitFor(Condition condition) throws InterruptedException {
		long deadline = System.nanoTime() + 20_000_000_000L;
		while (!condition.met()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("timed out");
			}
			Thread.sleep(20);
		}
	}

	// A 44.1 kHz stereo WAV with a 440 Hz tone, standing in for a film's soundtrack.
	private static File tone(Path path, int seconds) throws IOException {
		return writeWav(path, seconds, 8_000);
	}

	private static File silence(Path path, int seconds) throws IOException {
		return writeWav(path, seconds, 0);
	}

	private static File writeWav(Path path, int seconds, int amplitude) throws IOException {
		int frames = RATE * seconds;
		ByteBuffer data = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN);
		data.put("RIFF".getBytes()).putInt(36 + frames * 4).put("WAVE".getBytes());
		data.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 2).putInt(RATE)
				.putInt(RATE * 4).putShort((short) 4).putShort((short) 16);
		data.put("data".getBytes()).putInt(frames * 4);
		for (int i = 0; i < frames; i++) {
			short sample = (short) (amplitude * Math.sin(2 * Math.PI * 440 * i / RATE));
			data.putShort(sample).putShort(sample);
		}
		Files.write(path, data.array());
		return path.toFile();
	}
}

