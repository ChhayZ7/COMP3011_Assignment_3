package comp3011.assignment1.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import comp3011.assignment1.model.UptimeResponse;
import comp3011.assignment1.model.ShutdownResponse;
import comp3011.assignment1.service.ServerLifecycleService;


/**
 * Administration endpoints from the YAML specification.
 *
 * The controller holds no mutable state of its own; all lifecycle state lives in
 * {@link ServerLifecycleService}. A stateless controller is safe to share across every
 * concurrent request without synchronisation.
 */

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
	private final ServerLifecycleService lifecycleService;
	
	// Constructor injection: the dependency is final, explicit, and trivially stubbed in tests.
	public AdminController(ServerLifecycleService lifecycleService) {
		this.lifecycleService = lifecycleService;
	}
	
	// Get /api/v1/admin/uptime
	@GetMapping("/uptime")
	public UptimeResponse getServerUptime() {
		return lifecycleService.currentUptime();
	}
	
    /**
     * POST /api/v1/admin/shutdown
     *
     * <p>Returns 202 Accepted immediately; the container is torn down shortly afterwards. A
     * duplicate request throws {@code ShutdownInProgressException}, which the global handler
     * renders as the 409 body defined in the specification.
     */
	@PostMapping("/shutdown")
	public ResponseEntity<ShutdownResponse> shutdownServer(){
		lifecycleService.requestShutdown();
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ShutdownResponse("Graceful shutdown requested."));
	}
}
