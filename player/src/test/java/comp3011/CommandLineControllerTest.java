/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Authors:
 *   1. Kimchhay Leng (a2942784)
 *
 * Copyright 2026 Kimchhay Leng
 */
package comp3011;

import static comp3011.FrameProcessorOption.BLACK_AND_WHITE;
import static comp3011.FrameProcessorOption.BLEED_FRAMES;
import static comp3011.FrameProcessorOption.DUST_FRAMES;
import static comp3011.FrameProcessorOption.FLICKER_FRAMES;
import static comp3011.FrameProcessorOption.JITTER_FRAMES;
import static comp3011.FrameProcessorOption.MOTTLE_FRAMES;
import static comp3011.FrameProcessorOption.NUMBER_FRAMES;
import static comp3011.FrameProcessorOption.PEPPER_FRAMES;
import static comp3011.FrameProcessorOption.SCRATCH_FRAMES;
import static comp3011.FrameProcessorOption.VIGNETTE_FRAMES;
import static comp3011.FrameProcessorOption.YELLOW_FRAMES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the command-line parsing in {@link CommandLineController} and the
 * effect lookup and help text in {@link FrameProcessorOption}.
 */
class CommandLineControllerTest {
    private static final List<FrameProcessorOption> SPEC_EXAMPLE_ORDER = List.of(
            JITTER_FRAMES, FLICKER_FRAMES, DUST_FRAMES, DUST_FRAMES, NUMBER_FRAMES);

    @TempDir
    Path tempDir;

    // CommandLineController checks the video file exists, so each test gets a real (empty) one.
    private String videoPath;

    @BeforeEach
    void createVideoFile() throws IOException {
        videoPath = Files.createFile(tempDir.resolve("movie.mp4")).toString();
    }

    private CommandLineController parse(String... args) {
        return new CommandLineController(args);
    }

    @Test
    void longOptionsAreAppliedInOrder() {
        CommandLineController controller = parse("--jitter-frames", "--flicker-frames", "--dust-frames",
                "--dust-frames", "--number-frames", videoPath);
        assertEquals(SPEC_EXAMPLE_ORDER, controller.getFrameProcessorOptions());
        assertTrue(controller.shouldLaunchApplication());
    }

    @Test
    void separateShortOptionsAreAppliedInOrder() {
        CommandLineController controller = parse("-j", "-f", "-d", "-d", "-n", videoPath);
        assertEquals(SPEC_EXAMPLE_ORDER, controller.getFrameProcessorOptions());
    }

    @Test
    void stackedShortOptionsAreAppliedInOrder() {
        CommandLineController controller = parse("-jfddn", videoPath);
        assertEquals(SPEC_EXAMPLE_ORDER, controller.getFrameProcessorOptions());
    }

    @Test
    void mixedLongShortAndStackedOptionsAreAppliedInOrder() {
        CommandLineController controller = parse("-jf", "--dust-frames", "-d", "--number-frames", videoPath);
        assertEquals(SPEC_EXAMPLE_ORDER, controller.getFrameProcessorOptions());
    }

    @Test
    void helpTextExampleGivesAllThirteenEffectsInOrder() {
        CommandLineController controller = parse("-nssnfwyvdjmbp", videoPath);
        assertEquals(List.of(NUMBER_FRAMES, SCRATCH_FRAMES, SCRATCH_FRAMES, NUMBER_FRAMES, FLICKER_FRAMES,
                BLACK_AND_WHITE, YELLOW_FRAMES, VIGNETTE_FRAMES, DUST_FRAMES, JITTER_FRAMES, MOTTLE_FRAMES,
                BLEED_FRAMES, PEPPER_FRAMES), controller.getFrameProcessorOptions());
    }

    @Test
    void noEffectOptionsGivesNoEffects() {
        CommandLineController controller = parse(videoPath);
        assertTrue(controller.getFrameProcessorOptions().isEmpty());
        assertTrue(controller.shouldLaunchApplication());
    }

