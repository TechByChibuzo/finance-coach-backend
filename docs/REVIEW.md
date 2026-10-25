# Backend Review: Open Items

> First reviewed 2026-10-13 against `main` @ `8dbebbc`; updated 2026-10-25. Companion to [SYSTEM_DESIGN.md](SYSTEM_DESIGN.md).
>
> Only **open** items are listed. Everything fixed since the first review (all P0 and P1 findings, among others)
> has been removed; `git log 8dbebbc..main` has the fixes.

Severity scale: **P0** = security hole or loses money or data, fix now · **P1** = wrong results users will notice · **P2** = scalability, robustness, maintainability · **P3** = polish.

---

## 1. Billing (Stripe)

- `findPlanByStripePriceId` uses `findAll()` + filter. Minor, but add `findByStripePriceIdMonthlyOrStripePriceIdYearly`.
- Idempotency relies on checking whether the Stripe subscription is already recorded. A `processed_stripe_events(event_id PK)` table, skipping events already seen, would cover every event type.
- Offer the Stripe Billing **Customer Portal** for card updates and cancellation, so you don't build those screens.

---

## 2. Feature gating & usage limits

- Limits are hard-coded in `getFeatureLimit` while `subscription_plans.limits` (jsonb) sits unused. Parse the JSON so you can change limits without a deploy.

---

## 3. AI coach & RAG (P2)

- **Tool use.** The coach now gets exact monthly totals in its prompt. For questions beyond that ("what did I spend at Costco in March?"), give Claude tools such as `get_spending_by_category(start,end)`, `get_transactions(filter)` and `get_budget_status(month)` backed by the existing services. Bedrock's Anthropic Messages API supports `tools`.
- **No ANN index.** Add `CREATE INDEX ON transaction_embeddings USING hnsw (embedding vector_cosine_ops);` and a `user_id` b-tree index as data grows.
- No similarity threshold, so irrelevant rows still get injected. Filter by distance, but pick the cutoff from real query distances (a guess like `< 0.6` could drop everything).
- **Cost controls:** set a max-tokens cap per endpoint, and consider caching weekly and monthly reports per user per period.
- **Streaming:** `InvokeModelWithResponseStream` + SSE would make chat feel far faster.

---

## 4. Analytics, budgets, anomalies: correctness

- **GET endpoints write to the DB:** `getBudgetsForMonth` saves `spent` and `carriedOver` during a read (only when they changed). It can't simply stop: rollover builds each month's carry-over from last month's *stored* `carriedOver`, which only stays current because reads save it. Refresh budgets from the sync pipeline (after each transaction sync), then make the read pure.
- `BudgetService` validates things Bean Validation already checks. Harmless, but the duplicates can go.
- **Performance:** analytics calls load all rows into Java. Use SQL `GROUP BY` projections.
- Money types mix `Double` and `BigDecimal` (`alertThreshold`, percentages, `BudgetRepository` totals). Keep money in `BigDecimal` with explicit `precision/scale` on every money column.

---

## 5. Data & persistence

- **`ddl-auto: update` in production.** Hibernate never drops or renames columns and can't add constraints safely (hence `DatabaseConstraints`). Adopt **Flyway**: baseline the current schema, set `ddl-auto: validate`, and move seed data into migrations.
- **Foreign keys:** `transaction_embeddings → transactions` and `spending_anomalies`, `transactions`, `bank_accounts`, `plaid_items → users` exist (added by `DatabaseConstraints`). Other user-owned tables have none; move them all into Flyway migrations.
- `GET /api/transactions` without a date range still returns everything (the frontend now asks per month, plus `recent` and `search`). Add pagination (`Pageable`) to it and to the anomalies and history endpoints.
- The `payments` table and `PaymentRepository` are unused (only account deletion touches them). Wire them up or remove them.
- Rotating `JASYPT_ENCRYPTOR_PASSWORD` makes all stored Plaid tokens undecryptable. Document a key-rotation procedure, or move to KMS envelope encryption.

---

## 6. Reliability & operations

- **Schedulers vs multiple tasks.** With ECS desired count > 1, every cron job runs N times. Add **ShedLock** (JDBC lock provider) and a thread-pool scheduler before scaling out.
- **`contextLoads` (`FinanceCoachApplicationTests`) is skipped in CI** because it needs a real DB and secrets. Point it at the Testcontainers Postgres the repository tests use (`PostgresRepositoryTest`), with dummy values for the secrets.
- **Retries:** Plaid calls have no retry with backoff (timeouts are set: Plaid 60s, Bedrock 2 min per call, OpenAI 5s/30s).
- **Logging:** structured JSON logs with a request ID and user ID in MDC.
- **Errors:** the remaining bare `RuntimeException`s are internal (Stripe webhook processing, a missing seeded plan or price ID, `EmailService`) and answer 500 on purpose; webhooks rely on it for Stripe's retries. Give them typed exceptions if they ever reach users.

---

## 7. Testing

Service-layer unit tests are at ~98% lines / ~88% branches, enforced by JaCoCo in CI, with `@WebMvcTest` security tests. The remaining gap:

- **More repository tests.** `src/test/java/.../repository` runs against Postgres 16 + pgvector in Testcontainers and covers usage reservation, bank connection counts, the guarded embedding insert, `findWithoutEmbedding`, the active-subscription query and the user foreign keys. Not covered yet: `UserRepository.lockById`, the `BudgetRepository` totals, and the analytics and search queries.

---

## 8. Product & feature ideas

Ideas that build on what's already here:

- **Proactive insights feed.** After each sync, run anomaly detection and budget checks, then push notifications or email ("You've hit 90% of Dining with 12 days left"). The data and the `shouldAlert()` logic already exist.
- **Cash-flow forecast.** Project end-of-month balance from recurring income and bills plus average discretionary spend.
- **Goals in the coach.** Savings goals exist; inject them into the AI coach's prompt so it can reference them.
- **AI coach memory.** Persist durable facts the user shares ("saving for a house, $60k by 2028") per user, and inject them into the system prompt.
- **Multi-currency.** `currencyCode` is stored but ignored in all sums.
- **Admin dashboard.** MRR, active subs and AI cost per user. `PaymentRepository` already has revenue queries waiting to be used.
- **AI cost telemetry.** Record input and output tokens from Bedrock responses per user, so you can price tiers against real cost.
