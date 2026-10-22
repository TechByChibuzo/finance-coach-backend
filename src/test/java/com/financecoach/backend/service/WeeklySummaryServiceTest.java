package com.financecoach.backend.service;

import com.financecoach.backend.dto.RecurringChargeResponse;
import com.financecoach.backend.dto.WeeklySummary;
import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.model.User;
import com.financecoach.backend.repository.TransactionRepository;
import com.financecoach.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WeeklySummaryServiceTest {

    // A Monday: last week is Oct 19-25, the week before Oct 12-18
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 26);

    @Mock private TransactionRepository transactionRepository;
    @Mock private UserRepository userRepository;
    @Mock private RecurringChargeService recurringChargeService;
    @Mock private EmailService emailService;

    private WeeklySummaryService service;
    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        service = new WeeklySummaryService(transactionRepository, userRepository, recurringChargeService, emailService);
        userId = UUID.randomUUID();
        user = new User("jane@example.com", "hash", "Jane");
        user.setId(userId);
        lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    }

    @Test
    void buildSummary_comparesLastWeekWithTheWeekBefore_andListsWhatsDueThisWeek() {
        when(transactionRepository.findByUserIdAndDateBetween(userId, LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 25)))
                .thenReturn(List.of(
                        tx("2026-10-20", "FOOD_AND_DRINK", "60.00"),
                        tx("2026-10-22", "FOOD_AND_DRINK", "40.00"),
                        tx("2026-10-23", "TRAVEL", "300.00"),
                        tx("2026-10-24", "ENTERTAINMENT", "15.00"),
                        tx("2026-10-24", "PERSONAL_CARE", "10.00"),
                        tx("2026-10-25", "TRANSFER_OUT", "500.00"),
                        tx("2026-10-14", "FOOD_AND_DRINK", "200.00")));
        when(recurringChargeService.findRecurring(userId)).thenReturn(List.of(
                charge("Netflix", "2026-10-28"),
                charge("Rent", "2026-11-02"), // more than a week away
                charge("Spotify", "2026-10-26")));

        WeeklySummary summary = service.buildSummary(userId, MONDAY).orElseThrow();

        assertThat(summary.weekStart()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(summary.weekEnd()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(summary.spent()).isEqualByComparingTo("425.00");
        assertThat(summary.previousWeekSpent()).isEqualByComparingTo("200.00");
        assertThat(summary.topCategories()).extracting(WeeklySummary.CategoryTotal::category)
                .containsExactly("TRAVEL", "FOOD_AND_DRINK", "ENTERTAINMENT");
        assertThat(summary.upcoming()).extracting(RecurringChargeResponse::merchant).containsExactly("Spotify", "Netflix");
    }

    @Test
    void buildSummary_withNothingToSay_isEmpty() {
        when(transactionRepository.findByUserIdAndDateBetween(eq(userId), any(), any())).thenReturn(List.of());
        when(recurringChargeService.findRecurring(userId)).thenReturn(List.of());

        assertThat(service.buildSummary(userId, MONDAY)).isEmpty();
    }

    @Test
    void sendFor_sendsOnce_claimingTheWeekFirst() {
        when(transactionRepository.findByUserIdAndDateBetween(eq(userId), any(), any()))
                .thenReturn(List.of(tx("2026-10-20", "FOOD_AND_DRINK", "60.00")));
        when(userRepository.claimWeeklySummary(userId, LocalDate.of(2026, 10, 19))).thenReturn(1, 0);

        assertThat(service.sendFor(userId, MONDAY)).isTrue();
        assertThat(service.sendFor(userId, MONDAY)).isFalse(); // already sent, e.g. by another instance

        verify(emailService).sendWeeklySummary(eq("jane@example.com"), eq("Jane"), any());
    }

    @Test
    void sendFor_aUserWhoTurnedItOff_getsNothing() {
        user.setWeeklySummaryEmails(false);

        assertThat(service.sendFor(userId, MONDAY)).isFalse();

        verify(transactionRepository, never()).findByUserIdAndDateBetween(any(), any(), any());
        verify(emailService, never()).sendWeeklySummary(any(), any(), any());
    }

    @Test
    void sendWeeklySummaries_keepsGoingWhenOneUserFails() {
        UUID broken = UUID.randomUUID();
        when(transactionRepository.findDistinctUserIds()).thenReturn(List.of(broken, userId));
        when(userRepository.findById(broken)).thenThrow(new RuntimeException("database hiccup"));
        when(transactionRepository.findByUserIdAndDateBetween(eq(userId), any(), any()))
                .thenReturn(List.of(tx("2026-10-20", "FOOD_AND_DRINK", "60.00")));
        when(userRepository.claimWeeklySummary(eq(userId), any())).thenReturn(1);

        service.sendWeeklySummaries();

        verify(emailService).sendWeeklySummary(eq("jane@example.com"), any(), any());
    }

    private Transaction tx(String date, String category, String amount) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setDate(LocalDate.parse(date));
        t.setCategory(category);
        t.setAmount(new BigDecimal(amount));
        return t;
    }

    private static RecurringChargeResponse charge(String merchant, String nextDate) {
        return new RecurringChargeResponse(merchant, null, "ENTERTAINMENT", "subscription", "monthly",
                new BigDecimal("9.99"), null, LocalDate.parse(nextDate).minusMonths(1), LocalDate.parse(nextDate), 4);
    }
}
