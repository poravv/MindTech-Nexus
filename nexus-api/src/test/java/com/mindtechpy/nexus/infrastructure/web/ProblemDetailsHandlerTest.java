package com.mindtechpy.nexus.infrastructure.web;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Database down while serving a request: the client gets a retryable 503, never a 500 or a false 202. */
class ProblemDetailsHandlerTest {

    @RestController
    static class DatabaseDownController {
        @PostMapping("/db-down")
        void write() {
            throw new DataAccessResourceFailureException("Connection refused");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DatabaseDownController())
            .setControllerAdvice(new ProblemDetailsHandler()).build();

    @Test
    void should_answer_503_with_retry_after_when_the_database_is_unreachable() throws Exception {
        mvc.perform(post("/db-down"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("service-unavailable"));
    }
}
