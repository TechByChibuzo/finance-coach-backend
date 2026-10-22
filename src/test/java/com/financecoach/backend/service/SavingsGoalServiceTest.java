package com.financecoach.backend.service;

import com.financecoach.backend.dto.GoalRequest;
import com.financecoach.backend.dto.SavingsGoalResponse;
import com.financecoach.backend.exception.UnauthorizedAccessException;
import com.financecoach.backend.exception.ValidationException;
import com.financecoach.backend.model.BankAccount;
import com.financecoach.backend.model.SavingsGoal;
import com.financecoach.backend.repository.BankAccountRepository;
import com.financecoach.backend.repository.SavingsGoalRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SavingsGoalServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    @Mock private SavingsGoalRepository goalRepository;
    @Mock private BankAccountRepository bankAccountRepository;

    private SavingsGoalService service;
    private UUID userId;
    private BankAccount savings;

    @BeforeEach
    void setUp() {
        service = new SavingsGoalService(goalRepository, bankAccountRepository);
        userId = UUID.randomUUID();
        savings = new BankAccount();
        savings.setId(UUID.randomUUID());
        savings.setUserId(userId);
        savings.setAccountName("High Yield Savings");
        savings.setAccountType("depository");
        savings.setCurrentBalance(new BigDecimal("2500.00"));
        savings.setIsActive(true);
        lenient().when(bankAccountRepository.findByUserIdAndIsActive(userId, true)).thenReturn(List.of(savings));
        lenient().when(goalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // --- progress ---

    @Test
    void getGoals_trackedByHand_showsWhatToSaveEachMonthToMakeTheDate() {
        SavingsGoal trip = goal("Japan trip", "6000.00", LocalDate.of(2027, 3, 1), null, "1200.00");
        when(goalRepository.findByUserIdOrderByCreatedAtAsc(userId)).thenReturn(List.of(trip));

        SavingsGoalResponse response = service.getGoals(userId, TODAY).get(0);

        assertThat(response.saved()).isEqualByComparingTo("1200.00");
        assertThat(response.percent()).isEqualTo(20);
        assertThat(response.remaining()).isEqualByComparingTo("4800.00");
        // October through March: 6 months to save in
        assertThat(response.monthlyNeeded()).isEqualByComparingTo("800.00");
        assertThat(response.reached()).isFalse();
        assertThat(response.pastDue()).isFalse();
        assertThat(response.accountId()).isNull();
    }

    @Test
    void getGoals_linkedToAnAccount_followsItsBalance() {
        SavingsGoal emergency = goal("Emergency fund", "10000.00", null, savings.getId(), "0");
        when(goalRepository.findByUserIdOrderByCreatedAtAsc(userId)).thenReturn(List.of(emergency));

        SavingsGoalResponse response = service.getGoals(userId, TODAY).get(0);

        assertThat(response.saved()).isEqualByComparingTo("2500.00");
        assertThat(response.percent()).isEqualTo(25);
        assertThat(response.accountName()).isEqualTo("High Yield Savings");
        assertThat(response.monthlyNeeded()).isNull(); // no date
    }

    @Test
    void getGoals_whenTheLinkedAccountIsDisconnected_fallsBackToTheAmountTrackedByHand() {
        SavingsGoal car = goal("Car", "5000.00", null, UUID.randomUUID(), "300.00");
        when(goalRepository.findByUserIdOrderByCreatedAtAsc(userId)).thenReturn(List.of(car));

        SavingsGoalResponse response = service.getGoals(userId, TODAY).get(0);

        assertThat(response.saved()).isEqualByComparingTo("300.00");
        assertThat(response.accountId()).isNull();
    }

    @Test
    void getGoals_reachedAndPastDueGoals() {
        SavingsGoal reached = goal("Laptop", "2000.00", LocalDate.of(2026, 9, 1), savings.getId(), "0");
        SavingsGoal late = goal("Couch", "1500.00", LocalDate.of(2026, 9, 1), null, "500.00");
        SavingsGoal dueThisMonth = goal("Gift", "100.00", LocalDate.of(2026, 10, 30), null, "40.00");
        when(goalRepository.findByUserIdOrderByCreatedAtAsc(userId)).thenReturn(List.of(reached, late, dueThisMonth));

        List<SavingsGoalResponse> goals = service.getGoals(userId, TODAY);

        assertThat(goals.get(0).reached()).isTrue();
        assertThat(goals.get(0).percent()).isEqualTo(100);
        assertThat(goals.get(0).pastDue()).isFalse();
        assertThat(goals.get(0).monthlyNeeded()).isNull();
        assertThat(goals.get(1).pastDue()).isTrue();
        assertThat(goals.get(1).monthlyNeeded()).isNull();
        assertThat(goals.get(2).monthlyNeeded()).isEqualByComparingTo("60.00");
    }

    // --- creating and editing ---

    @Test
    void create_savesTheGoal_trimmingItsName() {
        when(bankAccountRepository.findById(savings.getId())).thenReturn(Optional.of(savings));
        SavingsGoalResponse response = service.create(userId,
                new GoalRequest("  Emergency fund ", new BigDecimal("10000"), null, savings.getId(), null), TODAY);

        assertThat(response.name()).isEqualTo("Emergency fund");
        assertThat(response.saved()).isEqualByComparingTo("2500.00");
    }

    @Test
    void create_refusesAPastDate_tooManyGoals_andAccountsThatArentTheUsersSavings() {
        assertThatThrownBy(() -> service.create(userId,
                new GoalRequest("Trip", new BigDecimal("100"), TODAY.minusDays(1), null, null), TODAY))
                .isInstanceOf(ValidationException.class);

        BankAccount card = new BankAccount();
        card.setId(UUID.randomUUID());
        card.setUserId(userId);
        card.setAccountType("credit");
        card.setIsActive(true);
        when(bankAccountRepository.findById(card.getId())).thenReturn(Optional.of(card));
        assertThatThrownBy(() -> service.create(userId,
                new GoalRequest("Trip", new BigDecimal("100"), null, card.getId(), null), TODAY))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("savings, checking or investment");

        BankAccount someoneElses = new BankAccount();
        someoneElses.setId(UUID.randomUUID());
        someoneElses.setUserId(UUID.randomUUID());
        someoneElses.setIsActive(true);
        when(bankAccountRepository.findById(someoneElses.getId())).thenReturn(Optional.of(someoneElses));
        assertThatThrownBy(() -> service.create(userId,
                new GoalRequest("Trip", new BigDecimal("100"), null, someoneElses.getId(), null), TODAY))
                .isInstanceOf(UnauthorizedAccessException.class);

        when(goalRepository.countByUserId(userId)).thenReturn((long) SavingsGoalService.MAX_GOALS);
        assertThatThrownBy(() -> service.create(userId,
                new GoalRequest("One more", new BigDecimal("100"), null, null, null), TODAY))
                .hasMessageContaining("up to 20 goals");
        verify(goalRepository, never()).save(any());
    }

    @Test
    void update_canKeepADateThatHasSincePassed_butNotChooseANewPastOne() {
        SavingsGoal couch = goal("Couch", "1500.00", LocalDate.of(2026, 9, 1), null, "500.00");
        when(goalRepository.findById(couch.getId())).thenReturn(Optional.of(couch));

        service.update(userId, couch.getId(),
                new GoalRequest("Sofa", new BigDecimal("1800"), LocalDate.of(2026, 9, 1), null, null), TODAY);
        assertThat(couch.getName()).isEqualTo("Sofa");
        assertThat(couch.getSavedAmount()).isEqualByComparingTo("500.00"); // unchanged without savedAmount

        assertThatThrownBy(() -> service.update(userId, couch.getId(),
                new GoalRequest("Sofa", new BigDecimal("1800"), LocalDate.of(2026, 8, 1), null, null), TODAY))
                .isInstanceOf(ValidationException.class);
    }

    // --- adding money by hand ---

    @Test
    void addToSaved_addsAndTakesOut_neverBelowZero() {
        SavingsGoal trip = goal("Trip", "1000.00", null, null, "100.00");
        when(goalRepository.findById(trip.getId())).thenReturn(Optional.of(trip));

        assertThat(service.addToSaved(userId, trip.getId(), new BigDecimal("50.25"), TODAY).saved()).isEqualByComparingTo("150.25");
        assertThat(service.addToSaved(userId, trip.getId(), new BigDecimal("-500"), TODAY).saved()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> service.addToSaved(userId, trip.getId(), new BigDecimal("1.234"), TODAY))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void addToSaved_onAGoalFollowingAnAccount_isRefused() {
        SavingsGoal linked = goal("Emergency", "1000.00", null, savings.getId(), "0");
        when(goalRepository.findById(linked.getId())).thenReturn(Optional.of(linked));

        assertThatThrownBy(() -> service.addToSaved(userId, linked.getId(), BigDecimal.TEN, TODAY))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void delete_onlyTheUsersOwnGoal() {
        SavingsGoal mine = goal("Mine", "100", null, null, "0");
        SavingsGoal theirs = goal("Theirs", "100", null, null, "0");
        theirs.setUserId(UUID.randomUUID());
        when(goalRepository.findById(mine.getId())).thenReturn(Optional.of(mine));
        when(goalRepository.findById(theirs.getId())).thenReturn(Optional.of(theirs));

        service.delete(userId, mine.getId());
        assertThatThrownBy(() -> service.delete(userId, theirs.getId())).isInstanceOf(UnauthorizedAccessException.class);

        verify(goalRepository).delete(mine);
        verify(goalRepository, never()).delete(theirs);
    }

    private SavingsGoal goal(String name, String target, LocalDate date, UUID accountId, String saved) {
        SavingsGoal goal = new SavingsGoal();
        goal.setId(UUID.randomUUID());
        goal.setUserId(userId);
        goal.setName(name);
        goal.setTargetAmount(new BigDecimal(target));
        goal.setTargetDate(date);
        goal.setAccountId(accountId);
        goal.setSavedAmount(new BigDecimal(saved));
        return goal;
    }
}
