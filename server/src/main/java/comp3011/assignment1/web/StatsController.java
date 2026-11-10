package comp3011.assignment1.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import comp3011.assignment1.model.GlobalStatsResponse;
import comp3011.assignment1.service.StatsService;

/*
 * Global statistics endpoint from the YAML specification.
 */
@RestController
@RequestMapping("/api/v1/global")
public class StatsController {
	private final StatsService statsService;
	
	public StatsController(StatsService statsService) {
		this.statsService = statsService;
	}
	
	// GET /api/v1/global/stats
	@GetMapping("/stats")
	public GlobalStatsResponse getGlobalStats() {
		return statsService.snapshot();
	}
}
