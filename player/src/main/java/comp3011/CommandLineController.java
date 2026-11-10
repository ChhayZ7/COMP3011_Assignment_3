/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Kimchhay Leng (a2942784)
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Optional;

/**
 * Created in the VideoPlayerApp's main function to receive and parse the video player's command-line arguments.
 *
 * <p>
 * Extracts the video file and supported launch options, reports invalid input
 * or help text, and exposes the resulting launch configuration via methods to the
 * {@link VideoPlayerApp}.
 * </p>
 */
public class CommandLineController {
	private static final String LONG_OPTION_PREFIX = "--";
	private static final String SHORT_OPTION_PREFIX = "-";

    private final String[] args;

    private boolean helpRequested;
    private boolean audioRequested;
    private boolean maximiseRequested;
    private Integer displayId;
    private File videoFile;
    private String errorMessage;
    private final List<FrameProcessorOption> frameProcessorOptions = new ArrayList<>();

    public CommandLineController(String[] args) {
        this.args = args.clone();
        parse();
        if (errorMessage != null) {
            System.out.println(errorMessage);
        }
        if (helpRequested) {
            printHelp();
        }
    }

    public File getVideoFile() {
        return videoFile;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getExitCode() {
        return errorMessage == null ? 0 : 1;
    }

    public Integer getDisplayId() {
        return displayId;
    }

    public boolean isAudioRequested() {
        return audioRequested;
    }

    public boolean isMaximiseRequested() {
        return maximiseRequested;
    }
    
	/**
	 * Returns the frame processor options in the oder they were given on the
	 * command line, including repeats.
	 */
    public List<FrameProcessorOption> getFrameProcessorOptions(){
    		return Collections.unmodifiableList(frameProcessorOptions);
    }

    public boolean shouldLaunchApplication() {
        return errorMessage == null && videoFile != null;
    }

    private void parse() {
        List<String> videoFiles = new ArrayList<>();
        for (String arg : args) {
        		if (arg.startsWith(LONG_OPTION_PREFIX)) {
        			parseLongOption(arg);
        		} else if (arg.startsWith(SHORT_OPTION_PREFIX) && arg.length() > 1) {
        			parseShortOptions(arg);
        		} else if (arg.startsWith(SHORT_OPTION_PREFIX)) {
        			errorMessage = "Unknown option: " + arg;
        		} else {
        			videoFiles.add(arg);
        		}
        }

        if (videoFiles.size() > 1) {
            errorMessage = "Usage: VideoPlayer [options] [video-file]";
        } else if (videoFiles.size() == 1) {
            videoFile = new File(videoFiles.get(0));
            if (!videoFile.isFile()) {
                errorMessage = "File not found: " + videoFile.getPath();
                videoFile = null;
            }
        } else {
            if (!helpRequested) {
                errorMessage = "No video file specified.";
            }
        }
    }
    
    // Handles a single "--name" argument.
    private void parseLongOption(String arg) {
    		String name = arg.substring(LONG_OPTION_PREFIX.length());
    		switch (name) {
    			case "help" -> helpRequested = true;
    			case "audio" -> audioRequested = true;
    			case "maximise" -> maximiseRequested = true;
    			case "monitor-1" -> setDisplayId(1);
    			case "monitor-2" -> setDisplayId(2);
    			default -> addFrameProcessorOption(FrameProcessorOption.fromLongName(name), arg);
    			
    		}
    }
    
    // Handles a "-abc" argument, treating each letter as its own short option.
    private void parseShortOptions(String arg) {
    		for(char letter: arg.substring(SHORT_OPTION_PREFIX.length()).toCharArray()) {
    			parseShortOption(letter);
    		}
    }
    
    private void parseShortOption(char letter) {
    		switch (letter) {
    			case 'h' -> helpRequested = true;
    			case 'a' -> audioRequested = true;
    			case 'x' -> maximiseRequested = true;
    			case '1' -> setDisplayId(1);
    			case '2' -> setDisplayId(2);
    			default -> addFrameProcessorOption(FrameProcessorOption.fromShortName(letter),
    					SHORT_OPTION_PREFIX + letter);
    		}
    }
    
    private void addFrameProcessorOption(Optional<FrameProcessorOption> option, String optionText) {
    		if (option.isPresent()) {
    			frameProcessorOptions.add(option.get());
    		} else {
    			errorMessage = "Unknown option: " + optionText;
    		}
    }

    private void setDisplayId(int displayId) {
        if (this.displayId != null && this.displayId != displayId) {
            errorMessage = "Only one display option can be used";
            return;
        }
        this.displayId = displayId;
    }

    private void printHelp() {
        System.out.println("Usage: VideoPlayer [options] [video-file]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -h, --help         Show this help message");
        System.out.println("  -a, --audio        Play audio");
        System.out.println("  -x, --maximise     Open the player maximised");
        System.out.println("  -1, --monitor-1    Open the player on display 1");
        System.out.println("  -2, --monitor-2    Open the player on display 2");
        System.out.println();
        System.out.println("Frame processors:");
        for (FrameProcessorOption option : FrameProcessorOption.values()) {
        		System.out.println(option.toHelpLine());
        }
        System.out.println();
        System.out.println("Frame processors are applied in command-line order and may be repeated.");
        System.out.println("Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,");
        System.out.println("converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.");
    }
}
