package com.financecoach.backend.service;

import com.stripe.Stripe;
import com.stripe.exception.InvalidRequestException;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionCollection;
import com.stripe.model.checkout.Session;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.SubscriptionCreateParams;
import com.stripe.param.SubscriptionListParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.checkout.SessionCreateParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StripeService wraps the SDK's static API; the statics are mocked so no network calls happen.
 */
class StripeServiceTest {

    private final StripeService stripeService = new StripeService();
    private MockedStatic<Customer> customers;
    private MockedStatic<Subscription> subscriptions;
    private MockedStatic<Session> sessions;
    private MockedStatic<PaymentIntent> paymentIntents;

    @BeforeEach
    void setUp() {
        customers = mockStatic(Customer.class);
        subscriptions = mockStatic(Subscription.class);
        sessions = mockStatic(Session.class);
        paymentIntents = mockStatic(PaymentIntent.class);
    }

    @AfterEach
    void tearDown() {
        customers.close();
        subscriptions.close();
        sessions.close();
        paymentIntents.close();
    }

    @Test
    void init_setsTheApiKey() {
        ReflectionTestUtils.setField(stripeService, "stripeApiKey", "sk_test_123");

        stripeService.init();

        assertThat(Stripe.apiKey).isEqualTo("sk_test_123");
    }

    @Test
    void createCustomer_sendsEmailAndName() throws Exception {
        Customer customer = new Customer();
        customers.when(() -> Customer.create(any(CustomerCreateParams.class))).thenReturn(customer);

        assertThat(stripeService.createCustomer("jane@example.com", "Jane")).isSameAs(customer);

        ArgumentCaptor<CustomerCreateParams> params = ArgumentCaptor.forClass(CustomerCreateParams.class);
        customers.verify(() -> Customer.create(params.capture()));
        assertThat(params.getValue().getEmail()).isEqualTo("jane@example.com");
        assertThat(params.getValue().getName()).isEqualTo("Jane");
    }

    @Test
    void createCheckoutSession_isASubscriptionCheckoutTaggedWithTheUser() throws Exception {
        Session session = new Session();
        sessions.when(() -> Session.create(any(SessionCreateParams.class))).thenReturn(session);

        assertThat(stripeService.createCheckoutSession("cus_1", "price_1", "https://ok", "https://cancel", "user-1"))
                .isSameAs(session);

        ArgumentCaptor<SessionCreateParams> params = ArgumentCaptor.forClass(SessionCreateParams.class);
        sessions.verify(() -> Session.create(params.capture()));
        assertThat(params.getValue().getMode()).isEqualTo(SessionCreateParams.Mode.SUBSCRIPTION);
        assertThat(params.getValue().getCustomer()).isEqualTo("cus_1");
        assertThat(params.getValue().getClientReferenceId()).isEqualTo("user-1");
        assertThat(params.getValue().getLineItems().get(0).getPrice()).isEqualTo("price_1");
    }

    @Test
    void cancelSubscription_cancelsImmediately() throws Exception {
        Subscription subscription = mock(Subscription.class);
        Subscription cancelled = new Subscription();
        subscriptions.when(() -> Subscription.retrieve("sub_1")).thenReturn(subscription);
        when(subscription.cancel()).thenReturn(cancelled);

        assertThat(stripeService.cancelSubscription("sub_1")).isSameAs(cancelled);
    }

    @Test
    void cancelSubscription_isSafeToRepeat_forEndedOrMissingSubscriptions() throws Exception {
        Subscription ended = mock(Subscription.class);
        when(ended.getStatus()).thenReturn("canceled");
        subscriptions.when(() -> Subscription.retrieve("sub_ended")).thenReturn(ended);
        subscriptions.when(() -> Subscription.retrieve("sub_gone")).thenThrow(
                new InvalidRequestException("No such subscription", "id", "req_1", "resource_missing", 404, null));

        assertThat(stripeService.cancelSubscription("sub_ended")).isSameAs(ended);
        assertThat(stripeService.cancelSubscription("sub_gone")).isNull();
        verify(ended, org.mockito.Mockito.never()).cancel();
    }

