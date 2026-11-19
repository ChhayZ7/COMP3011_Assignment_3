package comp3011.assignment1.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import comp3011.assignment1.model.SubtitleResponse;
import comp3011.assignment1.model.UsageResponse;

/**
 * HTTP-level tests for POST /api/v1/subtitles and GET /api/v1/global/usage against the stub.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("stub")
class SubtitleControllerTest {
    // 10 seconds of 16 kHz 16-bit mono audio plus a WAV header.
    private static final int TEN_SECONDS_OF_AUDIO = 44 + 10 * 16_000 * 2;

    @LocalServerPort
    private int port;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    @Test
    void returnsCuesThenServesTheSameClipFromCacheWithoutAddingUsage() {
        byte[] clip = new byte[TEN_SECONDS_OF_AUDIO];
        clip[100] = 42; // Make this clip's bytes, and so its cache key, unique to this test.

        double usageBefore = usage();
        ResponseEntity<SubtitleResponse> first = upload(clip);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().cached()).isFalse();
        assertThat(first.getBody().cues()).isNotEmpty();
        assertThat(first.getBody().cues().get(0).lines()).isNotEmpty();
        double usageAfterFirst = usage();
        assertThat(usageAfterFirst).isEqualTo(usageBefore + 10.0);

        ResponseEntity<SubtitleResponse> second = upload(clip);
        assertThat(second.getBody().cached()).isTrue();
        assertThat(second.getBody().cues()).isEqualTo(first.getBody().cues());
        assertThat(usage()).isEqualTo(usageAfterFirst);
    }

    @Test
    void emptyUploadIsRejected() {
        ResponseEntity<String> response = restTemplate.postForEntity(url("/api/v1/subtitles"),
                new HttpEntity<>(body(new byte[0]), headers()), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void usageIsASingleNumber() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/v1/global/usage"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).matches("\\{\"usage\":[0-9.]+\\}");
    }

    private ResponseEntity<SubtitleResponse> upload(byte[] clip) {
        return restTemplate.postForEntity(url("/api/v1/subtitles"), new HttpEntity<>(body(clip), headers()),
                SubtitleResponse.class);
    }

    private double usage() {
        return restTemplate.getForObject(url("/api/v1/global/usage"), UsageResponse.class).usage();
    }

    private static MultiValueMap<String, Object> body(byte[] clip) {
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType("audio/wav"));
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(new ByteArrayResource(clip) {
            @Override
            public String getFilename() {
                return "clip.wav";
            }
        }, partHeaders));
        return body;
    }

    private static HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return headers;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
