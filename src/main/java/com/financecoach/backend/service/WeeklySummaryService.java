package com.financecoach.backend.service;

import com.financecoach.backend.dto.RecurringChargeResponse;
import com.financecoach.backend.dto.WeeklySummary;
import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.model.User;
import com.financecoach.backend.repository.TransactionRepository;
import com.financecoach.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The Monday email: what the user spent last week against the week before, where it went,
 * and the bills due in the coming week.
 */
@Service
public class WeeklySummaryService {

    private static final Logger logger = LoggerFactory.getLogger(WeeklySummaryService.class);

    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final RecurringChargeService recurringChargeService;
    private final EmailService emailService;

    public WeeklySummaryService(TransactionRepository transactionRepository, UserRepository userRepository,
                                RecurringChargeService recurringChargeService, EmailService emailService) {
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
        this.recurringChargeService = recurringChargeService;
        this.emailService = emailService;
    }

    // Mondays at 14:00 UTC: morning across US time zones
    @Scheduled(cron = "0 0 14 * * MON", zone = "UTC")
    public void sendWeeklySummaries() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int sent = 0;
        for (UUID userId : transactionRepository.findDistinctUserIds()) {
            try {
                if (sendFor(userId, today)) {
                    sent++;
                }
            } catch (Exception e) {
                logger.warn("Weekly summary failed for user {}: {}", userId, e.getMessage());
            }
        }
        logger.info("Sent {} weekly summaries", sent);
    }

    /**
     * Sends the user's summary for the week before today, unless they turned it off, there's
     * nothing to say, or it was already sent. Returns whether it was sent.
     */
    boolean sendFor(UUID userId, LocalDate today) {
        Optional<User> user = userRepository.findById(userId);
        if (user.isEmpty() || Boolean.FALSE.equals(user.get().getWeeklySummaryEmails())) {
            return false;
        }
        Optional<WeeklySummary> summary = buildSummary(userId, today);
        if (summary.isEmpty() || userRepository.claimWeeklySummary(userId, summary.get().weekStart()) == 0) {
            return false;
        }
        emailService.sendWeeklySummary(user.get().getEmail(), user.get().getFullName(), summary.get());
        return true;
    }

    Optional<WeeklySummary> buildSummary(UUID userId, LocalDate today) {
        LocalDate weekStart = today.with(TemporalAdjusters.previous(DayOfWeek.MONDAY));
        LocalDate weekEnd = weekStart.plusDays(6);
        LocalDate previousStart = weekStart.minusDays(7);

        List<Transaction> spending = transactionRepository.findByUserIdAndDateBetween(userId, previousStart, weekEnd).stream()
                .filter(TransactionFilters::isSpending)
                .toList();
        List<Transaction> lastWeek = spending.stream().filter(t -> !t.getDate().isBefore(weekStart)).toList();
        BigDecimal spent = total(lastWeek);
        BigDecimal previousSpent = total(spending.stream().filter(t -> t.getDate().isBefore(weekStart)).toList());

        List<WeeklySummary.CategoryTotal> topCategories = lastWeek.stream()
                .collect(Collectors.groupingBy(t -> t.getCategory() != null ? t.getCategory() : "OTHER",
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)))
                .entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(3)
                .map(e -> new WeeklySummary.CategoryTotal(e.getKey(), e.getValue()))
                .toList();

        List<RecurringChargeResponse> upcoming = recurringChargeService.findRecurring(userId).stream()
                .filter(r -> r.nextDate() != null && !r.nextDate().isBefore(today) && r.nextDate().isBefore(today.plusDays(7)))
                .sorted(Comparator.comparing(RecurringChargeResponse::nextDate))
                .toList();

        if (spent.signum() == 0 && previousSpent.signum() == 0 && upcoming.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new WeeklySummary(weekStart, weekEnd, spent, previousSpent, topCategories, upcoming));
    }

    private static BigDecimal total(List<Transaction> transactions) {
        return transactions.stream().map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
