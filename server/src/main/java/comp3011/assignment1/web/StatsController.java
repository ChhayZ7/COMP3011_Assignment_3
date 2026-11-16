package comp3011.assignment1.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import comp3011.assignment1.model.GlobalStatsResponse;
import comp3011.assignment1.model.UsageResponse;
import comp3011.assignment1.service.StatsService;
import comp3011.assignment1.service.UsageService;

/*
 * Global statistics endpoint from the YAML specification.
 */
@RestController
@RequestMapping("/api/v1/global")
public class StatsController {
	private final StatsService statsService;
	private final UsageService usageService;
	
	public StatsController(StatsService statsService, UsageService usageService) {
		this.statsService = statsService;
		this.usageService = usageService;
	}
	
	// GET /api/v1/global/stats
	@GetMapping("/stats")
	public GlobalStatsResponse getGlobalStats() {
		return statsService.snapshot();
	}
	
	// GET /api/v1/global/usage: total seconds of audio billed by the transcription service.
	@GetMapping("/usage")
	public UsageResponse getGlobalUsage() {
		return new UsageResponse(usageService.totalSeconds());
	}
}
