package com.claire.claims.repository;

import com.claire.claims.domain.ClaimStatus;
import com.claire.claims.domain.ClaimStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ClaimStatusHistoryRepository extends JpaRepository<ClaimStatusHistory, Long> {
    List<ClaimStatusHistory> findByClaimIdOrderByChangedAtAsc(Long claimId);

    /**
     * The most recent transition INTO a given status, for each of several
     * claims, as rows of [claimId, changedAt]. Used by the denial worklist to
     * establish each claim's denial date (the last time it entered DENIED) in
     * one query rather than one per claim. Empty id list returns nothing.
     */
    @Query("""
           SELECT h.claim.id, MAX(h.changedAt)
           FROM ClaimStatusHistory h
           WHERE h.toStatus = :status AND h.claim.id IN :claimIds
           GROUP BY h.claim.id
           """)
    List<Object[]> latestTransitionInto(@Param("status") ClaimStatus status,
                                        @Param("claimIds") List<Long> claimIds);
}
