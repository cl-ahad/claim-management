package com.claire.claims.web;

import com.claire.claims.common.GlobalExceptionHandler;
import com.claire.claims.dto.DenialDtos.DenialReasonResponse;
import com.claire.claims.domain.DenialGroupCode;
import com.claire.claims.domain.DenialSource;
import com.claire.claims.service.DenialService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
 * RBAC and request validation at the denial-reasons endpoint. The @WebMvcTest
 * slice auto-configures the web security filter chain; MethodSecurityConfig
 * turns on @PreAuthorize processing (the app's @EnableMethodSecurity lives on
 * SecurityConfig, which the slice does not load). @WithMockUser drives the
 * role, and GlobalExceptionHandler is imported so the RFC 9457 status mapping
 * under test (403 on denied authorization, 400 on validation) is the one that
 * runs - exactly the boundary this test is about.
 */
@WebMvcTest(DenialController.class)
@Import(GlobalExceptionHandler.class)
@EnableMethodSecurity
class DenialControllerTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private DenialService denials;

    private static final String BODY = """
            {"groupCode":"CO","carcCode":"197","rarcCode":"N130","note":"No prior auth"}
            """;

    @Test
    @WithMockUser(username = "biller", roles = {"BILLER"})
    void billerCanRecordAReason() throws Exception {
        when(denials.recordReason(eq(4L), any())).thenReturn(sample());

        mvc.perform(post("/api/claims/4/denial-reasons").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void adminCanRecordAReason() throws Exception {
        when(denials.recordReason(eq(4L), any())).thenReturn(sample());

        mvc.perform(post("/api/claims/4/denial-reasons").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(username = "viewer", roles = {"VIEWER"})
    void viewerCannotRecordAReason() throws Exception {
        mvc.perform(post("/api/claims/4/denial-reasons").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());

        verify(denials, never()).recordReason(any(), any());
    }

    @Test
    @WithMockUser(username = "biller", roles = {"BILLER"})
    void missingStructuredReasonIsBadRequest() throws Exception {
        // CARC code absent: a reason with only free text is rejected at the edge.
        String freeTextOnly = """
                {"groupCode":"CO","note":"denied, see the letter"}
                """;

        mvc.perform(post("/api/claims/4/denial-reasons").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(freeTextOnly))
                .andExpect(status().isBadRequest());

        verify(denials, never()).recordReason(any(), any());
    }

    @Test
    @WithMockUser(username = "biller", roles = {"BILLER"})
    void missingGroupCodeIsBadRequest() throws Exception {
        String noGroup = """
                {"carcCode":"197"}
                """;

        mvc.perform(post("/api/claims/4/denial-reasons").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(noGroup))
                .andExpect(status().isBadRequest());

        verify(denials, never()).recordReason(any(), any());
    }

    private static DenialReasonResponse sample() {
        return new DenialReasonResponse(1L, 4L, DenialGroupCode.CO, "197", "N130",
                DenialSource.MANUAL, "No prior auth", "biller", OffsetDateTime.now());
    }
}
