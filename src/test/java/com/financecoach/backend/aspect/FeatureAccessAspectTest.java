package com.financecoach.backend.aspect;

import com.financecoach.backend.controller.AICoachController;
import com.financecoach.backend.controller.ExportController;
import com.financecoach.backend.dto.ChatRequest;
import com.financecoach.backend.exception.FeatureNotAvailableException;
import com.financecoach.backend.exception.UsageLimitExceededException;
import com.financecoach.backend.service.AICoachService;
import com.financecoach.backend.service.ExportService;
import com.financecoach.backend.service.SubscriptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs the real FeatureAccessAspect around the controllers to check that the
 * annotations on the endpoints actually enforce plan limits.
 */
@ExtendWith(MockitoExtension.class)
class FeatureAccessAspectTest {

    @Mock
    private SubscriptionService subscriptionService;

    @Mock
    private AICoachService aiCoachService;

    @Mock
    private ExportService exportService;

    @InjectMocks
    private FeatureAccessAspect aspect;

    private UUID userId;
    private AICoachController aiCoachController;
    private ExportController exportController;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));

        aiCoachController = proxy(new AICoachController(aiCoachService));

        ExportController export = new ExportController();
        ReflectionTestUtils.setField(export, "exportService", exportService);
        exportController = proxy(export);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void chat_withinLimit_reservesAUseUpFront_andKeepsIt() {
        when(subscriptionService.hasFeatureAccess(userId, "ai_coach")).thenReturn(true);
        when(aiCoachService.chat(any(), anyString(), anyString())).thenReturn("hi");

        aiCoachController.chat(chatRequest());

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(subscriptionService, aiCoachService);
        order.verify(subscriptionService).reserveUsage(userId, "ai_coach");
        order.verify(aiCoachService).chat(userId, "How am I doing?", "default");
        verify(subscriptionService, never()).releaseUsage(any(), anyString());
        // The atomic reservation enforces the limit; no separate (racy) count check
        verify(subscriptionService, never()).canUseFeature(any(), anyString());
    }

    @Test
    void chat_overLimit_isBlockedBeforeCallingTheAi() {
        when(subscriptionService.hasFeatureAccess(userId, "ai_coach")).thenReturn(true);
        org.mockito.Mockito.doThrow(new UsageLimitExceededException("Usage limit exceeded for ai_coach"))
                .when(subscriptionService).reserveUsage(userId, "ai_coach");

        assertThatThrownBy(() -> aiCoachController.chat(chatRequest()))
                .isInstanceOf(UsageLimitExceededException.class);

        verify(aiCoachService, never()).chat(any(), anyString(), anyString());
        verify(subscriptionService, never()).releaseUsage(any(), anyString());
    }

    @Test
    void chat_withoutAnAuthenticatedUser_isAnAuthenticationError_soItsA401NotA500() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> aiCoachController.chat(chatRequest()))
                .isInstanceOf(org.springframework.security.core.AuthenticationException.class);

        verify(aiCoachService, never()).chat(any(), anyString(), anyString());
    }

    @Test
    void chat_withoutPlanAccess_isBlockedWithoutReservingAUse() {
        when(subscriptionService.hasFeatureAccess(userId, "ai_coach")).thenReturn(false);

        assertThatThrownBy(() -> aiCoachController.chat(chatRequest()))
                .isInstanceOf(FeatureNotAvailableException.class);

        verify(aiCoachService, never()).chat(any(), anyString(), anyString());
    }

    @Test
    void chat_whenTheAiFails_givesTheReservedUseBack() {
        when(subscriptionService.hasFeatureAccess(userId, "ai_coach")).thenReturn(true);
        when(aiCoachService.chat(any(), anyString(), anyString())).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> aiCoachController.chat(chatRequest())).hasMessage("boom");

        verify(subscriptionService).reserveUsage(userId, "ai_coach");
        verify(subscriptionService).releaseUsage(userId, "ai_coach");
    }

    @Test
    void reportEndpoints_shareTheAiCoachLimit() {
        when(subscriptionService.hasFeatureAccess(userId, "ai_coach")).thenReturn(true);
        org.mockito.Mockito.doThrow(new UsageLimitExceededException("Usage limit exceeded for ai_coach"))
                .when(subscriptionService).reserveUsage(userId, "ai_coach");

        assertThatThrownBy(() -> aiCoachController.getWeeklySummary())
                .isInstanceOf(UsageLimitExceededException.class);
        assertThatThrownBy(() -> aiCoachController.getMonthlyReport())
                .isInstanceOf(UsageLimitExceededException.class);
        assertThatThrownBy(() -> aiCoachController.analyzeCategory("FOOD_AND_DRINK"))
                .isInstanceOf(UsageLimitExceededException.class);
    }

    @Test
    void clearSession_isNotGated() {
        aiCoachController.clearSession("default");

        verify(aiCoachService).clearSession(userId, "default");
        verify(subscriptionService, never()).canUseFeature(any(), anyString());
    }

    @Test
    void export_onFreePlan_isBlocked() {
        when(subscriptionService.canUseFeature(userId, "export_reports")).thenReturn(false);
        when(subscriptionService.hasFeatureAccess(userId, "export_reports")).thenReturn(false);

        assertThatThrownBy(() -> exportController.exportTransactions(null))
                .isInstanceOf(FeatureNotAvailableException.class)
                .hasMessageContaining("Upgrade to Premium");

        verify(exportService, never()).exportTransactionsCSV(any());
    }

    @Test
    void export_onPaidPlan_runs() {
        when(subscriptionService.canUseFeature(userId, "export_reports")).thenReturn(true);
        when(exportService.exportTransactionsCSV(userId)).thenReturn("https://example.com/file.csv");

        assertThat(exportController.exportTransactions(null).getStatusCode().is2xxSuccessful()).isTrue();
    }

    private ChatRequest chatRequest() {
        ChatRequest request = new ChatRequest();
        request.setMessage("How am I doing?");
        return request;
    }

    private <T> T proxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(aspect);
        return factory.getProxy();
    }
}
