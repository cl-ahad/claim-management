package com.claire.claims.web;

import com.claire.claims.common.GlobalExceptionHandler;
import com.claire.claims.domain.ClaimStatus;
import com.claire.claims.domain.DenialGroupCode;
import com.claire.claims.domain.DenialSource;
import com.claire.claims.dto.DenialDtos.DeadlineSource;
import com.claire.claims.dto.DenialDtos.DenialReasonResponse;
import com.claire.claims.dto.DenialDtos.DenialWorklistItem;
import com.claire.claims.service.DenialWorklistService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The denial worklist endpoint. The worklist is a read-only screen: any
 * authenticated user, including VIEWER, may read it (there is no @PreAuthorize
 * on the GET), which is the RBAC criterion for this ticket - VIEWER can see
 * the list, while writes on denials/appeals stay 403 on their own endpoints.
 *
 * The @WebMvcTest slice auto-configures the security filter chain;
 * @EnableMethodSecurity turns on @PreAuthorize processing (the app's lives on
 * SecurityConfig, which the slice does not load), and GlobalExceptionHandler
 * is imported so the RFC 9457 mapping is the one that runs.
 */
@WebMvcTest(DenialWorklistController.class)
@Import(GlobalExceptionHandler.class)
@EnableMethodSecurity
class DenialWorklistControllerTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private DenialWorklistService worklist;

    @Test
    @WithMockUser(username = "viewer", roles = {"VIEWER"})
    void viewerCanReadTheWorklist() throws Exception {
        when(worklist.worklist(isNull(), isNull(), isNull(), isNull()))
                .thenReturn(List.of(sample()));

        mvc.perform(get("/api/denials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].claimNumber").value("CLM-A"))
                .andExpect(jsonPath("$[0].expired").value(false))
                .andExpect(jsonPath("$[0].deadlineSource").value("STAMPED"))
                .andExpect(jsonPath("$[0].reasons[0].carcCode").value("197"));
    }

    @Test
    @WithMockUser(username = "biller", roles = {"BILLER"})
    void billerCanReadTheWorklist() throws Exception {
        when(worklist.worklist(isNull(), isNull(), isNull(), isNull()))
                .thenReturn(List.of(sample()));

        mvc.perform(get("/api/denials")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "biller", roles = {"BILLER"})
    void passesFiltersThrough() throws Exception {
        when(worklist.worklist(eq(7L), eq("197"),
                eq(LocalDate.of(2026, 1, 1)), eq(LocalDate.of(2026, 3, 31))))
                .thenReturn(List.of(sample()));

        mvc.perform(get("/api/denials")
                        .param("payerId", "7")
                        .param("reasonCode", "197")
                        .param("from", "2026-01-01")
                        .param("to", "2026-03-31"))
                .andExpect(status().isOk());

        verify(worklist).worklist(7L, "197", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
    }

    private static DenialWorklistItem sample() {
        DenialReasonResponse reason = new DenialReasonResponse(
                1L, 1L, DenialGroupCode.CO, "197", "N130",
                DenialSource.MANUAL, "No prior auth", "biller", OffsetDateTime.now());
        return new DenialWorklistItem(
                1L, "CLM-A", ClaimStatus.APPEALED, "Ient, Pat", "MRN1",
                7L, "Acme Health", "Tor, Doc",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 10),
                new BigDecimal("500.00"), new BigDecimal("500.00"),
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 5, 1),
                DeadlineSource.STAMPED, 60L, false, 1, List.of(reason));
    }
}
