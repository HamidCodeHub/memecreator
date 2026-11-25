package com.memecreator.web;

import com.memecreator.harvest.HarvestReport;
import com.memecreator.harvest.TemplateHarvester;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Admin endpoints for the template harvester. Dev/testing for now; to be restricted to admins later. */
@Tag(name = "Template harvest", description = "Download new meme templates from public template APIs")
@RestController
@RequestMapping("/api/templates/harvest")
public class TemplateHarvestController {

    private final TemplateHarvester harvester;

    public TemplateHarvestController(TemplateHarvester harvester) {
        this.harvester = harvester;
    }

    @Operation(summary = "Run the harvest now",
            description = "Downloads what is new from one source (imgflip, memegen) or all of them, skipping "
                    + "duplicates, and waits until it is done. The first run of memegen takes a few minutes.")
    @PostMapping
    public ResponseEntity<?> run(
            @Parameter(description = "imgflip, memegen or all") @RequestParam(defaultValue = "all") String source) {
        try {
            return ResponseEntity.ok(harvester.run(source));
        } catch (TemplateHarvester.BusyException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage()));
        }
    }

    public record HarvestStatus(boolean running, List<String> sources, List<HarvestReport> lastRun) {
    }

    @Operation(summary = "Harvest status", description = "Whether a run is in progress and what the last run did.")
    @GetMapping
    public HarvestStatus status() {
        return new HarvestStatus(harvester.running(), harvester.sourceNames(), harvester.lastReports());
    }
}
