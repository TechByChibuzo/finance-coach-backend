package com.financecoach.backend.service;

import com.financecoach.backend.exception.UnauthorizedAccessException;
import com.financecoach.backend.exception.ValidationException;
import com.financecoach.backend.model.CategoryRule;
import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.repository.CategoryRuleRepository;
import com.financecoach.backend.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryRuleServiceTest {

    @Mock private CategoryRuleRepository ruleRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private VectorStoreService vectorStoreService;

    @InjectMocks
    private CategoryRuleService service;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        lenient().when(transactionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    private Transaction tx(String merchant, String name, String category) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setUserId(userId);
        t.setPlaidTransactionId("p-" + UUID.randomUUID());
        t.setMerchantName(merchant);
        t.setName(name);
        t.setCategory(category);
        return t;
    }

    @Test
    void updateCategory_changesOnlyThatTransaction_andRebuildsItsEmbedding() {
        Transaction costco = tx("Costco", "COSTCO WHSE #123", "GENERAL_MERCHANDISE");
        when(transactionRepository.findById(costco.getId())).thenReturn(Optional.of(costco));

        Transaction result = service.updateCategory(userId, costco.getId(), "food_and_drink", false);

        assertThat(result.getCategory()).isEqualTo("FOOD_AND_DRINK");
        assertThat(result.getCategoryOverridden()).isTrue();
        verify(ruleRepository, never()).save(any());
        verify(vectorStoreService).removeTransaction(costco.getPlaidTransactionId());
        verify(vectorStoreService).indexTransactionsAsync(List.of(costco));
    }

    @Test
    void updateCategory_withRemember_savesARule_andRecategorizesEveryTransactionFromThatMerchant() {
        Transaction one = tx("Costco", "COSTCO WHSE #123", "GENERAL_MERCHANDISE");
        Transaction two = tx("COSTCO ", "COSTCO GAS", "TRANSPORTATION");
        Transaction other = tx("Target", "TARGET", "GENERAL_MERCHANDISE");
        when(transactionRepository.findById(one.getId())).thenReturn(Optional.of(one));
        when(ruleRepository.findByUserIdAndMatchKey(userId, "costco")).thenReturn(Optional.empty());
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(one, two, other));

        service.updateCategory(userId, one.getId(), "FOOD_AND_DRINK", true);

        ArgumentCaptor<CategoryRule> rule = ArgumentCaptor.forClass(CategoryRule.class);
        verify(ruleRepository).save(rule.capture());
        assertThat(rule.getValue().getMatchKey()).isEqualTo("costco");
        assertThat(rule.getValue().getDisplayName()).isEqualTo("Costco");
        assertThat(rule.getValue().getCategory()).isEqualTo("FOOD_AND_DRINK");
        assertThat(two.getCategory()).isEqualTo("FOOD_AND_DRINK");
        assertThat(other.getCategory()).isEqualTo("GENERAL_MERCHANDISE");
    }

    @Test
    void updateCategory_cardPayment_isStoredSoTotalsSkipIt() {
        Transaction payment = tx(null, "PAYMENT THANK YOU", "LOAN_PAYMENTS");
        when(transactionRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

        service.updateCategory(userId, payment.getId(), "CREDIT_CARD_PAYMENT", false);

        assertThat(payment.getCategory()).isEqualTo("LOAN_PAYMENTS");
        assertThat(payment.getSubcategory()).isEqualTo("LOAN_PAYMENTS_CREDIT_CARD_PAYMENT");
    }

    @Test
    void updateCategory_rejectsUnknownCategories_andOtherUsersTransactions() {
        Transaction someoneElses = tx("Costco", "COSTCO", "GENERAL_MERCHANDISE");
        someoneElses.setUserId(UUID.randomUUID());
        when(transactionRepository.findById(someoneElses.getId())).thenReturn(Optional.of(someoneElses));

        assertThatThrownBy(() -> service.updateCategory(userId, someoneElses.getId(), "SNACKS", false))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.updateCategory(userId, someoneElses.getId(), "TRAVEL", false))
                .isInstanceOf(UnauthorizedAccessException.class);
        verify(transactionRepository, never()).saveAll(anyList());
    }

    @Test
    void applyRules_categorizesMatchingNewTransactions() {
        CategoryRule rule = new CategoryRule();
        rule.setMatchKey("costco");
        rule.setCategory("FOOD_AND_DRINK");
        when(ruleRepository.findByUserIdOrderByDisplayNameAsc(userId)).thenReturn(List.of(rule));
        Transaction match = tx("Costco", "COSTCO WHSE", "GENERAL_MERCHANDISE");
        Transaction byName = tx(null, "costco", "GENERAL_MERCHANDISE");
        Transaction noMatch = tx("Target", "TARGET", "GENERAL_MERCHANDISE");

        service.applyRules(userId, List.of(match, byName, noMatch));

        assertThat(match.getCategory()).isEqualTo("FOOD_AND_DRINK");
        assertThat(byName.getCategory()).isEqualTo("FOOD_AND_DRINK");
        assertThat(noMatch.getCategory()).isEqualTo("GENERAL_MERCHANDISE");
        assertThat(match.getCategoryOverridden()).isTrue();
    }

    @Test
    void applyRules_withNothingToDo_skipsTheLookup() {
        service.applyRules(userId, List.of());
        verify(ruleRepository, never()).findByUserIdOrderByDisplayNameAsc(any());
    }

    @Test
    void deleteRule_onlyDeletesTheUsersOwnRule() {
        CategoryRule mine = new CategoryRule();
        mine.setId(UUID.randomUUID());
        mine.setUserId(userId);
        CategoryRule theirs = new CategoryRule();
        theirs.setId(UUID.randomUUID());
        theirs.setUserId(UUID.randomUUID());
        when(ruleRepository.findById(mine.getId())).thenReturn(Optional.of(mine));
        when(ruleRepository.findById(theirs.getId())).thenReturn(Optional.of(theirs));

        service.deleteRule(userId, mine.getId());
        assertThatThrownBy(() -> service.deleteRule(userId, theirs.getId())).isInstanceOf(UnauthorizedAccessException.class);

        verify(ruleRepository).delete(mine);
        verify(ruleRepository, never()).delete(theirs);
    }

    @Test
    void getRules_listsThem() {
        when(ruleRepository.findByUserIdOrderByDisplayNameAsc(userId)).thenReturn(List.of(new CategoryRule()));
        assertThat(service.getRules(userId)).hasSize(1);
    }
}
