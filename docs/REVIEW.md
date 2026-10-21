# Backend Review: Open Items

> First reviewed 2026-10-13 against `main` @ `8dbebbc`; updated 2026-10-20. Companion to [SYSTEM_DESIGN.md](SYSTEM_DESIGN.md).
>
> Only **open** items are listed. Everything fixed since the first review (all P0 and P1 findings, among others)
> has been removed; `git log 8dbebbc..main` has the fixes.

Severity scale: **P0** = security hole or loses money or data, fix now · **P1** = wrong results users will notice · **P2** = scalability, robustness, maintainability · **P3** = polish.

---

## Follow-up review (2026-10-20)

Edge cases, none reachable with today's production data or traffic. Fix before onboarding real users (#1 first). (#2, concurrent Stripe webhooks creating duplicate subscriptions, turned out to happen on every new subscription and has been fixed.)

| # | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| 1 | P1 | A sync in flight during account deletion can write transactions after the purge, and `plaidItemRepository.save(item)` can re-insert the deleted item **with its access token**. Only `transaction_embeddings` has a parent FK. | `TransactionService.syncItem` | Add parent FKs with `ON DELETE CASCADE` in `DatabaseConstraints` (transactions → bank_accounts, bank_accounts / plaid_items → users), deleting orphans first, so late writes fail. |
| 3 | P1 | On upgrade, a paid row with no `stripeSubscriptionId` is marked cancelled locally while Stripe keeps billing it. No such rows exist in production today. | `SubscriptionService.createSubscription` | Cancel every other running Stripe subscription for the customer (`listSubscriptions`), excluding the new one, like the cancel path does. |
| 4 | P2 | Parallel budget creation can exceed the plan cap (two requests at 2/3 both take the last slot). | `BudgetService.createBudget`, copy path | Lock the user row before counting, as `PlaidService.exchangePublicToken` does. |
| 5 | P2 | Legacy bank connections (accounts with no `item_id`) are counted by distinct *encrypted* access token. Jasypt salts randomly, so the same token never matches and each legacy account counts separately, which could wrongly block a user. No legacy rows exist in production today. | `BankAccountRepository.countLegacyConnections` | Load the legacy accounts and count distinct decrypted tokens in Java, or backfill `item_id` via `PlaidService.getItem`. |
| 6 | P2 | An embedding job that started before a Plaid modification can finish after it and store stale text; the newer job then hits `ON CONFLICT DO NOTHING`. | `VectorStoreService.insertEmbedding` | Only insert while the transaction's current fields match those the text was built from, and use `ON CONFLICT DO UPDATE`. |

---

## 1. Security hardening (P2)

- **CORS** allows `https://*.vercel.app`, which includes anyone's Vercel app. Pin it to your exact frontend origin(s) via config. Drop the per-controller `@CrossOrigin` annotations, which conflict with the global config.
- **CSV formula injection** in exports: prefix cells starting with `= + - @` with `'`.
- **Password reset tokens** are stored in plaintext. Store a SHA-256 hash and compare hashes.
- **Login timing**: an unknown email skips BCrypt, so response time reveals which emails exist. Run a dummy `matches` on miss.
- `User` uses Lombok `@Data`, so `toString()` includes `passwordHash`. Exclude it (`@ToString.Exclude`), and prefer `@Getter/@Setter` on entities.
- `FeatureAccessAspect.getCurrentUserId()` throws a plain `RuntimeException` (→ 500) instead of an authentication error (→ 401).

---

## 2. Billing (Stripe)

- `TRIAL` status isn't treated as active (`findActiveByUserId` only matches `ACTIVE`), so `startTrial` subscriptions never grant access.
- `findPlanByStripePriceId` uses `findAll()` + filter. Minor, but add `findByStripePriceIdMonthlyOrStripePriceIdYearly`.
- Idempotency relies on checking whether the Stripe subscription is already recorded. A `processed_stripe_events(event_id PK)` table, skipping events already seen, would cover every event type.
- Offer the Stripe Billing **Customer Portal** for card updates and cancellation, so you don't build those screens.

---

## 3. Feature gating & usage limits

