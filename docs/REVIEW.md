# Backend Review: Open Items

> First reviewed 2026-10-13 against `main` @ `8dbebbc`; updated 2026-10-23. Companion to [SYSTEM_DESIGN.md](SYSTEM_DESIGN.md).
>
> Only **open** items are listed. Everything fixed since the first review (all P0 and P1 findings, among others)
> has been removed; `git log 8dbebbc..main` has the fixes.

Severity scale: **P0** = security hole or loses money or data, fix now · **P1** = wrong results users will notice · **P2** = scalability, robustness, maintainability · **P3** = polish.

---

## 1. Billing (Stripe)

- `TRIAL` status isn't treated as active (`findActiveByUserId` only matches `ACTIVE`), so `startTrial` subscriptions would never grant access. Nothing calls `startTrial` yet; fix it (and cover the query with a repository test) before offering trials.
- `findPlanByStripePriceId` uses `findAll()` + filter. Minor, but add `findByStripePriceIdMonthlyOrStripePriceIdYearly`.
- Idempotency relies on checking whether the Stripe subscription is already recorded. A `processed_stripe_events(event_id PK)` table, skipping events already seen, would cover every event type.
- Offer the Stripe Billing **Customer Portal** for card updates and cancellation, so you don't build those screens.

---

## 2. Feature gating & usage limits

- Limits are hard-coded in `getFeatureLimit` while `subscription_plans.limits` (jsonb) sits unused. Parse the JSON so you can change limits without a deploy.

---

## 3. Plaid integration (P2)

- **Re-linking a disconnected account is impossible.** `existsByPlaidAccountId` (global, ignores `isActive`) skips it. Reactivate it and update the token instead.
- **`autoSyncHoldings` every 30 min is wasted work.** Plaid refreshes holdings about once a day, and it blocks the single scheduler thread. Run it once daily.
- `Holding.type` is `nullable=false`, but the type is only set when a matching security is found, so a missing security makes the insert fail.
- `PlaidConfig` maps only sandbox and production. Log a warning on unknown values.

---

## 4. AI coach & RAG (P2)

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

## 5. Analytics, budgets, anomalies: correctness

- **Anomaly statistics:** months with zero spend in a category are missing from the history list, which biases the mean up and the standard deviation down. Fill missing months with 0. A *partial* current month is also compared to *full* past months; pro-rate it or compare month-to-date.
- **GET endpoints write to the DB:** `getBudgetsForMonth` updates `spent` during a read. Compute `spent` on read, or refresh it from the sync pipeline.
- `BudgetService` validates things Bean Validation already checks. Harmless, but the duplicates can go.
- **Performance:** analytics calls load all rows into Java. Use SQL `GROUP BY` projections.
- Money types mix `Double` and `BigDecimal` (`alertThreshold`, percentages, `BudgetRepository` totals). Keep money in `BigDecimal` with explicit `precision/scale` on every money column.

---

## 6. Data & persistence

- **`ddl-auto: update` in production.** Hibernate never drops or renames columns and can't add constraints safely (hence `DatabaseConstraints`). Adopt **Flyway**: baseline the current schema, set `ddl-auto: validate`, and move seed data into migrations.
- **Foreign keys:** `transaction_embeddings → transactions` and `spending_anomalies`, `transactions`, `bank_accounts`, `plaid_items → users` exist (added by `DatabaseConstraints`). Other user-owned tables have none; move them all into Flyway migrations.
- `UserSubscriptionRepository.findByUserId` returns `Optional`, but users can have many rows. It throws if anything ever calls it; remove it.
- `GET /api/transactions` without a date range still returns everything (the frontend now asks per month, plus `recent` and `search`). Add pagination (`Pageable`) to it and to the anomalies and history endpoints.
- The `payments` table and `PaymentRepository` are unused (only account deletion touches them). Wire them up or remove them.
- Rotating `JASYPT_ENCRYPTOR_PASSWORD` makes all stored Plaid tokens undecryptable. Document a key-rotation procedure, or move to KMS envelope encryption.

---

## 7. Reliability & operations

- **Schedulers vs multiple tasks.** With ECS desired count > 1, every cron job runs N times. Add **ShedLock** (JDBC lock provider) and a thread-pool scheduler before scaling out.
- **`contextLoads` (`UserServiceApplicationTests`) is skipped in CI** because it needs a real DB and secrets. Run it against Testcontainers.
- **Timeouts:** Plaid (retrofit), Bedrock and OpenAI clients use default timeouts. Set explicit connect and read timeouts, and add retries with backoff for Plaid and OpenAI.
- **Logging:** structured JSON logs with a request ID and user ID in MDC.
- **Errors:** some services still throw a bare `RuntimeException`, which becomes a 500. Map them to typed exceptions. `SubscriptionController` catches exceptions itself and echoes `e.getMessage()` to clients; let `GlobalExceptionHandler` own this.
- **Stale config:** `render.yaml` is unused. Remove it.

---

## 8. Code quality

- **Duplicated `getCurrentUserId()`** in 11 controllers. Replace it with `@AuthenticationPrincipal UUID userId`, or a small `CurrentUser` helper.
- **Mixed injection styles**: field `@Autowired` remains in places (`MetricsService` everywhere, `FeatureAccessAspect`). Standardize on constructor injection.
- **Header comments say `userservice`** in 19 files. Delete them; the path is already known.
- Artifact is named `user-service` and the main class `UserServiceApplication`, but it's the whole backend. Consider renaming.
- Leftover markers (`// UPDATED ...`) and commented-out code can go.
- Lombok `@Data` on JPA entities generates `equals`/`hashCode` over all fields, which breaks with lazy proxies and collections. Use `@Getter @Setter`, with ID-based equality if needed.
- Use a typed enum for plan names instead of the strings "FREE"/"PREMIUM"/"PRO". `getPlanLevel` is duplicated in the aspect and the service.

---

## 9. Testing

Service-layer unit tests are at ~98% lines / ~88% branches, enforced by JaCoCo in CI, with `@WebMvcTest` security tests. The remaining gap:

- **Repository tests on Testcontainers Postgres + pgvector.** Repository calls are mocked, so SQL, encryption and locking are never exercised (that's how a legacy bank-connection count that never matched shipped). Cover the custom queries: usage reservation, connection counts, the guarded embedding insert, `findWithoutEmbedding`, and the user foreign keys.
- **Anomaly detection** edge cases (zero months, partial month).

---

## 10. Product & feature ideas

Ideas that build on what's already here:

- **Proactive insights feed.** After each sync, run anomaly detection and budget checks, then push notifications or email ("You've hit 90% of Dining with 12 days left"). The data and the `shouldAlert()` logic already exist.
- **Cash-flow forecast.** Project end-of-month balance from recurring income and bills plus average discretionary spend.
- **Goals in the coach.** Savings goals exist; inject them into the AI coach's prompt so it can reference them.
- **AI coach memory.** Persist durable facts the user shares ("saving for a house, $60k by 2028") per user, and inject them into the system prompt.
- **Multi-currency.** `currencyCode` is stored but ignored in all sums.
- **Admin dashboard.** MRR, active subs and AI cost per user. `PaymentRepository` already has revenue queries waiting to be used.
- **AI cost telemetry.** Record input and output tokens from Bedrock responses per user, so you can price tiers against real cost.
