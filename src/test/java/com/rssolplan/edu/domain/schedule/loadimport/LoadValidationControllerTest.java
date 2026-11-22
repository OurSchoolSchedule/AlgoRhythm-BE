package com.rssolplan.edu.domain.schedule.loadimport;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

class LoadValidationControllerTest {
    private final LoadValidationService service = mock(LoadValidationService.class);
    private final MockMvc api =
            MockMvcBuilders.standaloneSetup(new LoadValidationController(service))
                    .setCustomArgumentResolvers(
                            new org.springframework.security.web.method.annotation
                                    .AuthenticationPrincipalArgumentResolver())
                    .build();

    private MockMultipartFile file(boolean valid) {
        return new MockMultipartFile(
                "file",
                "시수.xlsx",
                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                LoadWorkbookParserTest.file(
                        s -> {
                            if (!valid) s.getRow(2).getCell(5).setCellValue(9);
                        }));
    }

    @Test
    void previewReturnsCatalogIds() throws Exception {
        when(service.catalog(null, 7L, null, Map.of()))
                .thenReturn(LoadWorkbookParserTest.catalog());
        api.perform(multipart("/api/v2/terms/7/load-imports/validate").file(file(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPeriods").value(5))
                .andExpect(jsonPath("$.demands[0].teacherId").value("10"));
    }

    @Test
    void mismatchedSumHasHumanAndMachineReadableCoordinates() throws Exception {
        when(service.catalog(null, null, 2026, Map.of()))
                .thenReturn(LoadWorkbookParserTest.catalog());
        api.perform(
                        multipart("/api/v2/school/load-imports/validate")
                                .file(file(false))
                                .param("academicYear", "2026"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_LOAD_SHEET"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("ROW_TOTAL_MISMATCH"))
                .andExpect(jsonPath("$.fieldErrors[0].sheet").value("시수표"))
                .andExpect(jsonPath("$.fieldErrors[0].row").value(3))
                .andExpect(jsonPath("$.fieldErrors[0].column").value("F"));
    }
}
