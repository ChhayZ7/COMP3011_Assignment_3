/*
 * Adelaide University COMP3011 Assignment 3.
 *
 * Authors:
 *   1. Kimchhay Leng (a2942784)
 *
 * Copyright 2026 Kimchhay Leng
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Checks that subtitles are drawn white with a black outline, near the bottom of the frame, and fit narrow frames.
 */
class SubtitleRendererTest {
	private static final int WIDTH = 640;
	private static final int HEIGHT = 360;
	private static final int CHANNELS = 3;
	private static final int GREY = 128;

	@BeforeAll
	static void headless() {
		System.setProperty("java.awt.headless", "true");
	}

	private static ByteBuffer greyFrame() {
		ByteBuffer pixels = ByteBuffer.allocate(WIDTH * HEIGHT * CHANNELS);
		for (int i = 0; i < pixels.capacity(); i++) {
			pixels.put(i, (byte) GREY);
		}
		return pixels;
	}

	@Test
	void drawsWhiteTextWithABlackOutlineNearTheBottom() {
		ByteBuffer pixels = greyFrame();
		SubtitleRenderer.Bitmap bitmap = SubtitleRenderer.render(List.of("Hello, world!"), WIDTH, HEIGHT);
		bitmap.blendOnto(pixels, WIDTH, HEIGHT, WIDTH * CHANNELS, CHANNELS);

		int white = 0;
		int black = 0;
		int changedInTopHalf = 0;
		for (int y = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				int value = pixels.get((y * WIDTH + x) * CHANNELS) & 0xff;
				if (value != GREY && y < HEIGHT / 2) {
					changedInTopHalf++;
				}
				if (value >= 250) {
					white++;
				} else if (value <= 5) {
					black++;
				}
			}
		}
		assertTrue(white > 100, "the letters are white");
		assertTrue(black > 100, "the letters have a black outline");
		assertEquals(0, changedInTopHalf, "the picture above the subtitles is untouched");
		assertTrue(bitmap.y() + bitmap.height() <= HEIGHT);
	}

	@Test
	void shrinksTextThatWouldNotFitANarrowFrame() {
		String longest = "This line is exactly forty-two characters.";
		assertEquals(42, longest.length());
		SubtitleRenderer.Bitmap bitmap = SubtitleRenderer.render(List.of(longest, longest), 320, 240);
		assertTrue(bitmap.width() <= 320 * 0.9 + 20, "the text fits inside the frame");
		assertTrue(bitmap.x() >= 0);
	}
}
