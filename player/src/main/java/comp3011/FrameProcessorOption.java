/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Authors:
 *   1. Kimchhay Leng (a2942784)
 *
 * Copyright 2026 Kimchhay Leng
 */
package comp3011;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The frame processing effects that can be requested on the video player's command line.
 * 
 * Each option binds a single-letter short name (e.g. {@code -s}) and a long name
 * (e.g. {@code -- scratch-frames}) to a factory for the {@link FrameProcessor} that
 * implements the effect. {@link CommandLineController} looks options up by name with
 * {@link #fromShortName(char)} and {@link #fromLongName(String)}, and the player calls
 * {@link #createProcessor()} once per occurence on the command line, so a repeated
 * option (e.g. {@code -ss}) yields independent processor instances.
 * 
 * Adding a new effect to the player only requires adding a new constant here; 
 * the command-line parsing and help text are driven from this list.
 */

public enum FrameProcessorOption {
	NUMBER_FRAMES('n', "number-frames", "Render the frame number onto each frame",
			FrameNumberer::new),
	SCRATCH_FRAMES('s', "scratch-frames", "Render vertical film scratches", FrameScratcher::new),
	FLICKER_FRAMES('f', "flicker-frames", "Randomly dim frames", FrameFlickerer::new),
	BLACK_AND_WHITE('w', "black-and-white", "Convert frames to black and white",
			FrameBlackAndWhiter::new),
	YELLOW_FRAMES('y', "yellow-frames", "Apply a warmer colour temperature", FrameYellower::new),
	VIGNETTE_FRAMES('v', "vignette-frames", "Darken the frame edges", FrameVignetter::new),
	DUST_FRAMES('d', "dust-frames", "Render dust and hair marks", FrameDuster::new),
	JITTER_FRAMES('j', "jitter-frames", "Randomly displace frames by a few pixels",
			FrameJitterer::new),
	MOTTLE_FRAMES('m', "mottle-frames", "Add cloudy emulsion mottling", FrameMottler::new),
	BLEED_FRAMES('b', "bleed-frames", "Bleed light into frames", FrameBleeder::new),
	PEPPER_FRAMES('p', "pepper-frames", "Pepper frames with dark spots/blotches",
			FramePepperer::new);
	
	// Width the "--long-name" column is padded to so descriptions line up in the help text.
	private static final int LONG_NAME_COLUMN_WIDTH = 17;
	
	private final char shortName;
	private final String longName;
	private final String description;
	private final Supplier<FrameProcessor> factory;
	
	FrameProcessorOption(char shortName, String longName, String description,
			Supplier<FrameProcessor> factory){
		this.shortName = shortName;
		this.longName = longName;
		this.description = description;
		this.factory = factory;
	}
	
	public char getShortName() {
		return shortName;
	}
	
	public String getLongName() {
		return longName;
	}
	
	public String getDescription() {
		return description;
	}
	
	/**
	 * Creates a new, independent instance of this option's frame processor.
	 */
	public FrameProcessor createProcessor() {
		return factory.get();
	}
	
	/**
	 * Returns this option formatted as a line of the help text, e.g. 
	 * {@code " -n, --number-frames  Render the frame number onto each frame"}.
	 */
	public String toHelpLine() {
		return String.format("  -%c, --%-" + LONG_NAME_COLUMN_WIDTH + "s%s",  shortName, longName,
				description);
	}
	
	/**
	 * Finds the option with the given short name (the letter after a single '-').
	 */
	public static Optional<FrameProcessorOption> fromShortName(char shortName){
		for (FrameProcessorOption option: values()) {
			if (option.shortName == shortName) {
				return Optional.of(option);
			}
		}
		return Optional.empty();
	}
	
	/**
	 *  Finds the option with the given long name (the text after '--'). 
	 */
	public static Optional<FrameProcessorOption> fromLongName(String longName){
		for (FrameProcessorOption option : values()) {
			if (option.longName.equals(longName)) {
				return Optional.of(option);
			}
		}
		return Optional.empty();
	}
}
	