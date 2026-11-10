package comp3011.assignment1.error;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import comp3011.assignment1.model.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Central translation of exceptions into the ErrorResponse shape defined by the YAML
 * specification.
 *
 * Handling errors in one advice rather than per controller keeps controllers free of
 * try/catch noise and guarantees every failure path emits the same JSON contract.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    //A second shutdown request while one is already running is a conflict, not an error.
    @ExceptionHandler(ShutdownInProgressException.class)
    public ResponseEntity<ErrorResponse> handleShutdownInProgress(
            ShutdownInProgressException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    //The client sent no audio, or nothing usable. Retrying unchanged will not help.
    @ExceptionHandler(InvalidAudioUploadException.class)
    public ResponseEntity<ErrorResponse> handleInvalidUpload(
            InvalidAudioUploadException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    /**
     * The multipart form field is missing entirely, so the request never reaches the controller.
     *
     * This is distinct from {@code MissingServletRequestParameterException}, which applies to
     * ordinary request parameters. A {@code MultipartFile} argument declared with
     * {@code @RequestParam} is resolved by Spring's multipart machinery, which raises this
     * exception type instead when the named part is absent.
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingPart(
            MissingServletRequestPartException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST,
                "Required multipart field '" + ex.getRequestPartName() + "' is missing.",
                request);
    }

    /**
     * The request was never a valid multipart/form-data request in the first place — a wrong or
     * missing Content-Type header, or no body at all.
     *
     * Two distinct exception types land here because Spring can reject a mismatched request at
     * two different points: {@code HttpMediaTypeNotSupportedException} when the Content-Type
     * header doesn't match what the endpoint declares in {@code consumes}, or
     * {@code MultipartException} when content negotiation passes but the multipart parser itself
     * finds no genuine multipart body to parse. Both represent the same client mistake from this
     * application's point of view, so both produce the same response.
     */
    @ExceptionHandler({MultipartException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<ErrorResponse> handleNotMultipart(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST,
                "Expected a multipart/form-data request with a 'file' field.", request);
    }

    /**
     * The upload exceeded spring.servlet.multipart.max-file-size.
     *
     * Without this handler the client receives an opaque 500, which is misleading: the server
     * is fine, the request was too large. The limit is a deliberate defence against a single
     * client exhausting heap, so the rejection should say so plainly.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge(
            MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE,
                "The uploaded audio exceeds the maximum permitted size.", request);
    }

    /** Upstream STT failures surface to the client as 500 with a generic message. */
    @ExceptionHandler(TranscriptionException.class)
    public ResponseEntity<ErrorResponse> handleTranscriptionFailure(
            TranscriptionException ex, HttpServletRequest request) {
        log.error("Transcription failed for {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                "The speech-to-text service could not process the request.", request);
    }

    /**
     * Catch-all for anything unanticipated.
     *
     * The client is deliberately given a fixed message rather than {@code ex.getMessage()}.
     * Exception text originating from an HTTP client library can contain request details,
     * including authorisation headers, so echoing it would be a credential disclosure path.
     * The full stack trace is logged locally where it is useful and not client-visible.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception while processing {}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected server error occurred.", request);
    }

    private ResponseEntity<ErrorResponse> build(
            HttpStatus status, String message, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(
                DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS)),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}