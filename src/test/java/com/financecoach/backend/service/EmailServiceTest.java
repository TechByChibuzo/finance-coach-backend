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
                .contains("Reset Your Password")
                .contains("https://app.financecoach.test/reset-password?token=token-123")
                .contains("Hi Jane");
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
