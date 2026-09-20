package com.claire.claims.repository;

import com.claire.claims.domain.DenialReason;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/**
 * Denial reasons are always addressed through their claim, never in the
 * global set - every query is scoped by claim id. There is no tenant column
 * in this schema; scoping by the addressed claim plus method-level RBAC is
 * how ownership is enforced (see repo notes on tenancy).
 */
public interface DenialReasonRepository extends JpaRepository<DenialReason, Long> {

    List<DenialReason> findByClaimIdOrderByCreatedAtAsc(Long claimId);

    /**
     * Reasons for several claims at once, ordered so a claim's rows stay in
     * capture order - lets the worklist enrich a page of claims without firing
     * a query per row. Empty id list returns nothing.
     */
    List<DenialReason> findByClaimIdInOrderByClaimIdAscCreatedAtAsc(List<Long> claimIds);

    boolean existsByClaimId(Long claimId);
}
