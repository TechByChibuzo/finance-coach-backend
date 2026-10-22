package com.financecoach.backend.service;

import com.sendgrid.*;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import com.financecoach.backend.dto.RecurringChargeResponse;
import com.financecoach.backend.dto.WeeklySummary;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Service
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    private final SendGrid sendGridClient;
    private final String fromEmail;
    private final String fromName;
    private final String frontendUrl;

    @Autowired
    public EmailService(
            @Value("${sendgrid.api-key}") String apiKey,
            @Value("${sendgrid.from-email}") String fromEmail,
            @Value("${sendgrid.from-name}") String fromName,
            @Value("${app.frontend-url}") String frontendUrl) {
        this(new SendGrid(apiKey), fromEmail, fromName, frontendUrl);
    }

    EmailService(SendGrid sendGridClient, String fromEmail, String fromName, String frontendUrl) {
        this.sendGridClient = sendGridClient;
        this.fromEmail = fromEmail;
        this.fromName = fromName;
        this.frontendUrl = frontendUrl;
    }

    /**
     * Send password reset email via SendGrid
     */
    public void sendPasswordResetEmail(String toEmail, String resetToken, String userName) {
        logger.info("Sending password reset email to: {}", toEmail);

        String resetLink = frontendUrl + "/reset-password?token=" + resetToken;

        Email from = new Email(fromEmail, fromName);
        Email to = new Email(toEmail);
        String subject = "Reset your Finance Coach password";

        // HTML content
        Content content = new Content(
                "text/html",
                buildPasswordResetEmailHtml(userName, resetLink)
        );

        Mail mail = new Mail(from, subject, to, content);

        try {
            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            Response response = sendGridClient.api(request);

            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
                logger.info("Password reset email sent successfully to: {}", toEmail);
            } else {
                logger.warn("SendGrid returned non-success status: {} for email: {}",
                        response.getStatusCode(), toEmail);
                logger.debug("SendGrid response body: {}", response.getBody());
            }

        } catch (IOException e) {
            logger.error("Failed to send password reset email to: {}", toEmail, e);
            throw new RuntimeException("Failed to send password reset email", e);
        }
    }

    /**
     * Send welcome email (bonus - for new user registration)
     */
    public void sendWelcomeEmail(String toEmail, String userName) {
        logger.info("Sending welcome email to: {}", toEmail);

        Email from = new Email(fromEmail, fromName);
        Email to = new Email(toEmail);
        String subject = "Welcome to Finance Coach";

        Content content = new Content(
                "text/html",
                buildWelcomeEmailHtml(userName)
        );

        Mail mail = new Mail(from, subject, to, content);

        try {
            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            Response response = sendGridClient.api(request);

            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
                logger.info("Welcome email sent successfully to: {}", toEmail);
            } else {
                logger.warn("SendGrid returned non-success status: {} for welcome email",
                        response.getStatusCode());
            }

        } catch (IOException e) {
            logger.error("Failed to send welcome email to: {}", toEmail, e);
            // Don't throw - welcome email failure shouldn't break registration
        }
    }

    /**
     * The Monday summary of last week's spending and the coming week's bills
     */
    public void sendWeeklySummary(String toEmail, String userName, WeeklySummary summary) {
        String subject = "Your week: " + money(summary.spent()) + " spent";
        Mail mail = new Mail(new Email(fromEmail, fromName), subject, new Email(toEmail),
                new Content("text/html", buildWeeklySummaryHtml(userName, summary)));
        try {
            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());
            Response response = sendGridClient.api(request);
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
                logger.warn("SendGrid returned {} for a weekly summary", response.getStatusCode());
            }
        } catch (IOException e) {
            logger.error("Failed to send weekly summary to: {}", toEmail, e);
        }
    }

    String buildWeeklySummaryHtml(String userName, WeeklySummary summary) {
        DateTimeFormatter day = DateTimeFormatter.ofPattern("MMM d", Locale.US);
        int compared = summary.spent().compareTo(summary.previousWeekSpent());
        String change = summary.previousWeekSpent().signum() == 0 || compared == 0
                ? ""
                : money(summary.spent().subtract(summary.previousWeekSpent()).abs())
                        + (compared > 0 ? " more" : " less") + " than the week before.";

        StringBuilder categories = new StringBuilder();
        for (WeeklySummary.CategoryTotal c : summary.topCategories()) {
            categories.append(row(categoryName(c.category()), money(c.amount())));
        }
        StringBuilder upcoming = new StringBuilder();
        for (RecurringChargeResponse r : summary.upcoming()) {
            upcoming.append(row(HtmlUtils.htmlEscape(r.merchant()) + " <span style=\"color:#5e6a75;\">"
                    + r.nextDate().format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)) + "</span>", money(r.amount())));
        }

        return """
            <!DOCTYPE html>
            <html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"></head>
            <body style="margin:0;padding:0;background:#f3f5f4;font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#1d2730;">
              <table role="presentation" style="width:100%%;border-collapse:collapse;"><tr><td align="center" style="padding:32px 16px;">
                <table role="presentation" style="width:100%%;max-width:560px;border-collapse:collapse;background:#ffffff;border-radius:16px;">
                  <tr><td style="padding:28px 32px 8px;">
                    <p style="margin:0;font-size:14px;font-weight:700;color:#0e6b5c;">Finance Coach</p>
                    <p style="margin:20px 0 4px;font-size:15px;color:#5e6a75;">Hi %s, here's your week of %s to %s.</p>
                    <p style="margin:0;font-size:36px;font-weight:800;letter-spacing:-0.5px;">%s <span style="font-size:16px;font-weight:500;color:#5e6a75;">spent</span></p>
                    <p style="margin:6px 0 0;font-size:15px;color:#5e6a75;">%s</p>
                  </td></tr>
                  %s
                  %s
                  <tr><td style="padding:24px 32px 28px;">
                    <a href="%s/dashboard" style="display:inline-block;background:#0e6b5c;color:#ffffff;text-decoration:none;font-size:15px;font-weight:600;padding:12px 22px;border-radius:10px;">Open Finance Coach</a>
                  </td></tr>
                </table>
                <p style="margin:16px 0 0;font-size:12px;color:#8d979f;">Transfers and card payments aren't counted as spending.
                  <a href="%s/settings?tab=profile" style="color:#5e6a75;">Turn off weekly emails</a></p>
              </td></tr></table>
            </body></html>
            """.formatted(
                displayName(userName),
                summary.weekStart().format(day), summary.weekEnd().format(day),
                money(summary.spent()), change,
                section("Where it went", categories.toString()),
                section("Coming up this week", upcoming.toString()),
                frontendUrl, frontendUrl);
    }

    private static String section(String title, String rows) {
        if (rows.isEmpty()) {
            return "";
        }
        return "<tr><td style=\"padding:20px 32px 0;\"><p style=\"margin:0 0 8px;font-size:13px;font-weight:700;color:#1d2730;\">"
                + title + "</p><table role=\"presentation\" style=\"width:100%;border-collapse:collapse;\">" + rows + "</table></td></tr>";
    }

    private static String row(String label, String amount) {
        return "<tr><td style=\"padding:8px 0;border-top:1px solid #e6eae8;font-size:15px;\">" + label
                + "</td><td style=\"padding:8px 0;border-top:1px solid #e6eae8;font-size:15px;font-weight:600;text-align:right;white-space:nowrap;\">"
                + amount + "</td></tr>";
    }

    // "FOOD_AND_DRINK" -> "Food and drink"
    static String categoryName(String code) {
        String words = code.toLowerCase(Locale.ROOT).replace('_', ' ');
        return HtmlUtils.htmlEscape(Character.toUpperCase(words.charAt(0)) + words.substring(1));
    }

    private static String money(BigDecimal amount) {
        return NumberFormat.getCurrencyInstance(Locale.US).format(amount);
    }

    /**
     * The user's name as safe HTML text. Names are user-supplied, so markup in them
     * (e.g. a link) must not render inside our emails.
     */
    private String displayName(String userName) {
        return userName == null || userName.isBlank() ? "there" : HtmlUtils.htmlEscape(userName);
    }

    /**
     * Build password reset email HTML
     */
    private String buildPasswordResetEmailHtml(String userName, String resetLink) {
        String link = HtmlUtils.htmlEscape(resetLink);
        return emailLayout("Reset your password", """
                <p style="margin:0 0 16px;font-size:15px;line-height:1.6;color:#5e6a75;">Hi %s,</p>
                <p style="margin:0 0 24px;font-size:15px;line-height:1.6;color:#5e6a75;">We got a request to reset your Finance Coach password. Choose a new one with the button below. The link works for one hour.</p>
                %s
                <p style="margin:24px 0 0;font-size:13px;line-height:1.6;color:#8d979f;">Or paste this link into your browser:<br><a href="%s" style="color:#0e6b5c;word-break:break-all;">%s</a></p>
                <p style="margin:24px 0 0;font-size:13px;line-height:1.6;color:#8d979f;">If you didn't ask for this, you can ignore this email. Your password stays the same.</p>
                """.formatted(displayName(userName), button(link, "Choose a new password"), link, link));
    }

    /**
     * Build welcome email HTML
     */
    private String buildWelcomeEmailHtml(String userName) {
        return emailLayout("Welcome to Finance Coach", """
                <p style="margin:0 0 16px;font-size:15px;line-height:1.6;color:#5e6a75;">Hi %s,</p>
                <p style="margin:0 0 20px;font-size:15px;line-height:1.6;color:#5e6a75;">Your account is ready. Three things get you set up:</p>
                <table role="presentation" style="width:100%%;border-collapse:collapse;margin:0 0 24px;">
                  %s%s%s
                </table>
                %s
                """.formatted(displayName(userName),
                step("1", "Connect a bank", "Your transactions come in on their own, sorted into categories."),
                step("2", "Set a budget", "Start from a suggestion based on what you actually spend."),
                step("3", "Ask the coach", "Try \u201cWhere did most of my money go last month?\u201d It answers with your numbers."),
                button(frontendUrl + "/dashboard", "Open Finance Coach")));
    }

    private static String step(String number, String title, String detail) {
        return "<tr><td style=\"width:32px;vertical-align:top;padding:10px 0;\"><span style=\"display:inline-block;width:24px;height:24px;line-height:24px;text-align:center;border-radius:12px;background:#e6f2ef;color:#0e6b5c;font-size:13px;font-weight:700;\">"
                + number + "</span></td><td style=\"padding:10px 0;\"><p style=\"margin:0;font-size:15px;font-weight:600;color:#1d2730;\">"
                + title + "</p><p style=\"margin:2px 0 0;font-size:14px;line-height:1.5;color:#5e6a75;\">" + detail + "</p></td></tr>";
    }

    private static String button(String href, String label) {
        return "<a href=\"" + href + "\" style=\"display:inline-block;background:#0e6b5c;color:#ffffff;text-decoration:none;font-size:15px;font-weight:600;padding:12px 22px;border-radius:10px;\">"
                + label + "</a>";
    }

    // The frame shared by account emails: brand, heading, content, and a quiet footer
    private static String emailLayout(String heading, String content) {
        return """
            <!DOCTYPE html>
            <html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"><title>%s</title></head>
            <body style="margin:0;padding:0;background:#f3f5f4;font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#1d2730;">
              <table role="presentation" style="width:100%%;border-collapse:collapse;"><tr><td align="center" style="padding:32px 16px;">
                <table role="presentation" style="width:100%%;max-width:560px;border-collapse:collapse;background:#ffffff;border-radius:16px;">
                  <tr><td style="padding:28px 32px 32px;">
                    <p style="margin:0;font-size:14px;font-weight:700;color:#0e6b5c;">Finance Coach</p>
                    <h1 style="margin:20px 0 16px;font-size:24px;font-weight:800;letter-spacing:-0.3px;color:#1d2730;">%s</h1>
                    %s
                  </td></tr>
                </table>
                <p style="margin:16px 0 0;font-size:12px;color:#8d979f;">Finance Coach</p>
              </td></tr></table>
            </body></html>
            """.formatted(heading, heading, content);
    }
}
