package comp3011.assignment1.model;

/*
* @param utcServerStart      UTC timestamp at which the server process started (RFC 3339)
* @param utcNow              UTC timestamp at response generation time (RFC 3339)
* @param serverUptimeSeconds fractional seconds between utcServerStart and utcNow
*/

public record UptimeResponse(String utcServerStart, String utcNow, double serverUptimeSeconds) {

}
