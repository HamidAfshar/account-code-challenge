package com.afshar.codechallenge.api;

import com.afshar.codechallenge.application.AccountService;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RestExceptionHandlerTests {

    @Test
    void constraintViolationsReturnBadRequestWithValidationProblemDetail() throws Exception {
        var service = mock(AccountService.class);
        var exception = new ConstraintViolationException(Set.of());
        when(service.getBalance("alice")).thenThrow(exception);
        var mvc = MockMvcBuilders.standaloneSetup(new AccountController(service))
                .setControllerAdvice(new RestExceptionHandler())
                .build();

        mvc.perform(get("/accounts/alice/balance"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Request validation failed"))
                .andExpect(result -> assertThat(result.getResolvedException()).isSameAs(exception));
    }
}
