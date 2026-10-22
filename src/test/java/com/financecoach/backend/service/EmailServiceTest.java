package com.financecoach.backend.service;

import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock
    private SendGrid sendGrid;

    private EmailService emailService;

    @BeforeEach
    void setUp() {
        emailService = new EmailService(sendGrid, "noreply@financecoach.test", "Finance Coach",
                "https://app.financecoach.test");
    }

    @Test
    void sendPasswordResetEmail_sendsTheResetLinkToTheUser() throws Exception {
        when(sendGrid.api(any())).thenReturn(new Response(202, "", null));

        emailService.sendPasswordResetEmail("jane@example.com", "token-123", "Jane");

        String body = sentBody();
        assertThat(body)
                .contains("jane@example.com")
                .contains("noreply@financecoach.test")
                .contains("Reset your password")
                .contains("https://app.financecoach.test/reset-password?token=token-123")
                .contains("Hi Jane");
    }

    @Test
    void sendWeeklySummary_showsTheWeek_escapingMerchantNames() throws Exception {
        when(sendGrid.api(any())).thenReturn(new Response(202, "", null));
        var summary = new com.financecoach.backend.dto.WeeklySummary(
                java.time.LocalDate.of(2026, 10, 19), java.time.LocalDate.of(2026, 10, 25),
                new java.math.BigDecimal("425.00"), new java.math.BigDecimal("200.00"),
                java.util.List.of(new com.financecoach.backend.dto.WeeklySummary.CategoryTotal("FOOD_AND_DRINK", new java.math.BigDecimal("100.00"))),
                java.util.List.of(new com.financecoach.backend.dto.RecurringChargeResponse("<b>Evil</b> Co", null, "ENTERTAINMENT",
                        "subscription", "monthly", new java.math.BigDecimal("9.99"), null,
                        java.time.LocalDate.of(2026, 9, 28), java.time.LocalDate.of(2026, 10, 28), 3)));

        emailService.sendWeeklySummary("jane@example.com", "Jane", summary);

        String body = sentBody();
        assertThat(body)
                .contains("Your week: $425.00 spent")
                .contains("Oct 19 to Oct 25")
                .contains("$225.00 more than the week before")
                .contains("Food and drink")
                .contains("&lt;b&gt;Evil&lt;/b&gt; Co")
                .contains("Wed, Oct 28")
                .contains("https://app.financecoach.test/settings?tab=profile")
                .doesNotContain("<b>Evil</b>");
    }

    @Test
    void buildWeeklySummaryHtml_leavesOutEmptySections_andTheComparisonWithNothingToCompare() {
        var summary = new com.financecoach.backend.dto.WeeklySummary(
                java.time.LocalDate.of(2026, 10, 19), java.time.LocalDate.of(2026, 10, 25),
                new java.math.BigDecimal("50.00"), java.math.BigDecimal.ZERO, java.util.List.of(), java.util.List.of());

        String html = emailService.buildWeeklySummaryHtml(null, summary);

        assertThat(html).contains("Hi there").doesNotContain("Coming up this week").doesNotContain("than the week before");
    }

    @Test
    void sendPasswordResetEmail_escapesMarkupInTheUsersName() throws Exception {
        when(sendGrid.api(any())).thenReturn(new Response(202, "", null));

        emailService.sendPasswordResetEmail("jane@example.com", "t", "<a href=\"https://evil.example\">Click</a>");

        String body = sentBody();
        assertThat(body).doesNotContain("<a href=\\\"https://evil.example\\\">Click</a>");
        assertThat(body).contains("&lt;a href=");
    }

    @Test
    void sendPasswordResetEmail_withoutAName_greetsGenerically() throws Exception {
        when(sendGrid.api(any())).thenReturn(new Response(202, "", null));

        emailService.sendPasswordResetEmail("jane@example.com", "t", null);

        assertThat(sentBody()).contains("Hi there").doesNotContain("Hi null");
    }

    @Test
    void sendPasswordResetEmail_whenSendGridRejects_doesNotThrow_butANetworkErrorDoes() throws Exception {
        when(sendGrid.api(any()))
                .thenReturn(new Response(401, "{\"errors\":[]}", null))
                .thenThrow(new IOException("timeout"));

        assertThatCode(() -> emailService.sendPasswordResetEmail("jane@example.com", "t", "Jane"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> emailService.sendPasswordResetEmail("jane@example.com", "t", "Jane"))
                .hasMessageContaining("Failed to send password reset email");
    }

    @Test
    void sendWelcomeEmail_sendsAWelcome_andNeverThrows() throws Exception {
        when(sendGrid.api(any()))
                .thenReturn(new Response(202, "", null))
                .thenReturn(new Response(500, "", null))
                .thenThrow(new IOException("timeout"));

        emailService.sendWelcomeEmail("jane@example.com", "Jane");
        assertThat(sentBody()).contains("Welcome to Finance Coach").contains("Jane")
                .contains("https://app.financecoach.test");

        assertThatCode(() -> emailService.sendWelcomeEmail("jane@example.com", "Jane")).doesNotThrowAnyException();
        assertThatCode(() -> emailService.sendWelcomeEmail("jane@example.com", "Jane")).doesNotThrowAnyException();
    }

    private String sentBody() throws IOException {
        ArgumentCaptor<Request> request = ArgumentCaptor.forClass(Request.class);
        verify(sendGrid, org.mockito.Mockito.atLeastOnce()).api(request.capture());
        assertThat(request.getValue().getEndpoint()).isEqualTo("mail/send");
        return request.getValue().getBody();
    }
}
