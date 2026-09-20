package com.claire.claims.web;

import com.claire.claims.dto.AppealDtos.AppealResponse;
import com.claire.claims.dto.AppealDtos.FileAppealRequest;
import com.claire.claims.dto.AppealDtos.RecordOutcomeRequest;
import com.claire.claims.service.AppealService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/**
 * The appeals lifecycle on a denied claim.
 *
 * Filing an appeal and recording its outcome are billing actions (ADMIN or
 * BILLER); reading the appeals on a claim is open to any authenticated user,
 * including VIEWER, matching the read-only worklist. Every appeal is addressed
 * through its claim, so the query is scoped to that claim id.
 */
@RestController
@RequestMapping("/api/claims/{claimId}/appeals")
public class AppealController {

    private final AppealService appeals;

    public AppealController(AppealService appeals) {
        this.appeals = appeals;
    }

    @GetMapping
    public List<AppealResponse> list(@PathVariable Long claimId) {
        return appeals.appealsFor(claimId);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','BILLER')")
    public ResponseEntity<AppealResponse> file(@PathVariable Long claimId,
                                               @Valid @RequestBody FileAppealRequest request) {
        AppealResponse created = appeals.file(claimId, request);
        return ResponseEntity
                .created(URI.create("/api/claims/" + claimId + "/appeals/" + created.id()))
                .body(created);
    }

    @PostMapping("/outcome")
    @PreAuthorize("hasAnyRole('ADMIN','BILLER')")
    public AppealResponse recordOutcome(@PathVariable Long claimId,
                                        @Valid @RequestBody RecordOutcomeRequest request) {
        return appeals.recordOutcome(claimId, request);
    }
}
