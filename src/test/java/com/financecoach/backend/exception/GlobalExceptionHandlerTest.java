package com.financecoach.backend.exception;

import com.financecoach.backend.dto.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void validationErrors_putTheActualReasonInTheMessage() {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "request");
        result.addError(new FieldError("request", "password", "Password must be at least 8 characters"));
        result.addError(new FieldError("request", "email", "Email must be valid"));
        MethodArgumentNotValidException ex =
                new MethodArgumentNotValidException(mock(MethodParameter.class), result);

        ResponseEntity<ErrorResponse> response =
                handler.handleMethodArgumentNotValid(ex, new MockHttpServletRequest("POST", "/api/auth/register"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getMessage())
                .isEqualTo("Email must be valid. Password must be at least 8 characters");
        assertThat(response.getBody().getFieldErrors()).isEqualTo(Map.of(
                "password", "Password must be at least 8 characters",
                "email", "Email must be valid"));
    }
}
