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
        String subject = "Reset Your Password - Finance Coach";

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
        String subject = "Welcome to Finance Coach! 🎉";

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
        return String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Reset Your Password</title>
            </head>
            <body style="margin: 0; padding: 0; font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background-color: #f3f4f6;">
                <table role="presentation" style="width: 100%%; border-collapse: collapse;">
                    <tr>
                        <td align="center" style="padding: 40px 0;">
                            <table role="presentation" style="width: 600px; border-collapse: collapse; background-color: #ffffff; border-radius: 12px; box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);">
                                
                                <!-- Header -->
                                <tr>
                                    <td style="padding: 40px 40px 20px; text-align: center; background: linear-gradient(135deg, #667eea 0%%, #764ba2 100%%); border-radius: 12px 12px 0 0;">
                                        <div style="font-size: 48px; margin-bottom: 10px;">💎</div>
                                        <h1 style="margin: 0; color: #ffffff; font-size: 28px; font-weight: 600;">Finance Coach</h1>
                                    </td>
                                </tr>
                                
                                <!-- Content -->
                                <tr>
                                    <td style="padding: 40px;">
                                        <h2 style="margin: 0 0 20px; color: #1f2937; font-size: 24px; font-weight: 600;">Reset Your Password</h2>
                                        
                                        <p style="margin: 0 0 20px; color: #4b5563; font-size: 16px; line-height: 1.6;">
                                            Hi %s,
                                        </p>
                                        
                                        <p style="margin: 0 0 30px; color: #4b5563; font-size: 16px; line-height: 1.6;">
                                            We received a request to reset your password. Click the button below to create a new password:
                                        </p>
                                        
                                        <!-- Button -->
                                        <table role="presentation" style="margin: 0 auto;">
                                            <tr>
                                                <td style="border-radius: 8px; background: linear-gradient(135deg, #667eea 0%%, #764ba2 100%%);">
                                                    <a href="%s" 
                                                       style="display: inline-block; padding: 16px 32px; color: #ffffff; text-decoration: none; font-size: 16px; font-weight: 600; border-radius: 8px;">
                                                        Reset Password →
                                                    </a>
                                                </td>
                                            </tr>
                                        </table>
                                        
                                        <p style="margin: 30px 0 20px; color: #6b7280; font-size: 14px; line-height: 1.6;">
                                            Or copy and paste this link into your browser:
                                        </p>
                                        
                                        <div style="padding: 12px; background-color: #f3f4f6; border-radius: 6px; word-break: break-all;">
                                            <a href="%s" style="color: #667eea; text-decoration: none; font-size: 14px;">%s</a>
                                        </div>
                                        
                                        <div style="margin: 30px 0; padding: 16px; background-color: #fef3c7; border-left: 4px solid #f59e0b; border-radius: 6px;">
                                            <p style="margin: 0; color: #92400e; font-size: 14px; line-height: 1.6;">
                                                ⚠️ <strong>This link will expire in 1 hour.</strong>
                                            </p>
                                        </div>
                                        
                                        <p style="margin: 0 0 10px; color: #6b7280; font-size: 14px; line-height: 1.6;">
                                            If you didn't request a password reset, you can safely ignore this email. Your password will remain unchanged.
                                        </p>
                                    </td>
                                </tr>
                                
                                <!-- Footer -->
                                <tr>
                                    <td style="padding: 30px 40px; background-color: #f9fafb; border-radius: 0 0 12px 12px; text-align: center;">
                                        <p style="margin: 0 0 10px; color: #6b7280; font-size: 14px;">
                                            Best regards,<br>
                                            <strong>Finance Coach Team</strong>
                                        </p>
                                        <p style="margin: 10px 0 0; color: #9ca3af; font-size: 12px;">
                                            © 2025 Finance Coach. All rights reserved.
                                        </p>
                                    </td>
                                </tr>
                                
                            </table>
                        </td>
                    </tr>
                </table>
            </body>
            </html>
            """, displayName(userName), resetLink, resetLink, resetLink);
    }

    /**
     * Build welcome email HTML (bonus)
     */
    private String buildWelcomeEmailHtml(String userName) {
        return String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Welcome to Finance Coach</title>
            </head>
            <body style="margin: 0; padding: 0; font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background-color: #f3f4f6;">
                <table role="presentation" style="width: 100%%; border-collapse: collapse;">
                    <tr>
                        <td align="center" style="padding: 40px 0;">
                            <table role="presentation" style="width: 600px; border-collapse: collapse; background-color: #ffffff; border-radius: 12px; box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);">
                                
                                <!-- Header -->
                                <tr>
                                    <td style="padding: 40px 40px 20px; text-align: center; background: linear-gradient(135deg, #667eea 0%%, #764ba2 100%%); border-radius: 12px 12px 0 0;">
                                        <div style="font-size: 48px; margin-bottom: 10px;">🎉</div>
                                        <h1 style="margin: 0; color: #ffffff; font-size: 28px; font-weight: 600;">Welcome to Finance Coach!</h1>
                                    </td>
                                </tr>
                                
                                <!-- Content -->
                                <tr>
                                    <td style="padding: 40px;">
                                        <p style="margin: 0 0 20px; color: #4b5563; font-size: 16px; line-height: 1.6;">
                                            Hi %s,
                                        </p>
                                        
                                        <p style="margin: 0 0 20px; color: #4b5563; font-size: 16px; line-height: 1.6;">
                                            Welcome to Finance Coach! We're excited to help you take control of your finances with the power of AI.
                                        </p>
                                        
                                        <h3 style="margin: 30px 0 15px; color: #1f2937; font-size: 18px; font-weight: 600;">Get Started:</h3>
                                        
                                        <ul style="margin: 0 0 20px; padding-left: 20px; color: #4b5563; font-size: 16px; line-height: 1.8;">
                                            <li>Connect your bank accounts</li>
                                            <li>Set up your budgets</li>
                                            <li>Chat with your AI financial coach</li>
                                            <li>Track your spending and savings</li>
                                        </ul>
                                        
                                        <table role="presentation" style="margin: 30px auto;">
                                            <tr>
                                                <td style="border-radius: 8px; background: linear-gradient(135deg, #667eea 0%%, #764ba2 100%%);">
                                                    <a href="%s/dashboard" 
                                                       style="display: inline-block; padding: 16px 32px; color: #ffffff; text-decoration: none; font-size: 16px; font-weight: 600; border-radius: 8px;">
                                                        Go to Dashboard →
                                                    </a>
                                                </td>
                                            </tr>
                                        </table>
                                        
                                        <p style="margin: 30px 0 0; color: #6b7280; font-size: 14px; line-height: 1.6;">
                                            Need help getting started? Check out our <a href="%s/help" style="color: #667eea; text-decoration: none;">help center</a> or reply to this email.
                                        </p>
                                    </td>
                                </tr>
                                
                                <!-- Footer -->
                                <tr>
                                    <td style="padding: 30px 40px; background-color: #f9fafb; border-radius: 0 0 12px 12px; text-align: center;">
                                        <p style="margin: 0 0 10px; color: #6b7280; font-size: 14px;">
                                            Best regards,<br>
                                            <strong>Finance Coach Team</strong>
                                        </p>
                                        <p style="margin: 10px 0 0; color: #9ca3af; font-size: 12px;">
                                            © 2025 Finance Coach. All rights reserved.
                                        </p>
                                    </td>
                                </tr>
                                
                            </table>
                        </td>
                    </tr>
                </table>
            </body>
            </html>
            """, displayName(userName), frontendUrl, frontendUrl);
    }
}
