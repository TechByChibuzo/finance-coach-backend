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

    @Test
    void unknownAddresses_wrongMethods_andUnreadableBodies_areClientErrors() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/networth/assets");

        assertThat(handler.handleNoResourceFound(
                new org.springframework.web.servlet.resource.NoResourceFoundException(
                        org.springframework.http.HttpMethod.POST, "api/networth/assets"), request)
                .getStatusCode().value()).isEqualTo(404);
        assertThat(handler.handleMethodNotSupported(
                new org.springframework.web.HttpRequestMethodNotSupportedException("PATCH"), request)
                .getBody().getMessage()).isEqualTo("PATCH isn't supported here.");
        assertThat(handler.handleUnreadableBody(
                new org.springframework.http.converter.HttpMessageNotReadableException("bad json",
                        new org.springframework.mock.http.MockHttpInputMessage(new byte[0])), request)
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void aMissingResource_is404_andBillingTroubleIs503_eachWithItsMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/networth/assets/1");

        ResponseEntity<ErrorResponse> notFound = handler.handleResourceNotFound(
                new ResourceNotFoundException("Asset"), request);
        assertThat(notFound.getStatusCode().value()).isEqualTo(404);
        assertThat(notFound.getBody().getMessage()).isEqualTo("Asset not found");

        ResponseEntity<ErrorResponse> unavailable = handler.handleBillingUnavailable(
                new BillingUnavailableException("Could not cancel your subscription. Please try again.",
                        new RuntimeException("stripe down")), request);
        assertThat(unavailable.getStatusCode().value()).isEqualTo(503);
        assertThat(unavailable.getBody().getMessage()).isEqualTo("Could not cancel your subscription. Please try again.");
    }
}
