package com.claire.claims.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * An appeal filed against a denied claim.
 *
 * The deadline is stamped once, at filing, from the payer's appeal window and
 * is never recomputed - the window is unforgiving. Escalation runs level 1
 * (reconsideration) -> 2 (formal) -> 3 (external). At most one appeal is OPEN
 * per claim at a time. A mistaken filing is WITHDRAWN, not deleted.
 *
 * The outcome recorded here is separate from the claim's status: the status
 * change is driven through {@link ClaimStatusMachine}, which writes the audit
 * row. This row never mutates the claim status directly.
 */
@Entity
@Table(name = "claim_appeal")
public class ClaimAppeal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claim_id", nullable = false)
    private Claim claim;

    /** Escalation level: 1 reconsideration, 2 formal, 3 external. */
    @Column(nullable = false)
    private int level;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AppealStatus status = AppealStatus.OPEN;

    /** The date the appeal was filed. */
    @Column(name = "filed_on", nullable = false)
    private LocalDate filedOn;

    /** Filing deadline, stamped once from the payer window. Never recomputed. */
    @Column(nullable = false)
    private LocalDate deadline;

    @Column(length = 2000)
    private String narrative;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AppealOutcome outcome;

    @Column(name = "decided_on")
    private LocalDate decidedOn;

    @Column(name = "filed_by", nullable = false, length = 60)
    private String filedBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = OffsetDateTime.now(); }

    public ClaimAppeal() { }

    public ClaimAppeal(Claim claim, int level, LocalDate filedOn, LocalDate deadline,
                       String narrative, String filedBy) {
        this.claim = claim;
        this.level = level;
        this.status = AppealStatus.OPEN;
        this.filedOn = filedOn;
        this.deadline = deadline;
        this.narrative = narrative;
        this.filedBy = filedBy;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    /** True when the appeal has neither been decided nor withdrawn. */
    @Transient
    public boolean isOpen() {
        return status == AppealStatus.OPEN;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Claim getClaim() { return claim; }
    public void setClaim(Claim claim) { this.claim = claim; }
    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }
    public AppealStatus getStatus() { return status; }
    public void setStatus(AppealStatus status) { this.status = status; }
    public LocalDate getFiledOn() { return filedOn; }
    public void setFiledOn(LocalDate filedOn) { this.filedOn = filedOn; }
    public LocalDate getDeadline() { return deadline; }
    public void setDeadline(LocalDate deadline) { this.deadline = deadline; }
    public String getNarrative() { return narrative; }
    public void setNarrative(String narrative) { this.narrative = narrative; }
    public AppealOutcome getOutcome() { return outcome; }
    public void setOutcome(AppealOutcome outcome) { this.outcome = outcome; }
    public LocalDate getDecidedOn() { return decidedOn; }
    public void setDecidedOn(LocalDate decidedOn) { this.decidedOn = decidedOn; }
    public String getFiledBy() { return filedBy; }
    public void setFiledBy(String filedBy) { this.filedBy = filedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
