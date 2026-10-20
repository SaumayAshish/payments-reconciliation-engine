package com.saumay.payments.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:15:30Z");

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC)))
            .addFilters(new CorrelationIdFilter())
            .build();

    @RestController
    static class TestController {

        record Payload(@NotBlank String name) {
        }

        @GetMapping("/test/duplicate")
        String duplicate() {
            throw new ApiException(HttpStatus.CONFLICT, "RECONCILIATION_DUPLICATE_FILE",
                    "Statement file has already been processed");
        }

        @GetMapping("/test/boom")
        String boom() {
            throw new IllegalStateException(
                    "jdbc:postgresql://secret-host:5432/db password=hunter2 SELECT * FROM statement_line");
        }

        @PostMapping("/test/validate")
        String validate(@Valid @RequestBody Payload payload) {
            return payload.name();
        }
    }

    @Test
    void deliberateFailuresUseTheStandardShapeAndEchoTheCallersCorrelationId() throws Exception {
        mvc.perform(get("/test/duplicate").header("X-Correlation-Id", "test-corr-1234"))
                .andExpect(status().isConflict())
                .andExpect(header().string("X-Correlation-Id", "test-corr-1234"))
                .andExpect(jsonPath("$.code").value("RECONCILIATION_DUPLICATE_FILE"))
                .andExpect(jsonPath("$.message").value("Statement file has already been processed"))
                .andExpect(jsonPath("$.correlationId").value("test-corr-1234"))
                .andExpect(jsonPath("$.timestamp").value("2026-10-19T10:15:30Z"))
                .andExpect(jsonPath("$.details").isEmpty());
    }

    @Test
    void aMissingCorrelationIdIsGeneratedAndReturnedInBodyAndHeader() throws Exception {
        String uuidPattern = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
        mvc.perform(get("/test/duplicate"))
                .andExpect(header().string("X-Correlation-Id", org.hamcrest.Matchers.matchesPattern(uuidPattern)))
                .andExpect(jsonPath("$.correlationId").value(org.hamcrest.Matchers.matchesPattern(uuidPattern)));
    }

    @Test
    void anUnsafeSuppliedCorrelationIdIsReplacedNeverEchoed() throws Exception {
        mvc.perform(get("/test/duplicate").header("X-Correlation-Id", "bad id!"))
                .andExpect(header().string("X-Correlation-Id", not(containsString("bad"))))
                .andExpect(jsonPath("$.correlationId").value(not(containsString("bad"))));
    }

    @Test
    void validationFailuresListEachInvalidField() throws Exception {
        mvc.perform(post("/test/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").value("name"));
    }

    @Test
    void malformedJsonIsABadRequestWithoutParserDetails() throws Exception {
        mvc.perform(post("/test/validate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request body is missing or malformed"));
    }

    @Test
    void wrongHttpMethodUsesTheSameShape() throws Exception {
        mvc.perform(get("/test/validate"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void unexpectedErrorsNeverLeakInternals() throws Exception {
        mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(not(containsString("secret-host"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(not(containsString("hunter2"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(not(containsString("SELECT"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(not(containsString("IllegalStateException"))));
    }
}