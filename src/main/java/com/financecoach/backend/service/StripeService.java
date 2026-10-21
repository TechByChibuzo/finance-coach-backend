package com.financecoach.backend.service;

import com.stripe.Stripe;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.*;
import com.stripe.model.checkout.Session;
import com.stripe.param.*;
import com.stripe.param.checkout.SessionCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.util.List;

@Service
public class StripeService {

    @Value("${stripe.api-key}")
    private String stripeApiKey;

    @PostConstruct
    public void init() {
        Stripe.apiKey = stripeApiKey;
    }

    /**
     * Create Stripe customer
     */
    public Customer createCustomer(String email, String name) throws StripeException {
        CustomerCreateParams params = CustomerCreateParams.builder()
                .setEmail(email)
                .setName(name)
                .build();

        return Customer.create(params);
    }

    /**
     * Create payment intent (one-time payment)
     */
    public PaymentIntent createPaymentIntent(Long amount, String currency, String customerId)
            throws StripeException {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(amount) // Amount in cents
                .setCurrency(currency)
                .setCustomer(customerId)
                .build();

        return PaymentIntent.create(params);
    }

    /**
     * Create subscription
     */
    public Subscription createSubscription(String customerId, String priceId)
            throws StripeException {
        SubscriptionCreateParams params = SubscriptionCreateParams.builder()
                .setCustomer(customerId)
                .addItem(
                        SubscriptionCreateParams.Item.builder()
                                .setPrice(priceId)
                                .build()
                )
                .setPaymentBehavior(SubscriptionCreateParams.PaymentBehavior.DEFAULT_INCOMPLETE)
                .setPaymentSettings(
                        SubscriptionCreateParams.PaymentSettings.builder()
                                .setSaveDefaultPaymentMethod(
                                        SubscriptionCreateParams.PaymentSettings.SaveDefaultPaymentMethod.ON_SUBSCRIPTION
                                )
                                .build()
                )
                .build();

        return Subscription.create(params);
    }

    /**
     * Cancel subscription
     */
    public Subscription cancelSubscription(String subscriptionId) throws StripeException {
        Subscription subscription;
        try {
            subscription = Subscription.retrieve(subscriptionId);
        } catch (InvalidRequestException e) {
            if ("resource_missing".equals(e.getCode())) {
                return null; // already gone at Stripe: nothing left to cancel
            }
            throw e;
        }

        // Safe to call again (e.g. on a retried webhook): an ended subscription stays ended
        String status = subscription.getStatus();
        if ("canceled".equals(status) || "incomplete_expired".equals(status)) {
            return subscription;
        }
        try {
            return subscription.cancel();
        } catch (InvalidRequestException e) {
            if ("resource_missing".equals(e.getCode())) {
                return null; // cancelled by someone else since we retrieved it
            }
            throw e;
        }
    }

    /**
     * Retrieve the current state of a subscription
     */
    public Subscription retrieveSubscription(String subscriptionId) throws StripeException {
        return Subscription.retrieve(subscriptionId);
    }

    /**
     * Cancel subscription at the end of the current billing period.
     * The customer keeps access until then and is not charged again.
     */
    public Subscription cancelSubscriptionAtPeriodEnd(String subscriptionId) throws StripeException {
        Subscription subscription = Subscription.retrieve(subscriptionId);
        return subscription.update(
                SubscriptionUpdateParams.builder()
                        .setCancelAtPeriodEnd(true)
                        .build()
        );
    }

    /**
     * Permanently delete a customer (and their stored email, name and payment methods).
     * Stripe also cancels any of the customer's subscriptions immediately.
     * A customer that no longer exists counts as deleted.
     */
    public void deleteCustomer(String customerId) throws StripeException {
        try {
            Customer.retrieve(customerId).delete();
        } catch (InvalidRequestException e) {
            if (!"resource_missing".equals(e.getCode())) {
                throw e;
            }
        }
    }

    /**
     * List a customer's subscriptions (all statuses)
     */
    public List<Subscription> listSubscriptions(String customerId) throws StripeException {
        SubscriptionListParams params = SubscriptionListParams.builder()
                .setCustomer(customerId)
                .setStatus(SubscriptionListParams.Status.ALL)
                .build();

        return Subscription.list(params).getData();
    }

    /**
     * Create checkout session
     */
    public Session createCheckoutSession(String customerId, String priceId, String successUrl, String cancelUrl,
                                         String clientReferenceId)
            throws StripeException {
        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setCustomer(customerId)
                .setClientReferenceId(clientReferenceId)
                .addLineItem(
                        SessionCreateParams.LineItem.builder()
                                .setPrice(priceId)
                                .setQuantity(1L)
                                .build()
                )
                .setSuccessUrl(successUrl)
                .setCancelUrl(cancelUrl)
                .build();

        return Session.create(params);
    }
}