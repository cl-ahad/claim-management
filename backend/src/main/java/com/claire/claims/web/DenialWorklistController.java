package com.claire.claims.web;

import com.claire.claims.dto.DenialDtos.DenialWorklistItem;
import com.claire.claims.service.DenialWorklistService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * The denial worklist.
 *
 * Reading the worklist is open to any authenticated user, including VIEWER -
 * it is the read-only screen a biller lives in, and there is deliberately no
 * @PreAuthorize here (matching the claims list). Write actions on denials and
 * appeals are ADMIN/BILLER and live on their own endpoints, so a VIEWER can
 * see this list but any write returns 403.
 *
 * All filters are optional and combine with AND. Results are sorted by appeal
 * deadline ascending; expired claims stay on the list, marked expired.
 */
@RestController
@RequestMapping("/api/denials")
public class DenialWorklistController {

    private final DenialWorklistService worklist;

    public DenialWorklistController(DenialWorklistService worklist) {
        this.worklist = worklist;
    }

    @GetMapping
    public List<DenialWorklistItem> list(
            @RequestParam(required = false) Long payerId,
            @RequestParam(required = false) String reasonCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return worklist.worklist(payerId, reasonCode, from, to);
    }
}
