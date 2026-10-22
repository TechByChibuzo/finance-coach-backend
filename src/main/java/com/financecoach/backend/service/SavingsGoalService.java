package com.financecoach.backend.service;

import com.financecoach.backend.dto.GoalRequest;
import com.financecoach.backend.dto.SavingsGoalResponse;
import com.financecoach.backend.exception.UnauthorizedAccessException;
import com.financecoach.backend.exception.ValidationException;
import com.financecoach.backend.model.BankAccount;
import com.financecoach.backend.model.SavingsGoal;
import com.financecoach.backend.repository.BankAccountRepository;
import com.financecoach.backend.repository.SavingsGoalRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Savings goals and their progress
 */
@Service
public class SavingsGoalService {

    static final int MAX_GOALS = 20;
    // Accounts whose balance is money the user has, not owes
    private static final Set<String> SAVINGS_ACCOUNT_TYPES = Set.of("depository", "investment");

    private final SavingsGoalRepository goalRepository;
    private final BankAccountRepository bankAccountRepository;

    public SavingsGoalService(SavingsGoalRepository goalRepository, BankAccountRepository bankAccountRepository) {
        this.goalRepository = goalRepository;
        this.bankAccountRepository = bankAccountRepository;
    }

    public List<SavingsGoalResponse> getGoals(UUID userId, LocalDate today) {
        Map<UUID, BankAccount> accounts = activeAccounts(userId);
        return goalRepository.findByUserIdOrderByCreatedAtAsc(userId).stream()
                .map(goal -> toResponse(goal, accounts, today))
                .toList();
    }

    public SavingsGoalResponse create(UUID userId, GoalRequest request, LocalDate today) {
        if (goalRepository.countByUserId(userId) >= MAX_GOALS) {
            throw new ValidationException("You can have up to " + MAX_GOALS + " goals.");
        }
        if (request.targetDate() != null && request.targetDate().isBefore(today)) {
            throw new ValidationException("Pick a date that hasn't passed.");
        }
        SavingsGoal goal = new SavingsGoal();
        goal.setUserId(userId);
        apply(goal, request, userId);
        return toResponse(goalRepository.save(goal), activeAccounts(userId), today);
    }

    public SavingsGoalResponse update(UUID userId, UUID goalId, GoalRequest request, LocalDate today) {
        SavingsGoal goal = owned(userId, goalId);
        // A date that has since passed can be kept, but not newly chosen
        if (request.targetDate() != null && request.targetDate().isBefore(today)
                && !request.targetDate().equals(goal.getTargetDate())) {
            throw new ValidationException("Pick a date that hasn't passed.");
        }
        apply(goal, request, userId);
        return toResponse(goalRepository.save(goal), activeAccounts(userId), today);
    }

    /**
     * Adds to (or, with a negative amount, takes from) a goal tracked by hand
     */
    public SavingsGoalResponse addToSaved(UUID userId, UUID goalId, BigDecimal amount, LocalDate today) {
        SavingsGoal goal = owned(userId, goalId);
        if (goal.getAccountId() != null) {
            throw new ValidationException("This goal follows an account's balance, so there's nothing to add by hand.");
        }
        if (amount == null || amount.signum() == 0 || amount.scale() > 2) {
            throw new ValidationException("Enter an amount in dollars and cents.");
        }
        goal.setSavedAmount(goal.getSavedAmount().add(amount).max(BigDecimal.ZERO));
        return toResponse(goalRepository.save(goal), activeAccounts(userId), today);
    }

    public void delete(UUID userId, UUID goalId) {
        goalRepository.delete(owned(userId, goalId));
    }

    private void apply(SavingsGoal goal, GoalRequest request, UUID userId) {
        if (request.accountId() != null) {
            BankAccount account = bankAccountRepository.findById(request.accountId())
                    .filter(a -> a.getUserId().equals(userId) && Boolean.TRUE.equals(a.getIsActive()))
                    .orElseThrow(() -> new UnauthorizedAccessException("bank account"));
            if (!SAVINGS_ACCOUNT_TYPES.contains(account.getAccountType())) {
                throw new ValidationException("Link a savings, checking or investment account.");
            }
        }
        goal.setName(request.name().trim());
        goal.setTargetAmount(request.targetAmount());
        goal.setTargetDate(request.targetDate());
        goal.setAccountId(request.accountId());
        if (request.savedAmount() != null) {
            goal.setSavedAmount(request.savedAmount());
        }
    }

    private SavingsGoal owned(UUID userId, UUID goalId) {
        return goalRepository.findById(goalId)
                .filter(g -> g.getUserId().equals(userId))
                .orElseThrow(() -> new UnauthorizedAccessException("savings goal"));
    }

    private Map<UUID, BankAccount> activeAccounts(UUID userId) {
        return bankAccountRepository.findByUserIdAndIsActive(userId, true).stream()
                .collect(Collectors.toMap(BankAccount::getId, Function.identity()));
    }

    static SavingsGoalResponse toResponse(SavingsGoal goal, Map<UUID, BankAccount> accounts, LocalDate today) {
        // A linked account that's since been disconnected falls back to the amount tracked by hand
        BankAccount account = goal.getAccountId() == null ? null : accounts.get(goal.getAccountId());
        BigDecimal saved = account != null
                ? Objects.requireNonNullElse(account.getCurrentBalance(), BigDecimal.ZERO).max(BigDecimal.ZERO)
                : goal.getSavedAmount();

        BigDecimal target = goal.getTargetAmount();
        BigDecimal remaining = target.subtract(saved).max(BigDecimal.ZERO);
        boolean reached = remaining.signum() == 0;
        int percent = saved.multiply(BigDecimal.valueOf(100)).divide(target, 0, RoundingMode.DOWN).min(BigDecimal.valueOf(100)).intValue();
        boolean pastDue = !reached && goal.getTargetDate() != null && goal.getTargetDate().isBefore(today);

        BigDecimal monthlyNeeded = null;
        if (!reached && goal.getTargetDate() != null && !pastDue) {
            // Months to save in, counting this one: due next month = this month and next
            long months = ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(goal.getTargetDate())) + 1;
            monthlyNeeded = remaining.divide(BigDecimal.valueOf(months), 2, RoundingMode.UP);
        }

        return new SavingsGoalResponse(goal.getId(), goal.getName(), target, goal.getTargetDate(),
                account != null ? account.getId() : null, account != null ? account.getAccountName() : null,
                saved, percent, remaining, monthlyNeeded, reached, pastDue);
    }
}
