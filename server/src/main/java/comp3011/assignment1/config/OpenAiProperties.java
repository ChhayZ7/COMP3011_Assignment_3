package comp3011.assignment1.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code stt.openai.*} properties declared in {@code application.properties}.
 *
 * Grouping these into one immutable record, rather than scattering {@code @Value} annotations
 * across the service layer, means the full set of external configuration this application depends
 * on is visible in one place, and typo'd property names fail fast at startup instead of silently
 * resolving to null at call time.
 *
 * @param apiKey         bearer token for the Cloud STT provider, read from {@code OPENAI_API_KEY}
 *                       at runtime; never hold this in any field or log statement beyond here and
 *                       the single request that uses it
 * @param baseUrl        scheme and host of the Cloud STT provider
 * @param model          model identifier passed to the provider on every request
 * @param timeoutSeconds connect and read timeout applied to every provider call
 */
@ConfigurationProperties(prefix = "stt.openai")
public record OpenAiProperties(String apiKey, String baseUrl, String model, int timeoutSeconds) {
}
