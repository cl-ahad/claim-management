package com.claire.claims.dto;

import com.claire.claims.domain.DenialGroupCode;
import com.claire.claims.domain.DenialSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public final class DenialDtos {

    private DenialDtos() { }

    // ----- write model ----------------------------------------------------

    /**
     * A structured denial reason. The group code and CARC reason code are both
     * required - a reason with only free text is rejected (400), because free
     * text alone is not a structured reason. The RARC remark code is optional.
     *
     * source is optional and defaults to MANUAL: this endpoint is how a biller
     * keys a reason by hand. Automated (REMITTANCE) reasons are posted by the
     * 835 importer, not here.
     */
    public record DenialReasonRequest(
            @NotNull(message = "groupCode is required (CO, PR, OA or PI)")
            DenialGroupCode groupCode,
            @NotBlank(message = "carcCode is required; a structured denial reason needs a CARC code")
            @Pattern(regexp = "^[0-9A-Za-z]{1,10}$",
                     message = "carcCode must be 1-10 alphanumeric characters, e.g. 197")
            String carcCode,
            @Pattern(regexp = "^[0-9A-Za-z]{1,10}$",
                     message = "rarcCode must be 1-10 alphanumeric characters, e.g. N130")
            String rarcCode,
            DenialSource source,
            @Size(max = 500) String note) { }

    // ----- read model -----------------------------------------------------

    public record DenialReasonResponse(
            Long id,
            Long claimId,
            DenialGroupCode groupCode,
            String carcCode,
            String rarcCode,
            DenialSource source,
            String note,
            String createdBy,
            OffsetDateTime createdAt) { }
}