    @Test
    void generalOptionsCanBeStackedWithEffects() {
        CommandLineController controller = parse("-axn1", videoPath);
        assertTrue(controller.isAudioRequested());
        assertTrue(controller.isMaximiseRequested());
        assertEquals(1, controller.getDisplayId());
        assertEquals(List.of(NUMBER_FRAMES), controller.getFrameProcessorOptions());
    }

    @Test
    void generalLongOptionsStillWork() {
        CommandLineController controller = parse("--audio", "--maximise", "--monitor-2", videoPath);
        assertTrue(controller.isAudioRequested());
        assertTrue(controller.isMaximiseRequested());
        assertEquals(2, controller.getDisplayId());
    }

    @Test
    void unknownShortOptionIsRejected() {
        CommandLineController controller = parse("-nq", videoPath);
        assertEquals("Unknown option: -q", controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication());
        assertEquals(1, controller.getExitCode());
    }

    @Test
    void unknownLongOptionIsRejected() {
        CommandLineController controller = parse("--sparkle-frames", videoPath);
        assertEquals("Unknown option: --sparkle-frames", controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    void missingVideoFileIsRejected() {
        CommandLineController controller = parse("-n");
        assertEquals("No video file specified.", controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    void helpDoesNotLaunchOrReportAnError() {
        CommandLineController controller = parse("-h");
        assertFalse(controller.shouldLaunchApplication());
        assertEquals(0, controller.getExitCode());
    }

    @Test
    void everyOptionCanBeFoundByItsShortAndLongName() {
        for (FrameProcessorOption option : FrameProcessorOption.values()) {
            assertEquals(option, FrameProcessorOption.fromShortName(option.getShortName()).orElseThrow());
            assertEquals(option, FrameProcessorOption.fromLongName(option.getLongName()).orElseThrow());
        }
    }

    @Test
    void helpLinesMatchTheSpecification() {
        assertEquals("  -n, --number-frames    Render the frame number onto each frame",
                NUMBER_FRAMES.toHelpLine());
        assertEquals("  -w, --black-and-white  Convert frames to black and white",
                BLACK_AND_WHITE.toHelpLine());
        assertEquals("  -b, --bleed-frames     Bleed light into frames",
                BLEED_FRAMES.toHelpLine());
    }

    @Test
    void helpTextMatchesTheSpecification() {
        String expected = String.join(System.lineSeparator(),
                "Usage: VideoPlayer [options] [video-file]",
                "",
                "Options:",
                "  -h, --help         Show this help message",
                "  -a, --audio        Play audio",
                "  -x, --maximise     Open the player maximised",
                "  -1, --monitor-1    Open the player on display 1",
                "  -2, --monitor-2    Open the player on display 2",
                "",
                "Frame processors:",
                "  -n, --number-frames    Render the frame number onto each frame",
                "  -s, --scratch-frames   Render vertical film scratches",
                "  -f, --flicker-frames   Randomly dim frames",
                "  -w, --black-and-white  Convert frames to black and white",
                "  -y, --yellow-frames    Apply a warmer colour temperature",
                "  -v, --vignette-frames  Darken the frame edges",
                "  -d, --dust-frames      Render dust and hair marks",
                "  -j, --jitter-frames    Randomly displace frames by a few pixels",
                "  -m, --mottle-frames    Add cloudy emulsion mottling",
                "  -b, --bleed-frames     Bleed light into frames",
                "  -p, --pepper-frames    Pepper frames with dark spots/blotches",
                "",
                "Frame processors are applied in command-line order and may be repeated.",
                "Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,",
                "converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.",
                "");

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true));
            parse("--help");
        } finally {
            System.setOut(originalOut);
        }
        assertEquals(expected, captured.toString());
    }

    @Test
    void repeatedOptionCreatesIndependentProcessors() {
        assertNotSame(SCRATCH_FRAMES.createProcessor(), SCRATCH_FRAMES.createProcessor());
    }
}