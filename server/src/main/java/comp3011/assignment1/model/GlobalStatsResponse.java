package comp3011.assignment1.model;

/**
 * Response body for GET /api/v1/global/stats.
 *
 * @param inputTokens  total input tokens consumed since UTC server start
 * @param outputTokens total output tokens produced since UTC server start
 */

public record GlobalStatsResponse(long inputTokens, long outputTokens) {

}
