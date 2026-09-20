package com.claire.claims.repository;

import com.claire.claims.domain.ClaimStatus;
import com.claire.claims.domain.ClaimStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.OffsetDateTime;
import java.util.List;

public interface ClaimStatusHistoryRepository extends JpaRepository<ClaimStatusHistory, Long> {
    List<ClaimStatusHistory> findByClaimIdOrderByChangedAtAsc(Long claimId);

    /**
     * For each claim filed within a half-open [from, to) window on the claim's
     * created_at, the moment it first reached a terminal decision alongside the
     * claim's created_at. Returns rows of [claimId, createdAt, firstTerminalChangedAt].
     * Because the history is append-only, MIN(changed_at) over the terminal target
     * statuses is the decision time; the difference from created_at is the
     * processing duration. Claims still open produce no row and are excluded
     * from the average. created_at is grouped alongside so no second query or
     * per-claim lookup is needed.
     */
    @Query("""
           SELECT h.claim.id, h.claim.createdAt, MIN(h.changedAt)
           FROM ClaimStatusHistory h
           WHERE h.toStatus IN :terminal
             AND h.claim.createdAt >= :from AND h.claim.createdAt < :to
           GROUP BY h.claim.id, h.claim.createdAt
           """)
    List<Object[]> firstTerminalDecisionAt(@Param("terminal") List<ClaimStatus> terminal,
                                           @Param("from") OffsetDateTime from,
                                           @Param("to") OffsetDateTime to);
}
