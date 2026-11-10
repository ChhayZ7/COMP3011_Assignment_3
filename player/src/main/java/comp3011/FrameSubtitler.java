/*
 * Adelaide University COMP3011 Assignment 3.
 *
 * Authors:
 *   1. Kimchhay Leng (a2942784)
 *
 * Copyright 2026 Kimchhay Leng
 */
package comp3011;

import java.nio.ByteBuffer;

import org.bytedeco.javacv.Frame;

/**
 * Frame processor for {@code -S, --subtitle-frames}: draws the subtitle due at each frame's film time onto the frame.
 *
 * <p>
 * Like every other effect it runs in its own {@link ProcessorStage} thread, and this object is only ever used by
 * that thread, so its small cache of the last drawn subtitle needs no locking. The subtitles themselves come from the
 * shared {@link SubtitleManager}, whose lookups never block.
 * </p>
 *
 * <p>
 * Subtitles are chosen by the frame's own timestamp, not by the wall clock. So they stay in step with the picture
 * after seeking, pausing or a slow frame, with no extra coordination, and they start and stop on exactly the frame
 * where the words are spoken.
 * </p>
 */
public class FrameSubtitler extends FrameProcessor {
	private SubtitleManager subtitles;
	
	// The last subtitle drawn and its bitmap, reused for every frame that shows the same subtitle.
	private SubtitleCue drawnCue;
	private SubtitleRenderer.Bitmap drawnBitmap;
	
	public FrameSubtitler() {
	}
	
	/**
	 * Connects this effect to the player's subtitles. Called once, before the pipeline thread that runs this effect
	 * is started, so that thread is guaranteed to see it.
	 */
	void useSubtitles(SubtitleManager subtitles) {
		this.subtitles = subtitles;
	}
	
	@Override
	public void initialise(InfoVideo videoInfo) throws Exception {
		super.initialise(videoInfo);
		drawnCue = null; // A new video may have a different size.
		drawnBitmap = null;
	}
	
	@Override
	public void process(java.awt.Frame frame, InfoFrame info) throws Exception {
		if (subtitles == null || !subtitles.isEnabled()) {
			return;
		}
		if (frame.image == null || frame.image.length == 0 || !(frame.image[0] instanceof ByteBuffer pixels)) {
			return;
		}
		if (videoInfo.imageDepth() != Frame.DEPTH_UBYTE && videoInfo.imageDepth() != Frame.DEPTH_BYTE) {
			return;
		}
		
		SubtitleCue cue = subtitles.cueAt(info.timestampUs());
		if (cue == null) {
			return;
		}
		if (cue != drawnCue) {
			drawnBitmap = SubtitleRenderer.render(cue.lines(), videoInfo.imageWidth(), videoInfo.imageHeight());
			drawnCue = cue;
		}
		drawnBitmap.blendOnto(pixels, videoInfo.imageWidth(), videoInfo.imageHeight(), videoInfo.imageStride(),
				videoInfo.imageChannels());
	}
}