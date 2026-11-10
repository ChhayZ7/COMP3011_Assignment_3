/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Authors:
 *   1. Kimchhay Leng (a2942784)
 *
 * Copyright 2026 Kimchhay Leng
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

/**
 * Tests the {@link FramePool} that limits how many frames can be in the pipeline at once.
 */
public class FramePoolTest {
	
	@Test
	void acquireWaitsOnceEveryFrameIsInUse() throws InterruptedException {
		Frame template = new Frame(8, 4, Frame.DEPTH_UBYTE, 3);
		try (FramePool pool = new FramePool(2, template)){
			Frame first = pool.acquire(1,  TimeUnit.SECONDS);
			Frame second = pool.acquire(1, TimeUnit.SECONDS);
			assertNotNull(first);
			assertNotNull(second);
			assertNull(pool.acquire(10, TimeUnit.MILLISECONDS),"A full pool handed out a third frame");
			
			pool.release(first);
			assertSame(first, pool.acquire(1, TimeUnit.SECONDS), "A released frame was not reused");
		}
	}
	
	@Test
	void copyImageCopiesEveryPixel() {
		Frame source = new Frame(8, 4, Frame.DEPTH_UBYTE, 3);
		Frame target = new Frame(8, 4, Frame.DEPTH_UBYTE, 3, source.imageStride);
		ByteBuffer sourcePixels = (ByteBuffer) source.image[0];
		for (int i = 0; i < sourcePixels.capacity(); i++) {
			sourcePixels.put(i, (byte) i);
		}
		
		FramePool.copyImage(source, target);
		
		ByteBuffer targetPixels = (ByteBuffer) target.image[0];
		for (int i = 0; i < sourcePixels.capacity(); i++) {
			assertEquals(sourcePixels.get(i), targetPixels.get(i), "Pixel byte " + i + " differs");
		}
	}
}
