package com.financecoach.backend.service;

import com.financecoach.backend.dto.RecurringChargeResponse;
import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Finds bills and subscriptions in the user's history: the same merchant charging at a steady
 * monthly, quarterly or yearly rhythm. Computed on request from the last 13 months.
 */
@Service
public class RecurringChargeService {

    private static final int MIN_OCCURRENCES = 3;
    private static final double REGULAR_SHARE = 0.75;      // share of gaps that must fit the rhythm
    private static final double AMOUNT_TOLERANCE = 0.25;   // a bill can vary this much and still repeat
    private static final double SAME_AMOUNT = 0.02;        // within this, the amount counts as unchanged
    // Housing, utilities and loans are bills even when the amount never changes (rent)
    private static final java.util.Set<String> BILL_CATEGORIES =
            java.util.Set.of("RENT_AND_UTILITIES", "LOAN_PAYMENTS", "BANK_FEES", "GOVERNMENT_AND_NON_PROFIT", "MEDICAL");

    private record Rhythm(String name, int days, int tolerance) {
    }

    private static final List<Rhythm> RHYTHMS = List.of(
            new Rhythm("monthly", 30, 4),
            new Rhythm("quarterly", 91, 10),
            new Rhythm("yearly", 365, 15));

    private final TransactionRepository transactionRepository;

    public RecurringChargeService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    public List<RecurringChargeResponse> findRecurring(UUID userId) {
        return findRecurring(userId, LocalDate.now());
    }

    List<RecurringChargeResponse> findRecurring(UUID userId, LocalDate today) {
        Map<String, List<Transaction>> byMerchant = new LinkedHashMap<>();
        for (Transaction t : transactionRepository.findByUserIdAndDateBetween(userId, today.minusMonths(13), today)) {
            if (TransactionFilters.isSpending(t) && !Boolean.TRUE.equals(t.getPending())) {
                byMerchant.computeIfAbsent(merchantKey(t), k -> new ArrayList<>()).add(t);
            }
        }

        List<RecurringChargeResponse> result = new ArrayList<>();
        for (List<Transaction> charges : byMerchant.values()) {
            if (charges.size() < MIN_OCCURRENCES) {
                continue;
            }
            charges.sort(Comparator.comparing(Transaction::getDate));
            detect(charges, today).ifPresent(result::add);
        }
        result.sort(Comparator.comparing(RecurringChargeResponse::nextDate));
        return result;
    }

    private Optional<RecurringChargeResponse> detect(List<Transaction> charges, LocalDate today) {
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < charges.size(); i++) {
            gaps.add(ChronoUnit.DAYS.between(charges.get(i - 1).getDate(), charges.get(i).getDate()));
        }
        long medianGap = median(gaps.stream().map(BigDecimal::valueOf).toList()).longValue();

        Rhythm rhythm = RHYTHMS.stream()
                .filter(r -> Math.abs(medianGap - r.days()) <= r.tolerance())
                .findFirst().orElse(null);
        if (rhythm == null) {
            return Optional.empty();
        }
        long regular = gaps.stream().filter(g -> Math.abs(g - rhythm.days()) <= rhythm.tolerance()).count();
        if (regular < REGULAR_SHARE * gaps.size()) {
            return Optional.empty();
        }

        List<BigDecimal> amounts = charges.stream().map(Transaction::getAmount).toList();
        BigDecimal typical = median(amounts);
        boolean consistent = amounts.stream().allMatch(a -> withinShare(a, typical, AMOUNT_TOLERANCE));
        if (!consistent) {
            return Optional.empty();
        }

        Transaction last = charges.get(charges.size() - 1);
        LocalDate nextDate = last.getDate().plusDays(rhythm.days());
        // Stopped: more than half a cycle overdue
        if (today.isAfter(nextDate.plusDays(rhythm.days() / 2))) {
            return Optional.empty();
        }

        // Price change: the earlier charges agree with each other, and the latest one doesn't
        List<BigDecimal> earlier = amounts.subList(0, amounts.size() - 1);
        BigDecimal earlierTypical = median(earlier);
        boolean earlierSteady = earlier.stream().allMatch(a -> withinShare(a, earlierTypical, SAME_AMOUNT));
        BigDecimal previousAmount = earlierSteady && !withinShare(last.getAmount(), earlierTypical, SAME_AMOUNT)
                ? earlierTypical : null;

        // Same amount every time, or a steady amount that just changed price
        boolean steady = previousAmount != null
                || amounts.subList(Math.max(0, amounts.size() - 3), amounts.size()).stream()
                        .allMatch(a -> withinShare(a, last.getAmount(), SAME_AMOUNT));
        boolean subscription = steady && !BILL_CATEGORIES.contains(last.getCategory());

        String logo = charges.stream().map(Transaction::getLogoUrl).filter(Objects::nonNull)
                .reduce((a, b) -> b).orElse(null);

        return Optional.of(new RecurringChargeResponse(
                displayName(last),
                logo,
                last.getCategory(),
                subscription ? "subscription" : "bill",
                rhythm.name(),
                steady ? last.getAmount() : typical,
                previousAmount,
                last.getDate(),
                nextDate,
                charges.size()));
    }

    // "NETFLIX.COM 4521" and "Netflix.com #9932" are the same merchant
    static String merchantKey(Transaction t) {
        String name = displayName(t).toLowerCase(Locale.ROOT)
                .replaceAll("[0-9#*]+", " ")
                .replaceAll("[^a-z&. ]", " ")
                .trim()
                .replaceAll("\\s+", " ");
        String[] words = name.split(" ");
        return String.join(" ", Arrays.copyOf(words, Math.min(3, words.length)));
    }

    private static String displayName(Transaction t) {
        return t.getMerchantName() != null && !t.getMerchantName().isBlank() ? t.getMerchantName() : t.getName();
    }

    private static boolean withinShare(BigDecimal value, BigDecimal reference, double share) {
        if (reference.signum() == 0) {
            return value.signum() == 0;
        }
        return value.subtract(reference).abs()
                .compareTo(reference.abs().multiply(BigDecimal.valueOf(share))) <= 0;
    }

    private static BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1
                ? sorted.get(n / 2)
                : sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }
}
