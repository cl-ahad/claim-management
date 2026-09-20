package com.claire.claims.web;

import com.claire.claims.common.GlobalExceptionHandler;
import com.claire.claims.domain.AppealOutcome;
import com.claire.claims.domain.AppealStatus;
import com.claire.claims.dto.AppealDtos.AppealResponse;
import com.claire.claims.service.AppealService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RBAC at the appeals endpoints. The @WebMvcTest slice auto-configures the web
 * security filter chain; @EnableMethodSecurity turns on @PreAuthorize (the
 * app's own annotation lives on SecurityConfig, which the slice does not
 * load). @WithMockUser drives the role, and GlobalExceptionHandler is imported
 * so the RFC 9457 403-on-denied-authorization mapping under test is the one
 * that runs - filing and recording an outcome are ADMIN/BILLER only, VIEWER
 * writes are forbidden.
 */
@WebMvcTest(AppealController.class)
@Import(GlobalExceptionHandler.class)
@EnableMethodSecurity
class AppealControllerTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private AppealService appeals;

    private static final String FILE_BODY = """
            {"level":1,"narrative":"please reconsider"}
            """;
    private static final String OUTCOME_BODY = """
            {"outcome":"OVERTURNED","note":"won on review"}
            """;

    @Test
    @WithMockUser(username = "biller", roles = {"BILLER"})
    void billerCanFileAnAppeal() throws Exception {
        when(appeals.file(eq(4L), any())).thenReturn(sample());

        mvc.perform(post("/api/claims/4/appeals").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(FILE_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void adminCanRecordAnOutcome() throws Exception {
        when(appeals.recordOutcome(eq(4L), any())).thenReturn(sample());

        mvc.perform(post("/api/claims/4/appeals/outcome").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(OUTCOME_BODY))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "viewer", roles = {"VIEWER"})
    void viewerCannotFileAnAppeal() throws Exception {
        mvc.perform(post("/api/claims/4/appeals").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(FILE_BODY))
                .andExpect(status().isForbidden());

        verify(appeals, never()).file(any(), any());
    }

    @Test
    @WithMockUser(username = "viewer", roles = {"VIEWER"})
    void viewerCannotRecordAnOutcome() throws Exception {
        mvc.perform(post("/api/claims/4/appeals/outcome").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(OUTCOME_BODY))
                .andExpect(status().isForbidden());

        verify(appeals, never()).recordOutcome(any(), any());
    }

    @Test
    @WithMockUser(username = "biller", roles = {"BILLER"})
    void outcomeWithoutADecisionIsBadRequest() throws Exception {
        // outcome is required; a body without it is rejected at the edge.
        mvc.perform(post("/api/claims/4/appeals/outcome").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}"))
                .andExpect(status().isBadRequest());

        verify(appeals, never()).recordOutcome(any(), any());
    }

    private static AppealResponse sample() {
        return new AppealResponse(1L, 4L, 1, AppealStatus.OPEN,
                LocalDate.now(), LocalDate.now().plusDays(90), "please reconsider",
                null, null, "biller", OffsetDateTime.now(), OffsetDateTime.now());
    }
}
