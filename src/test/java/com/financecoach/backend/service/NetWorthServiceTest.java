package com.financecoach.backend.service;

import com.financecoach.backend.dto.networth.ManualAssetRequest;
import com.financecoach.backend.dto.networth.ManualAssetResponse;
import com.financecoach.backend.dto.networth.ManualLiabilityRequest;
import com.financecoach.backend.dto.networth.ManualLiabilityResponse;
import com.financecoach.backend.dto.networth.NetWorthSnapshotDTO;
import com.financecoach.backend.dto.networth.NetWorthSummaryDTO;
import com.financecoach.backend.model.BankAccount;
import com.financecoach.backend.model.ManualAsset;
import com.financecoach.backend.model.ManualLiability;
import com.financecoach.backend.model.NetWorthSnapshot;
import com.financecoach.backend.model.User;
import com.financecoach.backend.repository.BankAccountRepository;
import com.financecoach.backend.repository.HoldingRepository;
import com.financecoach.backend.repository.ManualAssetRepository;
import com.financecoach.backend.repository.ManualLiabilityRepository;
import com.financecoach.backend.repository.NetWorthSnapshotRepository;
import com.financecoach.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NetWorthServiceTest {

    @Mock private BankAccountRepository bankAccountRepository;
    @Mock private HoldingRepository holdingRepository;
    @Mock private ManualAssetRepository manualAssetRepository;
    @Mock private ManualLiabilityRepository manualLiabilityRepository;
    @Mock private NetWorthSnapshotRepository snapshotRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks
    private NetWorthService netWorthService;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
    }

    // --- calculateNetWorth ---

    @Test
    void calculateNetWorth_addsAssetsAndSubtractsLiabilities() {
        stubBalances(userId,
                List.of(account("1500.00"), account("2500.50"), account(null)),
                List.of(account("-300.00"), account("200.00")),
                "10000.00", "25000.00", "8000.00");

        NetWorthSummaryDTO summary = netWorthService.calculateNetWorth(userId);

        assertThat(summary.getCashBalance()).isEqualByComparingTo("4000.50");
        assertThat(summary.getInvestmentsValue()).isEqualByComparingTo("10000.00");
        assertThat(summary.getManualAssetsValue()).isEqualByComparingTo("25000.00");
        assertThat(summary.getTotalAssets()).isEqualByComparingTo("39000.50");
        // Credit balances count as debt whatever their sign
        assertThat(summary.getCreditCardDebt()).isEqualByComparingTo("500.00");
        assertThat(summary.getManualLiabilities()).isEqualByComparingTo("8000.00");
        assertThat(summary.getTotalLiabilities()).isEqualByComparingTo("8500.00");
        assertThat(summary.getNetWorth()).isEqualByComparingTo("30500.50");
    }

    @Test
    void calculateNetWorth_creditAccountWithoutBalance_countsAsZero() {
        stubBalances(userId, List.of(), List.of(account(null), account("120.00")), null, null, null);

        NetWorthSummaryDTO summary = netWorthService.calculateNetWorth(userId);

        assertThat(summary.getCreditCardDebt()).isEqualByComparingTo("120.00");
        assertThat(summary.getNetWorth()).isEqualByComparingTo("-120.00");
    }

    @Test
    void calculateNetWorth_withNoData_isZero_andNullSumsAreTreatedAsZero() {
        stubBalances(userId, List.of(), List.of(), null, null, null);

        NetWorthSummaryDTO summary = netWorthService.calculateNetWorth(userId);

        assertThat(summary.getTotalAssets()).isEqualByComparingTo("0");
        assertThat(summary.getTotalLiabilities()).isEqualByComparingTo("0");
        assertThat(summary.getNetWorth()).isEqualByComparingTo("0");
    }

    // --- history ---

    @Test
    void getNetWorthHistory_mapsSnapshotsToDtos() {
        LocalDate start = LocalDate.of(2026, 9, 1);
        LocalDate end = LocalDate.of(2026, 9, 30);
        NetWorthSnapshot snapshot = NetWorthSnapshot.builder()
                .userId(userId).snapshotDate(LocalDate.of(2026, 9, 15))
                .totalAssets(new BigDecimal("1000")).totalLiabilities(new BigDecimal("400"))
                .netWorth(new BigDecimal("600")).build();
        when(snapshotRepository.findByUserIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(userId, start, end))
                .thenReturn(List.of(snapshot));

        List<NetWorthSnapshotDTO> history = netWorthService.getNetWorthHistory(userId, start, end);

        assertThat(history).hasSize(1);
        assertThat(history.get(0).getDate()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(history.get(0).getNetWorth()).isEqualByComparingTo("600");
        assertThat(history.get(0).getTotalAssets()).isEqualByComparingTo("1000");
        assertThat(history.get(0).getTotalLiabilities()).isEqualByComparingTo("400");
    }

    // --- manual assets ---

    @Test
    void getAllAssets_returnsTheUsersAssets() {
        when(manualAssetRepository.findByUserId(userId)).thenReturn(List.of(asset(userId, "House", "350000")));

        List<ManualAssetResponse> assets = netWorthService.getAllAssets(userId);

        assertThat(assets).extracting(ManualAssetResponse::getName).containsExactly("House");
    }

    @Test
    void addAsset_savesItForTheUser() {
        when(manualAssetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ManualAssetResponse response = netWorthService.addAsset(userId, assetRequest("Car", "vehicle", "18000"));

        ArgumentCaptor<ManualAsset> saved = ArgumentCaptor.forClass(ManualAsset.class);
        verify(manualAssetRepository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(response.getName()).isEqualTo("Car");
        assertThat(response.getValue()).isEqualByComparingTo("18000");
    }

    @Test
    void updateAsset_byOwner_updatesFields() {
        ManualAsset existing = asset(userId, "Car", "18000");
        when(manualAssetRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(manualAssetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ManualAssetResponse response = netWorthService.updateAsset(userId, existing.getId(),
                assetRequest("Car", "vehicle", "16500"));

        assertThat(response.getValue()).isEqualByComparingTo("16500");
    }

    @Test
    void updateAsset_ofAnotherUser_isRejected() {
        ManualAsset existing = asset(UUID.randomUUID(), "Car", "18000");
        when(manualAssetRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> netWorthService.updateAsset(userId, existing.getId(),
                assetRequest("Mine now", "vehicle", "1")))
                .hasMessageContaining("Unauthorized");
        verify(manualAssetRepository, never()).save(any());
    }

    @Test
    void updateAsset_unknown_throwsNotFound() {
        UUID assetId = UUID.randomUUID();
        when(manualAssetRepository.findById(assetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> netWorthService.updateAsset(userId, assetId, assetRequest("x", "other", "1")))
                .hasMessageContaining("not found");
    }

    @Test
    void deleteAsset_byOwner_deletes_andOfAnotherUser_isRejected() {
        ManualAsset mine = asset(userId, "House", "1");
        ManualAsset theirs = asset(UUID.randomUUID(), "House", "1");
        when(manualAssetRepository.findById(mine.getId())).thenReturn(Optional.of(mine));
        when(manualAssetRepository.findById(theirs.getId())).thenReturn(Optional.of(theirs));

        netWorthService.deleteAsset(userId, mine.getId());
        assertThatThrownBy(() -> netWorthService.deleteAsset(userId, theirs.getId()))
                .hasMessageContaining("Unauthorized");

        verify(manualAssetRepository).delete(mine);
        verify(manualAssetRepository, never()).delete(theirs);
    }

    @Test
    void deleteAsset_unknown_throwsNotFound() {
        UUID assetId = UUID.randomUUID();
        when(manualAssetRepository.findById(assetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> netWorthService.deleteAsset(userId, assetId)).hasMessageContaining("not found");
    }

    // --- manual liabilities ---

    @Test
    void getAllLiabilities_returnsTheUsersLiabilities() {
        when(manualLiabilityRepository.findByUserId(userId)).thenReturn(List.of(liability(userId, "Mortgage", "200000")));

        List<ManualLiabilityResponse> liabilities = netWorthService.getAllLiabilities(userId);

        assertThat(liabilities).extracting(ManualLiabilityResponse::getName).containsExactly("Mortgage");
    }

    @Test
    void addLiability_savesAllFields() {
        when(manualLiabilityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ManualLiabilityResponse response = netWorthService.addLiability(userId,
                liabilityRequest("Student loan", "28000", "5.5", "320"));

        ArgumentCaptor<ManualLiability> saved = ArgumentCaptor.forClass(ManualLiability.class);
        verify(manualLiabilityRepository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(response.getInterestRate()).isEqualByComparingTo("5.5");
        assertThat(response.getMonthlyPayment()).isEqualByComparingTo("320");
    }

    @Test
    void updateLiability_byOwner_updates_andOfAnotherUser_isRejected() {
        ManualLiability mine = liability(userId, "Loan", "1000");
        ManualLiability theirs = liability(UUID.randomUUID(), "Loan", "1000");
        when(manualLiabilityRepository.findById(mine.getId())).thenReturn(Optional.of(mine));
        when(manualLiabilityRepository.findById(theirs.getId())).thenReturn(Optional.of(theirs));
        when(manualLiabilityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ManualLiabilityResponse updated = netWorthService.updateLiability(userId, mine.getId(),
                liabilityRequest("Loan", "900", "4.0", "100"));
        assertThatThrownBy(() -> netWorthService.updateLiability(userId, theirs.getId(),
                liabilityRequest("Loan", "0", "0", "0")))
                .hasMessageContaining("Unauthorized");

        assertThat(updated.getBalance()).isEqualByComparingTo("900");
        verify(manualLiabilityRepository, times(1)).save(any());
    }

    @Test
    void updateLiability_unknown_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(manualLiabilityRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> netWorthService.updateLiability(userId, id, liabilityRequest("x", "1", "1", "1")))
                .hasMessageContaining("not found");
    }

    @Test
    void deleteLiability_byOwner_deletes_andOfAnotherUser_isRejected() {
        ManualLiability mine = liability(userId, "Loan", "1");
        ManualLiability theirs = liability(UUID.randomUUID(), "Loan", "1");
        when(manualLiabilityRepository.findById(mine.getId())).thenReturn(Optional.of(mine));
        when(manualLiabilityRepository.findById(theirs.getId())).thenReturn(Optional.of(theirs));

        netWorthService.deleteLiability(userId, mine.getId());
        assertThatThrownBy(() -> netWorthService.deleteLiability(userId, theirs.getId()))
                .hasMessageContaining("Unauthorized");

        verify(manualLiabilityRepository).delete(mine);
        verify(manualLiabilityRepository, never()).delete(theirs);
    }

    @Test
    void deleteLiability_unknown_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(manualLiabilityRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> netWorthService.deleteLiability(userId, id)).hasMessageContaining("not found");
    }

    // --- daily snapshots ---

    @Test
    void takeNetWorthSnapshots_savesOnePerUser_andContinuesPastFailures() {
        User ok = user();
        User broken = user();
        when(userRepository.findAll()).thenReturn(List.of(broken, ok));
        when(bankAccountRepository.findByUserIdAndAccountTypeInAndIsActive(eq(broken.getId()), any(), eq(true)))
                .thenThrow(new RuntimeException("db hiccup"));
        stubBalances(ok.getId(), List.of(account("100.00")), List.of(), null, null, null);

        netWorthService.takeNetWorthSnapshots();

        ArgumentCaptor<NetWorthSnapshot> saved = ArgumentCaptor.forClass(NetWorthSnapshot.class);
        verify(snapshotRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(ok.getId());
        assertThat(saved.getValue().getNetWorth()).isEqualByComparingTo("100.00");
        assertThat(saved.getValue().getSnapshotDate()).isEqualTo(LocalDate.now());
    }

    // --- helpers ---

    private void stubBalances(UUID user, List<BankAccount> cash, List<BankAccount> credit,
                              String investments, String manualAssets, String manualLiabilities) {
        when(bankAccountRepository.findByUserIdAndAccountTypeInAndIsActive(
                user, List.of("depository", "checking", "savings"), true)).thenReturn(cash);
        when(bankAccountRepository.findByUserIdAndAccountTypeAndIsActive(user, "credit", true)).thenReturn(credit);
        when(holdingRepository.getTotalPortfolioValueByUserId(user)).thenReturn(money(investments));
        when(manualAssetRepository.getTotalValueByUserId(user)).thenReturn(money(manualAssets));
        when(manualLiabilityRepository.getTotalBalanceByUserId(user)).thenReturn(money(manualLiabilities));
    }

    private BigDecimal money(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private BankAccount account(String balance) {
        BankAccount account = new BankAccount();
        account.setCurrentBalance(money(balance));
        return account;
    }

    private ManualAsset asset(UUID owner, String name, String value) {
        return ManualAsset.builder().id(UUID.randomUUID()).userId(owner).name(name)
                .type("other").value(new BigDecimal(value)).build();
    }

    private ManualLiability liability(UUID owner, String name, String balance) {
        return ManualLiability.builder().id(UUID.randomUUID()).userId(owner).name(name)
                .type("other").balance(new BigDecimal(balance)).build();
    }

    private ManualAssetRequest assetRequest(String name, String type, String value) {
        ManualAssetRequest request = new ManualAssetRequest();
        request.setName(name);
        request.setType(type);
        request.setValue(new BigDecimal(value));
        return request;
    }

    private ManualLiabilityRequest liabilityRequest(String name, String balance, String rate, String payment) {
        ManualLiabilityRequest request = new ManualLiabilityRequest();
        request.setName(name);
        request.setType("other");
        request.setBalance(new BigDecimal(balance));
        request.setInterestRate(new BigDecimal(rate));
        request.setMonthlyPayment(new BigDecimal(payment));
        return request;
    }

    private User user() {
        User user = new User();
        user.setId(UUID.randomUUID());
        return user;
    }
}
