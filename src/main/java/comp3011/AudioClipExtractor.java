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
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

/**
 * Cuts a clip of a film's original audio and packs it as a WAV file for the subtitle server.
 *
 * <p>
 * Each clip is read with a new grabber of its own, separate from the one the playback pipeline uses, so cutting
 * clips never disturbs playback. A new grabber per clip also means the same clip of the same film always gives
 * exactly the same bytes, whatever was read before it, which is what lets the server's cache recognise a clip that
 * another player has already sent. And if the media disappears (the USB stick is pulled), the next attempt simply
 * fails to open the file, and succeeds again once it is back.
 * </p>
 *
 * <p>
 * The audio is converted to 16 kHz, 16-bit mono, which is all speech recognition needs: a 30 second clip is about
 * 1 MB instead of about 5 MB at CD quality, so uploads are quicker and well under the server's 25 MB limit.
 * </p>
 */
final class AudioClipExtractor{
	static final int SAMPLE_RATE = 16_000;
	private static final int BYTES_PER_SAMPLE = 2;
	// Clips whose loudest sample is below this are treated as silence and not sent: there is nothing to subtitle,
	// it saves money, and Whisper tends to invent words for silence.
	private static final int SILENCE_PEAK = 200;
	
	/**
	 * A clip ready to upload.
	 *
	 * @param wav     the WAV file bytes, or null if the clip is silent or past the end of the audio
	 * @param startUs the film time of the clip's first sample
	 */
	record Clip(byte[] wav, long startUs) {
		boolean isEmpty() {
			return wav == null;
		}
	}
	
	private final File mediaFile;
	
	AudioClipExtractor(File mediaFile){
		this.mediaFile = mediaFile;
	}
	
	/*
	 * Facts about the film needed to plan clips, or throws if the file cannot be opened.
	 */
	record MediaInfo(boolean hasAudio, long durationUs) {	
	}
	
	MediaInfo probe() throws Exception{
		try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(mediaFile)){
			grabber.start();
			return new MediaInfo(grabber.hasAudio() && grabber.getAudioChannels() > 0, grabber.getLengthInTime());
		}
	}
	
	/** Reads the audio from {@code stratUs} up to {@code endUs}. */
	Clip extract(long startUs, long endUs) throws Exception{
		try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(mediaFile)){
			grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
			grabber.setSampleRate(SAMPLE_RATE); // FFmpeg resamples and mixes down for us.
			grabber.setAudioChannels(1);
			grabber.start();
			if (startUs > 0) {
				grabber.setTimestamp(startUs);
			}
			
			ByteArrayOutputStream pcm = new ByteArrayOutputStream();
			long clipStartUs = -1;
			long samplesWanted = Long.MAX_VALUE;
			long samplesWritten = 0;
			int peak = 0;
			
			Frame frame;
			while (samplesWritten < samplesWanted && (frame = grabber.grabSamples()) != null) {
				if (frame.samples == null || !(frame.samples[0] instanceof ShortBuffer buffer)) {
					continue;
				}
				long frameStartUs = grabber.getTimestamp();
				if (frameStartUs >= endUs) {
					break;
				}
				int skip = 0;
				if (clipStartUs < 0) {
					// Seeking lands on a frame boundary; drop any samples from before the requested start.
					skip = (int) Math.max(0, (startUs - frameStartUs) * SAMPLE_RATE / 1_000_000L);
					clipStartUs = frameStartUs + skip * 1_000_000L / SAMPLE_RATE;
					samplesWanted = (endUs - clipStartUs) * SAMPLE_RATE / 1_000_000L;
				}
				ShortBuffer samples = buffer.duplicate();
				samples.rewind();
				for(int i = skip; i < samples.limit() && samplesWritten < samplesWanted; i++) {
					short sample = samples.get(i);
					peak = Math.max(peak, Math.abs((int) sample));
					pcm.write(sample & 0xff);
					pcm.write((sample >> 8) & 0xff);
					samplesWritten++;
				}
			}
			
			if (samplesWritten == 0 || peak < SILENCE_PEAK) {
				return new Clip(null, Math.max(clipStartUs, startUs));
			}
			return new Clip(wav(pcm.toByteArray()), clipStartUs);
		}
	}
	
	// A standard 44 byte WAV header followed by little-endian 16-bit PCM.
	private static byte[] wav(byte[] pcm) {
		ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
		header.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes());
		header.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
				.putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * BYTES_PER_SAMPLE)
				.putShort((short) BYTES_PER_SAMPLE).putShort((short) 16);
		header.put("data".getBytes()).putInt(pcm.length);

		byte[] wav = new byte[44 + pcm.length];
		System.arraycopy(header.array(), 0, wav, 0, 44);
		System.arraycopy(pcm, 0, wav, 44, pcm.length);
		return wav;
	}
}