- Limits are hard-coded in `getFeatureLimit` while `subscription_plans.limits` (jsonb) sits unused. Parse the JSON so you can change limits without a deploy.

---

## 4. Plaid integration (P2)

- **Re-linking a disconnected account is impossible.** `existsByPlaidAccountId` (global, ignores `isActive`) skips it. Reactivate it and update the token instead.
- **Disconnecting an account doesn't call `/item/remove`** once the item's last active account is gone, so the item stays live at Plaid (account deletion already revokes everything).
- **No handling of `ITEM_LOGIN_REQUIRED`.** Surface a "reconnect" state and use Link update mode.
- **`autoSyncHoldings` every 30 min is wasted work.** Plaid refreshes holdings about once a day, and it blocks the single scheduler thread. Run it once daily.
- `Holding.type` is `nullable=false`, but the type is only set when a matching security is found, so a missing security makes the insert fail.
- `PlaidConfig` maps only sandbox and production. Log a warning on unknown values.

---

## 5. AI coach & RAG (P2)

- **Tool use.** The coach now gets exact monthly totals in its prompt. For questions beyond that ("what did I spend at Costco in March?"), give Claude tools such as `get_spending_by_category(start,end)`, `get_transactions(filter)` and `get_budget_status(month)` backed by the existing services. Bedrock's Anthropic Messages API supports `tools`.
- The embedded text includes the account **UUID** (`EmbeddingService`). It's random noise that hurts similarity. Use the institution or account name, or drop it.
- **No ANN index.** Add `CREATE INDEX ON transaction_embeddings USING hnsw (embedding vector_cosine_ops);` and a `user_id` b-tree index as data grows.
- No similarity threshold, so irrelevant rows still get injected. Filter by distance (`< 0.6` or similar).
- **Prompt injection:** merchant names come from banks and go straight into the system prompt. Low risk, but wrap the context in delimiters and tell the model that context is data, not instructions.
- **Cost controls:** set a max-tokens cap per endpoint, and consider caching weekly and monthly reports per user per period.
- **Streaming:** `InvokeModelWithResponseStream` + SSE would make chat feel far faster.
- `ConversationService.getHistory` loads the whole session, then slices it. Query `ORDER BY created_at DESC LIMIT 10` instead.
- `anthropic-java` and the unused `CLAUDE_API_KEY` env var can go, since you call Claude through Bedrock.

---

## 6. Analytics, budgets, anomalies: correctness

- **Anomaly statistics:** months with zero spend in a category are missing from the history list, which biases the mean up and the standard deviation down. Fill missing months with 0. A *partial* current month is also compared to *full* past months; pro-rate it or compare month-to-date.
- **GET endpoints write to the DB:** `getBudgetsForMonth` updates `spent` during a read. Compute `spent` on read, or refresh it from the sync pipeline.
- `BudgetService` validates things Bean Validation already checks. Harmless, but the duplicates can go.
- **Performance:** analytics calls load all rows into Java. Use SQL `GROUP BY` projections.
- Plaid `loan` accounts (student loans, mortgages) are ignored by net worth.
- Money types mix `Double` and `BigDecimal` (`alertThreshold`, percentages, `BudgetRepository` totals). Keep money in `BigDecimal` with explicit `precision/scale` on every money column.

---

## 7. Data & persistence

- **`ddl-auto: update` in production.** Hibernate never drops or renames columns and can't add constraints safely (hence `DatabaseConstraints`). Adopt **Flyway**: baseline the current schema, set `ddl-auto: validate`, and move seed data into migrations.
- **Foreign keys:** only `transaction_embeddings → transactions` exists. See follow-up #1.
- `UserSubscriptionRepository.findByUserId` returns `Optional`, but users can have many rows. It throws if anything ever calls it; remove it.
- Lists are unbounded: `GET /api/transactions` returns everything. Add pagination (`Pageable`) to transactions, anomalies and history endpoints.
- Unused tables and repositories (`payments` and most `PaymentRepository` queries; `TransactionEmbeddingRepository.findSimilarTransactions`) should be wired up or removed.
- Rotating `JASYPT_ENCRYPTOR_PASSWORD` makes all stored Plaid tokens undecryptable. Document a key-rotation procedure, or move to KMS envelope encryption.

