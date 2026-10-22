package com.financecoach.backend.service;

import com.financecoach.backend.dto.RecurringChargeResponse;
import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecurringChargeServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private RecurringChargeService service;

    private final UUID userId = UUID.randomUUID();
    private final LocalDate today = LocalDate.of(2026, 10, 21);
    private final List<Transaction> history = new ArrayList<>();

    private void charge(String name, String date, String amount) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setName(name);
        t.setDate(LocalDate.parse(date));
        t.setAmount(new BigDecimal(amount));
        t.setCategory("ENTERTAINMENT");
        history.add(t);
    }

    private List<RecurringChargeResponse> run() {
        when(transactionRepository.findByUserIdAndDateBetween(eq(userId), any(), any())).thenReturn(history);
        return service.findRecurring(userId, today);
    }

    @Test
    void steadyMonthlyCharge_isASubscription_withTheNextDatePredicted() {
        charge("NETFLIX.COM", "2026-07-08", "15.49");
        charge("NETFLIX.COM", "2026-08-08", "15.49");
        charge("NETFLIX.COM", "2026-09-08", "15.49");
        charge("NETFLIX.COM", "2026-10-08", "15.49");

        List<RecurringChargeResponse> result = run();

        assertThat(result).hasSize(1);
        RecurringChargeResponse netflix = result.get(0);
        assertThat(netflix.type()).isEqualTo("subscription");
        assertThat(netflix.frequency()).isEqualTo("monthly");
        assertThat(netflix.amount()).isEqualByComparingTo("15.49");
        assertThat(netflix.nextDate()).isEqualTo(LocalDate.of(2026, 11, 7));
        assertThat(netflix.previousAmount()).isNull();
    }

    @Test
    void latestChargeHigherThanTheSteadyAmount_isReportedAsAPriceChange() {
        charge("SPOTIFY USA", "2026-07-02", "10.99");
        charge("SPOTIFY USA", "2026-08-02", "10.99");
        charge("SPOTIFY USA", "2026-09-02", "10.99");
        charge("SPOTIFY USA", "2026-10-02", "11.99");

        RecurringChargeResponse spotify = run().get(0);

        assertThat(spotify.amount()).isEqualByComparingTo("11.99");
        assertThat(spotify.previousAmount()).isEqualByComparingTo("10.99");
    }

    @Test
    void varyingMonthlyAmount_isABill() {
        charge("CITY ELECTRIC", "2026-07-15", "82.10");
        charge("CITY ELECTRIC", "2026-08-14", "95.40");
        charge("CITY ELECTRIC", "2026-09-15", "88.00");
        charge("CITY ELECTRIC", "2026-10-15", "79.30");

        RecurringChargeResponse electric = run().get(0);

        assertThat(electric.type()).isEqualTo("bill");
        assertThat(electric.amount()).isEqualByComparingTo("85.05"); // median of the four
    }

    @Test
    void subscriptionThatStopped_isLeftOut() {
        charge("HULU", "2026-04-10", "7.99");
        charge("HULU", "2026-05-10", "7.99");
        charge("HULU", "2026-06-10", "7.99");

        assertThat(run()).isEmpty();
    }

    @Test
    void irregularSpendingAndOneOffs_areNotRecurring() {
        charge("STARBUCKS", "2026-09-01", "6.25");
        charge("STARBUCKS", "2026-09-04", "5.10");
        charge("STARBUCKS", "2026-09-20", "7.00");
        charge("STARBUCKS", "2026-10-02", "6.25");
        charge("DELTA AIR", "2026-08-01", "312.40");

        assertThat(run()).isEmpty();
    }

    @Test
    void yearlyCharge_isFound() {
        charge("AMAZON PRIME", "2024-10-25", "139.00");
        charge("AMAZON PRIME", "2025-10-25", "139.00");
        charge("AMAZON PRIME", "2026-10-20", "139.00");
        // The repository returns what's in range; a 13-month window would only hold the last one,
        // so this checks the rhythm logic with a longer history
        RecurringChargeResponse prime = run().get(0);

        assertThat(prime.frequency()).isEqualTo("yearly");
    }

    @Test
    void storeNumbersAndPunctuation_groupTheSameMerchant() {
        Transaction a = new Transaction();
        a.setName("NETFLIX.COM 4521");
        Transaction b = new Transaction();
        b.setName("Netflix.com #9932");
        Transaction c = new Transaction();
        c.setMerchantName("Netflix");
        c.setName("NETFLIX.COM 1");

        assertThat(RecurringChargeService.merchantKey(a)).isEqualTo(RecurringChargeService.merchantKey(b));
        assertThat(RecurringChargeService.merchantKey(c)).isEqualTo("netflix");
    }

    @Test
    void transfersAndMoneyIn_areIgnored() {
        charge("PAYROLL", "2026-08-01", "-2000");
        charge("PAYROLL", "2026-09-01", "-2000");
        charge("PAYROLL", "2026-10-01", "-2000");
        for (String date : List.of("2026-08-05", "2026-09-05", "2026-10-05")) {
            charge("TRANSFER TO SAVINGS", date, "500");
            history.get(history.size() - 1).setCategory("TRANSFER_OUT");
        }

        assertThat(run()).isEmpty();
    }
}