    @Test
    void cancelSubscription_cancelledByAnotherRequestMeanwhile_countsAsCancelled() throws Exception {
        Subscription subscription = mock(Subscription.class);
        when(subscription.getStatus()).thenReturn("active");
        subscriptions.when(() -> Subscription.retrieve("sub_1")).thenReturn(subscription);
        when(subscription.cancel()).thenThrow(
                new InvalidRequestException("No such subscription", "id", "req_1", "resource_missing", 404, null));

        assertThat(stripeService.cancelSubscription("sub_1")).isNull();
    }

    @Test
    void cancelSubscription_errorsWhileCancelling_propagate() throws Exception {
        Subscription subscription = mock(Subscription.class);
        when(subscription.getStatus()).thenReturn("active");
        subscriptions.when(() -> Subscription.retrieve("sub_1")).thenReturn(subscription);
        when(subscription.cancel()).thenThrow(
                new InvalidRequestException("Rate limited", null, "req_1", "rate_limit", 429, null));

        assertThatThrownBy(() -> stripeService.cancelSubscription("sub_1")).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void cancelSubscription_otherStripeErrors_propagate() {
        subscriptions.when(() -> Subscription.retrieve("sub_1")).thenThrow(
                new InvalidRequestException("Invalid API key", null, "req_1", "api_key_invalid", 401, null));

        assertThatThrownBy(() -> stripeService.cancelSubscription("sub_1")).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void cancelSubscriptionAtPeriodEnd_setsCancelAtPeriodEnd() throws Exception {
        Subscription subscription = mock(Subscription.class);
        subscriptions.when(() -> Subscription.retrieve("sub_1")).thenReturn(subscription);
        when(subscription.update(any(SubscriptionUpdateParams.class))).thenReturn(subscription);

        stripeService.cancelSubscriptionAtPeriodEnd("sub_1");

        ArgumentCaptor<SubscriptionUpdateParams> params = ArgumentCaptor.forClass(SubscriptionUpdateParams.class);
        verify(subscription).update(params.capture());
        assertThat(params.getValue().getCancelAtPeriodEnd()).isTrue();
    }

    @Test
    void retrieveAndListSubscriptions_delegateToStripe() throws Exception {
        Subscription subscription = new Subscription();
        SubscriptionCollection collection = new SubscriptionCollection();
        collection.setData(List.of(subscription));
        subscriptions.when(() -> Subscription.retrieve("sub_1")).thenReturn(subscription);
        subscriptions.when(() -> Subscription.list(any(SubscriptionListParams.class))).thenReturn(collection);

        assertThat(stripeService.retrieveSubscription("sub_1")).isSameAs(subscription);
        assertThat(stripeService.listSubscriptions("cus_1")).containsExactly(subscription);
    }

    @Test
    void deleteCustomer_deletesIt_andTreatsAMissingCustomerAsDeleted() throws Exception {
        Customer customer = mock(Customer.class);
        customers.when(() -> Customer.retrieve("cus_1")).thenReturn(customer);
        customers.when(() -> Customer.retrieve("cus_gone")).thenThrow(
                new InvalidRequestException("No such customer", "id", "req_1", "resource_missing", 404, null));

        stripeService.deleteCustomer("cus_1");
        verify(customer).delete();
        assertThatCode(() -> stripeService.deleteCustomer("cus_gone")).doesNotThrowAnyException();
    }

    @Test
    void deleteCustomer_otherStripeErrors_propagate() {
        customers.when(() -> Customer.retrieve("cus_1")).thenThrow(
                new InvalidRequestException("Invalid API key", null, "req_1", "api_key_invalid", 401, null));

        assertThatThrownBy(() -> stripeService.deleteCustomer("cus_1")).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void createSubscriptionAndPaymentIntent_delegateToStripe() throws Exception {
        Subscription subscription = new Subscription();
        PaymentIntent intent = new PaymentIntent();
        subscriptions.when(() -> Subscription.create(any(SubscriptionCreateParams.class))).thenReturn(subscription);
        paymentIntents.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class))).thenReturn(intent);

        assertThat(stripeService.createSubscription("cus_1", "price_1")).isSameAs(subscription);
        assertThat(stripeService.createPaymentIntent(999L, "usd", "cus_1")).isSameAs(intent);
    }
}
