package com.financecoach.backend.service;

import com.financecoach.backend.model.BankAccount;
import com.financecoach.backend.model.Holding;
import com.financecoach.backend.model.PlaidItem;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvestmentServiceTest {

    @Mock private HoldingRepository holdingRepository;
    @Mock private PortfolioSnapshotRepository snapshotRepository;
    @Mock private BankAccountRepository bankAccountRepository;
    @Mock private PlaidService plaidService;

    @InjectMocks
    private InvestmentService investmentService;

    private UUID userId;
    private PlaidItem item;
    private BankAccount brokerage;
    private BankAccount ira;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        item = new PlaidItem();
        item.setId(UUID.randomUUID());
        item.setAccessToken("access-item");

        brokerage = investmentAccount("plaid-brokerage");
        ira = investmentAccount("plaid-ira");
    }

    @Test
    void syncAllHoldings_savesEachHoldingUnderItsOwnAccount_once() {
        when(bankAccountRepository.findByUserIdAndAccountTypeAndIsActive(userId, "investment", true))
                .thenReturn(List.of(brokerage, ira));
        when(plaidService.getItems(List.of(brokerage, ira))).thenReturn(List.of(item));
        stubItemAccounts();
        when(plaidService.getHoldings("access-item")).thenReturn(new InvestmentsHoldingsGetResponse()
                .holdings(List.of(
                        plaidHolding("plaid-brokerage", "sec-aapl", 10, 1900),
                        plaidHolding("plaid-ira", "sec-vti", 5, 1250)))
                .securities(List.of(
                        new Security().securityId("sec-aapl").name("Apple").tickerSymbol("AAPL").type("equity"),
                        new Security().securityId("sec-vti").name("Vanguard Total").tickerSymbol("VTI").type("etf"))));
        when(holdingRepository.findByAccountIdAndSecurityId(any(), any())).thenReturn(Optional.empty());
        when(holdingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        investmentService.syncAllHoldings(userId);

        // One Plaid call for the item, and exactly one row per holding
        verify(plaidService, times(1)).getHoldings("access-item");
        verify(plaidService).applyBalances(any(), any());
        ArgumentCaptor<Holding> saved = ArgumentCaptor.forClass(Holding.class);
        verify(holdingRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(Holding::getSymbol, Holding::getAccountId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("AAPL", brokerage.getId()),
                        org.assertj.core.groups.Tuple.tuple("VTI", ira.getId()));
    }

    @Test
    void syncHoldingsForAccount_syncsTheWholeItemWithCorrectAttribution() {
        when(bankAccountRepository.findById(ira.getId())).thenReturn(Optional.of(ira));
        when(plaidService.getItem(ira)).thenReturn(item);
        stubItemAccounts();
        when(plaidService.getHoldings("access-item")).thenReturn(new InvestmentsHoldingsGetResponse()
                .holdings(List.of(plaidHolding("plaid-brokerage", "sec-aapl", 10, 1900)))
                .securities(List.of(
                        new Security().securityId("sec-aapl").name("Apple").tickerSymbol("AAPL").type("equity"))));
        when(holdingRepository.findByAccountIdAndSecurityId(any(), any())).thenReturn(Optional.empty());
        when(holdingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        investmentService.syncHoldingsForAccount(ira.getId(), userId);

        ArgumentCaptor<Holding> saved = ArgumentCaptor.forClass(Holding.class);
        verify(holdingRepository).save(saved.capture());
        // The brokerage holding stays on the brokerage account even though the IRA triggered the sync
        assertThat(saved.getValue().getAccountId()).isEqualTo(brokerage.getId());
    }

    @Test
    void sync_removesPositionsPlaidNoLongerReturns_soSoldHoldingsDontInflateTheTotals() {
        Holding stillHeld = stored(brokerage, "sec-aapl");
        Holding sold = stored(brokerage, "sec-tsla");
        Holding soldFromIra = stored(ira, "sec-old-fund");
        when(bankAccountRepository.findByUserIdAndAccountTypeAndIsActive(userId, "investment", true))
                .thenReturn(List.of(brokerage, ira));
        when(plaidService.getItems(List.of(brokerage, ira))).thenReturn(List.of(item));
        stubItemAccounts();
        when(plaidService.getHoldings("access-item")).thenReturn(new InvestmentsHoldingsGetResponse()
                .holdings(List.of(plaidHolding("plaid-brokerage", "sec-aapl", 10, 1900)))
                .securities(List.of(new Security().securityId("sec-aapl").name("Apple").tickerSymbol("AAPL").type("equity"))));
        when(holdingRepository.findByAccountIdAndSecurityId(brokerage.getId(), "sec-aapl")).thenReturn(Optional.of(stillHeld));
        when(holdingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(holdingRepository.findByAccountId(brokerage.getId())).thenReturn(List.of(stillHeld, sold));
        when(holdingRepository.findByAccountId(ira.getId())).thenReturn(List.of(soldFromIra));

        investmentService.syncAllHoldings(userId);

        verify(holdingRepository).deleteAll(List.of(sold));
        verify(holdingRepository).deleteAll(List.of(soldFromIra)); // IRA came back empty: everything was sold
    }

    @Test
    void sync_whenPlaidFails_keepsAllStoredHoldings() {
        when(bankAccountRepository.findById(ira.getId())).thenReturn(Optional.of(ira));
        when(plaidService.getItem(ira)).thenReturn(item);
        stubItemAccounts();
        when(plaidService.getHoldings("access-item"))
                .thenThrow(new com.financecoach.backend.exception.PlaidIntegrationException("ITEM_LOGIN_REQUIRED"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> investmentService.syncHoldingsForAccount(ira.getId(), userId))
                .isInstanceOf(com.financecoach.backend.exception.PlaidIntegrationException.class);

        verify(holdingRepository, org.mockito.Mockito.never()).deleteAll(any());
    }

    private Holding stored(BankAccount account, String securityId) {
        return Holding.builder().id(UUID.randomUUID()).userId(userId).accountId(account.getId())
                .securityId(securityId).type("stock").build();
    }

    private BankAccount investmentAccount(String plaidAccountId) {
        BankAccount account = new BankAccount();
        account.setId(UUID.randomUUID());
        account.setUserId(userId);
        account.setItemId(item.getId());
        account.setPlaidAccountId(plaidAccountId);
        account.setAccountType("investment");
        return account;
    }

    private void stubItemAccounts() {
        Map<String, BankAccount> byPlaidId = new LinkedHashMap<>();
        byPlaidId.put(brokerage.getPlaidAccountId(), brokerage);
        byPlaidId.put(ira.getPlaidAccountId(), ira);
        when(plaidService.getActiveAccountsByPlaidId(item)).thenReturn(byPlaidId);
    }

    private com.plaid.client.model.Holding plaidHolding(String plaidAccountId, String securityId,
                                                       double quantity, double value) {
        return new com.plaid.client.model.Holding()
                .accountId(plaidAccountId)
                .securityId(securityId)
                .quantity(quantity)
                .institutionPrice(value / quantity)
                .institutionValue(value);
    }
}
