package comp3011.assignment1.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the HTTP client used to call the Cloud STT provider.
 *
 * The injected {@link RestClient.Builder} is the one Spring Boot auto-configures, which
 * carries Boot's own Jackson and message-converter customisation. Building a fresh
 * {@code RestClient.builder()} here instead would silently lose that and risk subtly different
 * JSON handling than the rest of the application.
 *
 * This bean holds no request-time state and knows nothing about the API key; attaching the
 * bearer token is the transcription service's responsibility, keeping this class a pure piece of
 * framework wiring.
 */
@Configuration
@EnableConfigurationProperties(OpenAiProperties.class)
public class OpenAiClientConfig {

    @Bean
    public RestClient openAiRestClient(RestClient.Builder builder, OpenAiProperties properties) {
    		return build(builder, properties.baseUrl(), Duration.ofSeconds(properties.timeoutSeconds()));
    }
    
    /**
     * A second client for the subtitle endpoint's Whisper calls. Same provider, longer timeout:
     * a verbose transcription with word timestamps takes noticeably longer than a plain one.
     */
    @Bean
    public RestClient whisperRestClient(
    		RestClient.Builder builder, OpenAiProperties properties, WhisperProperties whisper) {
    		return build(builder, properties.baseUrl(), Duration.ofSeconds(whisper.timeoutSeconds()));
    }
    
    private RestClient build(RestClient.Builder builder, String baseUrl, Duration timeout) {
    		HttpClient httpClient = HttpClient.newBuilder()
    				.connectTimeout(timeout)
    				.build();
    		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    		requestFactory.setReadTimeout(timeout);
    		
    		return builder
    				.clone()
    				.baseUrl(baseUrl)
    				.requestFactory(requestFactory)
    				.build();
    }
}