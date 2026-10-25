package com.financecoach.backend.controller;

import com.financecoach.backend.dto.CheckoutRequest;
import com.financecoach.backend.exception.BillingUnavailableException;
import com.financecoach.backend.exception.ResourceNotFoundException;
import com.financecoach.backend.model.BillingCycle;
import com.financecoach.backend.service.SubscriptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Errors from checkout and cancel go to GlobalExceptionHandler (proper status, safe message)
 * instead of the controller answering 400 with the raw exception message.
 */
class SubscriptionControllerTest {

    private final SubscriptionService subscriptionService = mock(SubscriptionService.class);
    private final SubscriptionController controller = new SubscriptionController();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "subscriptionService", subscriptionService);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void checkoutErrors_reachTheExceptionHandler() throws Exception {
        when(subscriptionService.createCheckoutSession(any(), any(), any()))
                .thenThrow(new ResourceNotFoundException("Plan"));
        CheckoutRequest request = new CheckoutRequest();
        request.setPlanId(UUID.randomUUID());
        request.setBillingCycle(BillingCycle.MONTHLY);

        assertThatThrownBy(() -> controller.createCheckout(request)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aStripeFailureAtCheckout_isABillingError_withoutStripesMessage() throws Exception {
        when(subscriptionService.createCheckoutSession(any(), any(), any()))
                .thenThrow(new com.stripe.exception.ApiConnectionException("internal stripe detail"));

        assertThatThrownBy(() -> controller.createCheckout(new CheckoutRequest()))
                .isInstanceOf(BillingUnavailableException.class)
                .hasMessage("Couldn't start checkout. Please try again.");
    }

    @Test
    void cancelErrors_reachTheExceptionHandler() {
        when(subscriptionService.cancelSubscription(userId))
                .thenThrow(new BillingUnavailableException("Could not cancel your subscription. Please try again.", null));

        assertThatThrownBy(controller::cancelSubscription).isInstanceOf(BillingUnavailableException.class);
    }

    @Test
    void withoutAnAuthenticatedUser_itsAnAuthenticationError() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(controller::cancelSubscription)
                .isInstanceOf(org.springframework.security.core.AuthenticationException.class);
    }
}
