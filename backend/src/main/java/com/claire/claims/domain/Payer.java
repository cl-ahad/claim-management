package com.claire.claims.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "payer")
public class Payer {

    /**
     * Fallback appeal window, in days, when a payer carries no explicit value.
     *
     * OPEN DECISION: 90 days is a common commercial-payer figure used as a
     * placeholder. The production default must be confirmed by the business
     * before go-live, and payer-specific values keyed per payer, because the
     * deadline is unforgiving once stamped. Kept as a constant so there is one
     * place to change it and it matches the V4 migration's column default.
     */
    public static final int DEFAULT_APPEAL_WINDOW_DAYS = 90;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payer_code", nullable = false, unique = true, length = 20)
    private String payerCode;

    @Column(nullable = false, length = 160)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_type", nullable = false, length = 30)
    private PlanType planType;

    @Column(name = "claims_address", length = 255)
    private String claimsAddress;

    @Column(length = 30)
    private String phone;

    @Column(nullable = false)
    private boolean active = true;

    /**
     * Days a claim may be appealed after denial, for this payer. Stamped onto
     * an appeal's deadline at filing and never recomputed. Defaults to
     * {@link #DEFAULT_APPEAL_WINDOW_DAYS} at the database level.
     */
    @Column(name = "appeal_window_days", nullable = false)
    private int appealWindowDays = DEFAULT_APPEAL_WINDOW_DAYS;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = OffsetDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPayerCode() { return payerCode; }
    public void setPayerCode(String payerCode) { this.payerCode = payerCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public PlanType getPlanType() { return planType; }
    public void setPlanType(PlanType planType) { this.planType = planType; }
    public String getClaimsAddress() { return claimsAddress; }
    public void setClaimsAddress(String claimsAddress) { this.claimsAddress = claimsAddress; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public int getAppealWindowDays() { return appealWindowDays; }
    public void setAppealWindowDays(int appealWindowDays) { this.appealWindowDays = appealWindowDays; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
