package com.claire.claims.repository;

import com.claire.claims.domain.AppealStatus;
import com.claire.claims.domain.ClaimAppeal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Appeals are always addressed through their claim, never in the global set -
 * every query is scoped by claim id. This schema has no tenant column; scoping
 * by the addressed claim plus method-level RBAC is how ownership is enforced
 * (see repo notes on tenancy).
 */
public interface ClaimAppealRepository extends JpaRepository<ClaimAppeal, Long> {

    List<ClaimAppeal> findByClaimIdOrderByLevelAscFiledOnAsc(Long claimId);

    /**
     * Appeals for several claims at once, ordered so the last row for a claim
     * is its highest level - lets the worklist read each claim's current
     * (stamped) deadline without a query per row. Empty id list returns nothing.
     */
    List<ClaimAppeal> findByClaimIdInOrderByClaimIdAscLevelAsc(List<Long> claimIds);

    Optional<ClaimAppeal> findByClaimIdAndStatus(Long claimId, AppealStatus status);

    boolean existsByClaimIdAndStatus(Long claimId, AppealStatus status);
}
