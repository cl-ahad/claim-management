package com.claire.claims.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * A structured reason a claim was denied - an X12 835 Claim Adjustment triple
 * (group code + CARC reason code + optional RARC remark code) plus the source
 * it was captured from. A denial may carry several of these.
 *
 * Rows are kept, not mutated: an automated (REMITTANCE) reason takes precedence
 * over a manually keyed one, but the manual row is preserved for audit.
 */
@Entity
@Table(name = "denial_reason")
public class DenialReason {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claim_id", nullable = false)
    private Claim claim;

    @Enumerated(EnumType.STRING)
    @Column(name = "group_code", nullable = false, length = 2)
    private DenialGroupCode groupCode;

    @Column(name = "carc_code", nullable = false, length = 10)
    private String carcCode;

    @Column(name = "rarc_code", length = 10)
    private String rarcCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DenialSource source;

    @Column(length = 500)
    private String note;

    @Column(name = "created_by", nullable = false, length = 60)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public DenialReason() { }

    public DenialReason(Claim claim, DenialGroupCode groupCode, String carcCode,
                        String rarcCode, DenialSource source, String note, String createdBy) {
        this.claim = claim;
        this.groupCode = groupCode;
        this.carcCode = carcCode;
        this.rarcCode = rarcCode;
        this.source = source;
        this.note = note;
        this.createdBy = createdBy;
        this.createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Claim getClaim() { return claim; }
    public void setClaim(Claim claim) { this.claim = claim; }
    public DenialGroupCode getGroupCode() { return groupCode; }
    public void setGroupCode(DenialGroupCode groupCode) { this.groupCode = groupCode; }
    public String getCarcCode() { return carcCode; }
    public void setCarcCode(String carcCode) { this.carcCode = carcCode; }
    public String getRarcCode() { return rarcCode; }
    public void setRarcCode(String rarcCode) { this.rarcCode = rarcCode; }
    public DenialSource getSource() { return source; }
    public void setSource(DenialSource source) { this.source = source; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
