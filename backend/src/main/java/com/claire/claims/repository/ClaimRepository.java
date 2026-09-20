package com.claire.claims.repository;

import com.claire.claims.domain.Claim;
import com.claire.claims.domain.ClaimStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface ClaimRepository extends JpaRepository<Claim, Long>,
                                         JpaSpecificationExecutor<Claim> {

    /**
     * Overridden purely to attach an entity graph: without it the claims list
     * fires one extra query per row for patient, provider and payer.
     */
    @Override
    @EntityGraph(attributePaths = {"patient", "provider", "payer"})
    Page<Claim> findAll(Specification<Claim> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "provider", "payer", "policy"})
    Optional<Claim> findWithReferencesById(Long id);

    Optional<Claim> findByClaimNumber(String claimNumber);

    boolean existsByClaimNumber(String claimNumber);

    /**
     * Status counts and money totals in one pass, for the dashboard.
     * Returns rows of [status, count, totalCharge, paidAmount].
     */
    @Query("""
           SELECT c.status, COUNT(c), COALESCE(SUM(c.totalCharge), 0), COALESCE(SUM(c.paidAmount), 0)
           FROM Claim c
           GROUP BY c.status
           """)
    List<Object[]> summaryByStatus();

    /**
     * Same shape as summaryByStatus() but bounded to claims filed within a
     * half-open [from, to) window on created_at, for period reporting.
     * Returns rows of [status, count, totalCharge, paidAmount].
     */
    @Query("""
           SELECT c.status, COUNT(c), COALESCE(SUM(c.totalCharge), 0), COALESCE(SUM(c.paidAmount), 0)
           FROM Claim c
           WHERE c.createdAt >= :from AND c.createdAt < :to
           GROUP BY c.status
           """)
    List<Object[]> reportRowsByStatus(@Param("from") OffsetDateTime from,
                                      @Param("to") OffsetDateTime to);

    @Query("SELECT COALESCE(SUM(c.totalCharge - c.paidAmount), 0) FROM Claim c WHERE c.status NOT IN :closed")
    BigDecimal outstandingReceivable(@Param("closed") List<ClaimStatus> closed);

    /** Highest sequence number issued this year, used to mint the next claim number. */
    @Query("SELECT MAX(c.claimNumber) FROM Claim c WHERE c.claimNumber LIKE CONCAT('CLM-', :year, '-%')")
    Optional<String> maxClaimNumberForYear(@Param("year") String year);
}