---

## 8. Reliability & operations

- **Schedulers vs multiple tasks.** With ECS desired count > 1, every cron job runs N times. Add **ShedLock** (JDBC lock provider) and a thread-pool scheduler before scaling out.
- **`contextLoads` (`UserServiceApplicationTests`) is skipped in CI** because it needs a real DB and secrets. Run it against Testcontainers.
- **Timeouts:** Plaid (retrofit), Bedrock and OpenAI clients use default timeouts. Set explicit connect and read timeouts, and add retries with backoff for Plaid and OpenAI.
- **Logging:** structured JSON logs with a request ID and user ID in MDC.
- **Errors:** some services still throw a bare `RuntimeException`, which becomes a 500. Map them to typed exceptions. `SubscriptionController` catches exceptions itself and echoes `e.getMessage()` to clients; let `GlobalExceptionHandler` own this.
- **Stale config:** `OpenApiConfig` lists old server URLs, and `render.yaml` is unused. Update or remove them.
- Add an S3 lifecycle rule to expire `exports/` after a few days.

---

## 9. Code quality

- **Duplicated `getCurrentUserId()`** in 11 controllers. Replace it with `@AuthenticationPrincipal UUID userId`, or a small `CurrentUser` helper.
- **Mixed injection styles**: field `@Autowired` remains in places (`MetricsService` everywhere, `FeatureAccessAspect`). Standardize on constructor injection.
- **Header comments say `userservice`** in 19 files. Delete them; the path is already known.
- `System.out.println` in `SecurityConfig`. Use the logger.
- Artifact is named `user-service` and the main class `UserServiceApplication`, but it's the whole backend. Consider renaming.
- Leftover markers (`// UPDATED ...`) and commented-out code can go.
- Lombok `@Data` on JPA entities generates `equals`/`hashCode` over all fields, which breaks with lazy proxies and collections. Use `@Getter @Setter`, with ID-based equality if needed.
- Use a typed enum for plan names instead of the strings "FREE"/"PREMIUM"/"PRO". `getPlanLevel` is duplicated in the aspect and the service.

---

## 10. Testing

Service-layer unit tests are at ~98% lines / ~88% branches, enforced by JaCoCo in CI, with `@WebMvcTest` security tests. The remaining gap:

- **Repository tests on Testcontainers Postgres + pgvector.** Repository calls are mocked, so SQL, encryption and locking are never exercised (that's how follow-up #5 shipped). Cover the custom queries: usage reservation, connection counts, the guarded embedding insert, `findWithoutEmbedding`, and the FKs in follow-up #1.
- **Anomaly detection** edge cases (zero months, partial month).

---

## 11. Product & feature ideas

Ideas that build on what's already here:

- **Proactive insights feed.** After each sync, run anomaly detection and budget checks, then push notifications or email ("You've hit 90% of Dining with 12 days left"). The data and the `shouldAlert()` logic already exist.
- **Recurring-charge detection.** Group by merchant and roughly equal amount at monthly or yearly cadence, to find subscriptions and flag price increases. Plaid also has `/transactions/recurring/get`.
- **Cash-flow forecast.** Project end-of-month balance from recurring income and bills plus average discretionary spend.
- **Goals.** Savings goals with target dates, with progress tracked from account balances. The AI coach can reference them.
- **User-editable categories and rules.** Let users re-categorize ("Costco → Groceries, not Shopping") and remember the rule.
- **AI coach memory.** Persist durable facts the user shares ("saving for a house, $60k by 2028") per user, and inject them into the system prompt.
- **Weekly digest email** using the existing `generateWeeklySummary` and SendGrid, sent from a Sunday cron.
- **Multi-currency.** `currencyCode` is stored but ignored in all sums.
- **Admin dashboard.** MRR, active subs and AI cost per user. `PaymentRepository` already has revenue queries waiting to be used.
- **AI cost telemetry.** Record input and output tokens from Bedrock responses per user, so you can price tiers against real cost.
