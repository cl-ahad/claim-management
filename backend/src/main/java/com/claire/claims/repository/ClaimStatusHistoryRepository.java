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
     * The moment each claim first reached a terminal decision, for claims filed
     * within a half-open [from, to) window on the claim's created_at.
     * Returns rows of [claimId, firstTerminalChangedAt]. Because the history is
     * append-only, MIN(changed_at) over the terminal target statuses is the
     * decision time; joined against created_at it gives processing duration.
     * Claims still open have no such row and are excluded from the average.
     */
    @Query("""
           SELECT h.claim.id, MIN(h.changedAt)
           FROM ClaimStatusHistory h
           WHERE h.toStatus IN :terminal
             AND h.claim.createdAt >= :from AND h.claim.createdAt < :to
           GROUP BY h.claim.id
           """)
    List<Object[]> firstTerminalDecisionAt(@Param("terminal") List<ClaimStatus> terminal,
                                           @Param("from") OffsetDateTime from,
                                           @Param("to") OffsetDateTime to);
}
