package com.financecoach.backend.service;

import com.financecoach.backend.exception.BankAccountNotFoundException;
import com.financecoach.backend.exception.PlaidIntegrationException;
import com.financecoach.backend.exception.UnauthorizedAccessException;
import com.financecoach.backend.model.BankAccount;
import com.financecoach.backend.model.Holding;
import com.financecoach.backend.model.PlaidItem;
import com.financecoach.backend.model.PortfolioSnapshot;
import com.financecoach.backend.repository.BankAccountRepository;
import com.financecoach.backend.repository.HoldingRepository;
import com.financecoach.backend.repository.PortfolioSnapshotRepository;
import com.plaid.client.model.InvestmentsHoldingsGetResponse;
import com.plaid.client.model.Security;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Portfolio maths, holdings lookup, snapshots, the holdings auto-sync job, and how Plaid
 * securities are mapped. Item-level attribution is covered in InvestmentServiceTest.
 */
@ExtendWith(MockitoExtension.class)
class InvestmentPortfolioTest {

    @Mock private HoldingRepository holdingRepository;
    @Mock private PortfolioSnapshotRepository snapshotRepository;
    @Mock private BankAccountRepository bankAccountRepository;
    @Mock private PlaidService plaidService;

    @InjectMocks
    private InvestmentService investmentService;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
    }

    // --- portfolio summary ---

    @Test
    void getPortfolioSummary_totalsValueCostAndGain_andGroupsByType() {
        when(holdingRepository.findByUserId(userId)).thenReturn(List.of(
                holding("stock", "1500.00", "1000.00"),
                holding("etf", "600.00", "500.00"),
                holding("stock", "400.00", null)));

        InvestmentService.PortfolioSummary summary = investmentService.getPortfolioSummary(userId);

        assertThat(summary.getTotalValue()).isEqualByComparingTo("2500.00");
        assertThat(summary.getTotalCostBasis()).isEqualByComparingTo("1500.00");
        assertThat(summary.getTotalGainLoss()).isEqualByComparingTo("1000.00");
        assertThat(summary.getGainLossPercentage()).isEqualByComparingTo("66.6700");
        assertThat(summary.getAllocationBreakdown()).containsOnlyKeys("stock", "etf");
        assertThat(summary.getAllocationBreakdown().get("stock")).isEqualByComparingTo("1900.00");
        assertThat(summary.getHoldings()).hasSize(3);
    }

    @Test
    void getPortfolioSummary_withNoHoldings_isAllZero() {
        when(holdingRepository.findByUserId(userId)).thenReturn(List.of());

        InvestmentService.PortfolioSummary summary = investmentService.getPortfolioSummary(userId);

        assertThat(summary.getTotalValue()).isEqualByComparingTo("0");
        assertThat(summary.getGainLossPercentage()).isEqualByComparingTo("0");
        assertThat(summary.getHoldings()).isEmpty();
        assertThat(summary.getAllocationBreakdown()).isEmpty();
    }

    @Test
    void getPortfolioSummary_withoutAnyCostBasis_reportsZeroPercentGain() {
        when(holdingRepository.findByUserId(userId)).thenReturn(List.of(holding("crypto", "250.00", null)));

        assertThat(investmentService.getPortfolioSummary(userId).getGainLossPercentage()).isEqualByComparingTo("0");
    }

    @Test
    void getAllocationBreakdown_mapsTheGroupedQuery() {
        when(holdingRepository.getAssetAllocationByUserId(userId)).thenReturn(List.of(
                new Object[]{"stock", new BigDecimal("1900.00")},
                new Object[]{"bond", new BigDecimal("100.00")}));

        Map<String, BigDecimal> allocation = investmentService.getAllocationBreakdown(userId);

        assertThat(allocation).containsOnlyKeys("stock", "bond");
        assertThat(allocation.get("bond")).isEqualByComparingTo("100.00");
    }

    // --- holdings by account ---

    @Test
    void getHoldingsByAccount_forTheOwner_returnsHoldings() {
        BankAccount account = account(userId, "investment");
        List<Holding> holdings = List.of(holding("stock", "1", "1"));
        when(bankAccountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(holdingRepository.findByAccountId(account.getId())).thenReturn(holdings);

        assertThat(investmentService.getHoldingsByAccount(account.getId(), userId)).isEqualTo(holdings);
    }

    @Test
    void getHoldingsByAccount_ofAnotherUser_isRejected_andUnknown_isNotFound() {
        BankAccount theirs = account(UUID.randomUUID(), "investment");
        UUID missing = UUID.randomUUID();
        when(bankAccountRepository.findById(theirs.getId())).thenReturn(Optional.of(theirs));
        when(bankAccountRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> investmentService.getHoldingsByAccount(theirs.getId(), userId))
                .isInstanceOf(UnauthorizedAccessException.class);
        assertThatThrownBy(() -> investmentService.getHoldingsByAccount(missing, userId))
                .isInstanceOf(BankAccountNotFoundException.class);
        verify(holdingRepository, never()).findByAccountId(any());
    }

    // --- sync guards ---

    @Test
    void syncHoldingsForAccount_rejectsOtherUsers_unknownAccounts_andSkipsNonInvestmentAccounts() {
        BankAccount theirs = account(UUID.randomUUID(), "investment");
        BankAccount checking = account(userId, "depository");
        UUID missing = UUID.randomUUID();
        when(bankAccountRepository.findById(theirs.getId())).thenReturn(Optional.of(theirs));
        when(bankAccountRepository.findById(checking.getId())).thenReturn(Optional.of(checking));
        when(bankAccountRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> investmentService.syncHoldingsForAccount(theirs.getId(), userId))
                .isInstanceOf(UnauthorizedAccessException.class);
        assertThatThrownBy(() -> investmentService.syncHoldingsForAccount(missing, userId))
                .isInstanceOf(BankAccountNotFoundException.class);
        investmentService.syncHoldingsForAccount(checking.getId(), userId);

        verify(plaidService, never()).getHoldings(anyString());
    }

    @Test
    void syncHoldings_whenPlaidFails_propagatesForTheAccount_butSyncAllContinues() {
        BankAccount account = account(userId, "investment");
        PlaidItem item = new PlaidItem();
        item.setId(UUID.randomUUID());
        item.setAccessToken("token");
        when(bankAccountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(plaidService.getItem(account)).thenReturn(item);
        when(plaidService.getActiveAccountsByPlaidId(item)).thenReturn(Map.of());
        when(plaidService.getHoldings("token")).thenThrow(new PlaidIntegrationException("PRODUCTS_NOT_SUPPORTED"));
        when(bankAccountRepository.findByUserIdAndAccountTypeAndIsActive(userId, "investment", true))
                .thenReturn(List.of(account));
        when(plaidService.getItems(List.of(account))).thenReturn(List.of(item));

        assertThatThrownBy(() -> investmentService.syncHoldingsForAccount(account.getId(), userId))
                .isInstanceOf(PlaidIntegrationException.class);
        investmentService.syncAllHoldings(userId); // logs and carries on
    }

    @Test
    void syncAllHoldings_withNoInvestmentAccounts_doesNothing() {
        when(bankAccountRepository.findByUserIdAndAccountTypeAndIsActive(userId, "investment", true)).thenReturn(List.of());

        investmentService.syncAllHoldings(userId);

        verify(plaidService, never()).getItems(any());
    }

    @Test
    void sync_mapsPlaidSecurityTypes_andCostBasis() {
        BankAccount account = account(userId, "investment");
        account.setPlaidAccountId("plaid-acc");
        PlaidItem item = new PlaidItem();
        item.setId(UUID.randomUUID());
        item.setAccessToken("token");
        when(bankAccountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(plaidService.getItem(account)).thenReturn(item);
        when(plaidService.getActiveAccountsByPlaidId(item)).thenReturn(Map.of("plaid-acc", account));
        String[][] types = {{"equity", "stock"}, {"etf", "etf"}, {"mutual fund", "mutual_fund"},
                {"fixed income", "bond"}, {"cash", "cash"}, {"cryptocurrency", "crypto"}, {"derivative", "other"}};
        List<Security> securities = new java.util.ArrayList<>();
        List<com.plaid.client.model.Holding> holdings = new java.util.ArrayList<>();
        for (int i = 0; i < types.length; i++) {
            securities.add(new Security().securityId("sec-" + i).name("Security " + i).type(types[i][0]));
            holdings.add(new com.plaid.client.model.Holding().accountId("plaid-acc").securityId("sec-" + i)
                    .quantity(1.0).institutionPrice(10.0).institutionValue(10.0).costBasis(i == 0 ? 8.0 : null));
        }
        when(plaidService.getHoldings("token"))
                .thenReturn(new InvestmentsHoldingsGetResponse().holdings(holdings).securities(securities));
        when(holdingRepository.findByAccountIdAndSecurityId(any(), any())).thenReturn(Optional.empty());
        when(holdingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        investmentService.syncHoldingsForAccount(account.getId(), userId);

        ArgumentCaptor<Holding> saved = ArgumentCaptor.forClass(Holding.class);
        verify(holdingRepository, org.mockito.Mockito.times(types.length)).save(saved.capture());
        for (int i = 0; i < types.length; i++) {
            assertThat(saved.getAllValues().get(i).getType()).isEqualTo(types[i][1]);
        }
        assertThat(saved.getAllValues().get(0).getCostBasis()).isEqualByComparingTo("8.0");
        assertThat(saved.getAllValues().get(1).getCostBasis()).isNull();
    }

    // --- snapshots and history ---

    @Test
    void takePortfolioSnapshots_savesUsersWithHoldings_skipsEmptyOnes_andSurvivesFailures() {
        UUID withHoldings = UUID.randomUUID();
        UUID empty = UUID.randomUUID();
        UUID broken = UUID.randomUUID();
        when(bankAccountRepository.findDistinctUserIdsWithInvestmentAccounts()).thenReturn(List.of(broken, empty, withHoldings));
        when(holdingRepository.findByUserId(broken)).thenThrow(new RuntimeException("db hiccup"));
        when(holdingRepository.findByUserId(empty)).thenReturn(List.of());
        when(holdingRepository.findByUserId(withHoldings)).thenReturn(List.of(holding("stock", "100.00", "80.00")));

        investmentService.takePortfolioSnapshots();

        ArgumentCaptor<PortfolioSnapshot> saved = ArgumentCaptor.forClass(PortfolioSnapshot.class);
        verify(snapshotRepository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(withHoldings);
        assertThat(saved.getValue().getTotalValue()).isEqualByComparingTo("100.00");
        assertThat(saved.getValue().getSnapshotDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void getPortfolioHistory_readsSnapshotsSinceTheGivenNumberOfDays() {
        List<PortfolioSnapshot> history = List.of(PortfolioSnapshot.builder().userId(userId).build());
        when(snapshotRepository.findByUserIdAndSnapshotDateAfterOrderBySnapshotDateAsc(userId, LocalDate.now().minusDays(30)))
                .thenReturn(history);

        assertThat(investmentService.getPortfolioHistory(userId, 30)).isEqualTo(history);
    }

    @Test
    void autoSyncHoldings_withNoInvestors_doesNothing_andFailuresDontStopTheJob() {
        UUID broken = UUID.randomUUID();
        when(bankAccountRepository.findDistinctUserIdsWithInvestmentAccounts())
                .thenReturn(List.of())
                .thenReturn(List.of(broken));
        when(bankAccountRepository.findByUserIdAndAccountTypeAndIsActive(broken, "investment", true))
                .thenThrow(new RuntimeException("db hiccup"));

        investmentService.autoSyncHoldings();
        investmentService.autoSyncHoldings();

        verify(plaidService, never()).getItems(any());
    }

    // --- helpers ---

    private Holding holding(String type, String value, String costBasis) {
        return Holding.builder()
                .userId(userId)
                .accountId(UUID.randomUUID())
                .type(type)
                .currentValue(new BigDecimal(value))
                .costBasis(costBasis == null ? null : new BigDecimal(costBasis))
                .build();
    }

    private BankAccount account(UUID owner, String type) {
        BankAccount account = new BankAccount();
        account.setId(UUID.randomUUID());
        account.setUserId(owner);
        account.setAccountType(type);
        return account;
    }
}
