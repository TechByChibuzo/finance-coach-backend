package com.financecoach.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Assigns Plaid-style categories to imported transactions, which arrive without any.
 * Asks Claude in batches; if it's unavailable or answers oddly, falls back to keyword rules.
 */
@Service
public class TransactionCategorizer {

    private static final Logger logger = LoggerFactory.getLogger(TransactionCategorizer.class);
    private static final int BATCH_SIZE = 150;

    // Plaid Personal Finance Category primaries, plus a marker for credit card bill payments,
    // which are stored as LOAN_PAYMENTS / LOAN_PAYMENTS_CREDIT_CARD_PAYMENT so totals skip them
    static final String CARD_PAYMENT = "CREDIT_CARD_PAYMENT";
    static final Set<String> CATEGORIES = Set.of(
            "INCOME", "TRANSFER_IN", "TRANSFER_OUT", "LOAN_PAYMENTS", CARD_PAYMENT, "BANK_FEES",
            "ENTERTAINMENT", "FOOD_AND_DRINK", "GENERAL_MERCHANDISE", "HOME_IMPROVEMENT", "MEDICAL",
            "PERSONAL_CARE", "GENERAL_SERVICES", "GOVERNMENT_AND_NON_PROFIT", "TRANSPORTATION",
            "TRAVEL", "RENT_AND_UTILITIES");

    private static final String SYSTEM_PROMPT = """
            You categorize bank transactions. For each numbered line (description | amount, where a
            positive amount is money out and a negative amount is money in), choose exactly one code:
            %s.
            Use CREDIT_CARD_PAYMENT for paying off a credit card bill, TRANSFER_IN / TRANSFER_OUT for
            moving money between the person's own accounts, and INCOME for salary, payroll or interest.
            Reply with only a JSON array of codes, one per line, in the same order. No other text.
            """.formatted(String.join(", ", CATEGORIES.stream().sorted().toList()));

    public record Input(String description, BigDecimal amount) {
    }

    public record Category(String category, String subcategory) {
    }

    private final ClaudeService claudeService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TransactionCategorizer(ClaudeService claudeService) {
        this.claudeService = claudeService;
    }

    public List<Category> categorize(List<Input> inputs) {
        List<Category> result = new ArrayList<>(inputs.size());
        for (int start = 0; start < inputs.size(); start += BATCH_SIZE) {
            List<Input> batch = inputs.subList(start, Math.min(start + BATCH_SIZE, inputs.size()));
            List<String> codes = askClaude(batch);
            for (int i = 0; i < batch.size(); i++) {
                String code = codes != null ? codes.get(i) : byKeywords(batch.get(i));
                result.add(toCategory(code));
            }
        }
        return result;
    }

    private List<String> askClaude(List<Input> batch) {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            lines.append(i + 1).append(". ").append(batch.get(i).description().replace('\n', ' '))
                    .append(" | ").append(batch.get(i).amount().toPlainString()).append('\n');
        }
        try {
            String reply = claudeService.chat(lines.toString(), SYSTEM_PROMPT);
            int open = reply.indexOf('[');
            int close = reply.lastIndexOf(']');
            if (open < 0 || close < open) {
                throw new IllegalStateException("no JSON array in reply");
            }
            List<String> codes = objectMapper.readValue(reply.substring(open, close + 1), new TypeReference<>() {});
            if (codes.size() != batch.size()) {
                throw new IllegalStateException("expected " + batch.size() + " codes, got " + codes.size());
            }
            List<String> checked = new ArrayList<>(codes.size());
            for (int i = 0; i < codes.size(); i++) {
                String code = codes.get(i) == null ? "" : codes.get(i).trim().toUpperCase(Locale.ROOT);
                checked.add(CATEGORIES.contains(code) ? code : byKeywords(batch.get(i)));
            }
            return checked;
        } catch (Exception e) {
            logger.warn("Categorizing {} imported transactions with keyword rules instead of Claude: {}",
                    batch.size(), e.getMessage());
            return null;
        }
    }

    private static final List<Rule> RULES = List.of(
            new Rule("PAYMENT THANK YOU|AUTOPAY|CREDIT CARD PAYMENT|CARD PAYMENT|PAYMENT RECEIVED", CARD_PAYMENT, null),
            new Rule("PAYROLL|DIRECT DEP|SALARY|INTEREST PAID|INTEREST EARNED", "INCOME", null),
            new Rule("TRANSFER|ZELLE|VENMO|CASH APP", "TRANSFER_OUT", "TRANSFER_IN"),
            new Rule("UBER|LYFT|SHELL|CHEVRON|EXXON|BP |PARKING|TRANSIT|METRO", "TRANSPORTATION", null),
            new Rule("RESTAURANT|CAFE|COFFEE|STARBUCKS|MCDONALD|DOORDASH|GRUBHUB|PIZZA|GROCERY|WHOLE FOODS|TRADER JOE|KROGER|SAFEWAY", "FOOD_AND_DRINK", null),
            new Rule("AIRLINE|AIRBNB|HOTEL|MARRIOTT|HILTON|EXPEDIA|DELTA|UNITED|AMERICAN AIR", "TRAVEL", null),
            new Rule("NETFLIX|SPOTIFY|HULU|DISNEY|CINEMA|THEATER|STEAM", "ENTERTAINMENT", null),
            new Rule("RENT|ELECTRIC|WATER|GAS CO|COMCAST|XFINITY|VERIZON|AT&T|T-MOBILE|UTILIT", "RENT_AND_UTILITIES", null),
            new Rule("PHARMACY|CVS|WALGREENS|DOCTOR|DENTAL|HOSPITAL|CLINIC", "MEDICAL", null),
            new Rule("FEE|OVERDRAFT", "BANK_FEES", null),
            new Rule("MORTGAGE|LOAN", "LOAN_PAYMENTS", null)
    );

    private record Rule(Pattern pattern, String moneyOut, String moneyIn) {
        Rule(String words, String moneyOut, String moneyIn) {
            this(Pattern.compile("\\b(" + words + ")", Pattern.CASE_INSENSITIVE), moneyOut, moneyIn);
        }
    }

    static String byKeywords(Input input) {
        boolean moneyIn = input.amount().signum() < 0;
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(input.description()).find()) {
                return moneyIn && rule.moneyIn() != null ? rule.moneyIn() : rule.moneyOut();
            }
        }
        return moneyIn ? "INCOME" : "GENERAL_MERCHANDISE";
    }

    private static Category toCategory(String code) {
        return CARD_PAYMENT.equals(code)
                ? new Category("LOAN_PAYMENTS", "LOAN_PAYMENTS_CREDIT_CARD_PAYMENT")
                : new Category(code, null);
    }
}
