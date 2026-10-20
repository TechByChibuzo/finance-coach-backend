# Backend Review: Findings & Improvement Plan

> Reviewed 2026-10-13 against `main` @ `8dbebbc`. Companion to [SYSTEM_DESIGN.md](SYSTEM_DESIGN.md).
>
> **Status (2026-10-20):** the P0 and P1 items below have been fixed (see `git log`), and the
> `REVIEW(...)` source comments have been removed. The P2/P3 items are still open where not noted otherwise.
> New open items from a follow-up review are listed under [Open: follow-up review](#open-follow-up-review-2026-10-20).

Severity scale: **P0** = security hole or loses money or data, fix now · **P1** = wrong results users will notice · **P2** = scalability, robustness, maintainability · **P3** = polish.

---

## Summary: top 10 to fix first

| # | Sev | Issue | Where |
|---|---|---|---|
| 1 | P0 | Any logged-in user can list **every user's** email/name, or look one up by ID | `UserController` `GET /api/users`, `GET /api/users/{id}` |
| 2 | P0 | Plaid **access token written to logs** on any holdings error | `PlaidService.java:256` |
| 3 | P0 | `/api/plaid/**` is `permitAll` | `SecurityConfig.java:36` |
| 4 | P0 | In-app "cancel" never cancels in Stripe (the Stripe subscription ID is never saved), so users keep getting billed | `SubscriptionService.createSubscription` / `cancelSubscription` |
| 5 | P0 | Renewals are not processed: every paid sub expires locally after one cycle while Stripe keeps charging | `SubscriptionService` webhook stubs |
| 6 | P0 | Plan limits aren't enforced: `@RequiresFeature` / `@TrackUsage` are never applied, so FREE users get unlimited AI and export | controllers |
| 7 | P0 | Anyone can mark another user's anomaly reviewed (IDOR) | `AnomalyDetectionController.markAsReviewed` |
| 8 | P1 | Transactions and holdings attributed to the **wrong account**, and holdings double-counted, when a Plaid item has >1 account | `TransactionService:164`, `InvestmentService:148` |
| 9 | P1 | Account balances are **never refreshed** after linking, so net worth goes stale | `PlaidService` / `NetWorthService` |
| 10 | P1 | Spending/income totals include transfers and credit-card payments (double counting) | `AnalyticsService` |

---

## Open: follow-up review (2026-10-20)

Edge cases, none reachable with today's production data or traffic. Fix before taking real payments or onboarding real users (#1 and #2 first).

| # | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| 1 | P1 | A sync in flight during account deletion can write transactions after the purge, and `plaidItemRepository.save(item)` can re-insert the deleted item **with its access token**. Only `transaction_embeddings` has a parent FK. | `TransactionService.syncItem` | Add parent FKs with `ON DELETE CASCADE` in `DatabaseConstraints` (transactions → bank_accounts, bank_accounts / plaid_items → users), deleting orphans first, so late writes fail. |
| 2 | P1 | Two concurrent checkout webhooks for one user can both see no active subscription and create two ACTIVE rows; `findActiveByUserId` (an `Optional`) then throws on every plan check for that user. | `SubscriptionService.createSubscription` | `userRepository.lockById(userId)` at the start of the (already transactional) method. |
| 3 | P1 | On upgrade, a paid row with no `stripeSubscriptionId` is marked cancelled locally while Stripe keeps billing it. No such rows exist in production today. | `SubscriptionService.createSubscription` | Cancel every other running Stripe subscription for the customer (`listSubscriptions`), excluding the new one, like the cancel path does. |
| 4 | P2 | Parallel budget creation can exceed the plan cap (two requests at 2/3 both take the last slot). | `BudgetService.createBudget`, copy path | Lock the user row before counting, as `PlaidService.exchangePublicToken` does. |
| 5 | P2 | Legacy bank connections (accounts with no `item_id`) are counted by distinct *encrypted* access token. Jasypt salts randomly, so the same token never matches and each legacy account counts separately, which could wrongly block a user. No legacy rows exist in production today. | `BankAccountRepository.countLegacyConnections` | Load the legacy accounts and count distinct decrypted tokens in Java, or backfill `item_id` via `PlaidService.getItem`. |
| 6 | P2 | An embedding job that started before a Plaid modification can finish after it and store stale text; the newer job then hits `ON CONFLICT DO NOTHING`. | `VectorStoreService.insertEmbedding` | Only insert while the transaction's current fields match those the text was built from, and use `ON CONFLICT DO UPDATE`. |

**Testing gap:** repository tests mock the queries, so SQL, encryption and locking are never exercised (that's how #5 shipped). Add Testcontainers (Postgres + pgvector) tests for the custom queries: usage reservation, connection counts, guarded embedding insert, and the FKs above.

---

## 1. Security

### P0: User enumeration / PII leak
`GET /api/users` returns all users. `GET /api/users/{id}` returns any user. Both only require *a* valid JWT, and registration is open, so anyone can create an account and download the user list.
**Fix:** delete both endpoints, or gate them behind an `ADMIN` role (`@PreAuthorize("hasRole('ADMIN')")` + `@EnableMethodSecurity`).

### P0: Plaid access token logged
`PlaidService.getHoldings` logs `"Access Token: {}"` at ERROR level. Anything that reads CloudWatch logs can then pull the user's bank data. Remove the line. Then consider rotating any tokens that are already in log history (`/item/access_token/invalidate`).

### P0: `/api/plaid/**` is public
Today the controllers cast an anonymous principal to `UUID`, which throws a `ClassCastException` and returns a 500, so nothing leaks. One refactor away, it could. Remove the `permitAll` line. If you later add Plaid webhooks, permit only `/api/plaid/webhook` and verify Plaid's JWT signature there.

### P0: Anomaly IDOR
`PUT /api/anomalies/{id}/review` never checks ownership. Pass `userId` in and verify `anomaly.getUserId().equals(userId)`, as `BudgetService.deleteBudget` already does.

### P1: Actuator is public in prod
`/actuator/**` is `permitAll`, and `prometheus` and `metrics` are exposed. That reveals business metrics (registrations, logins, AI usage) and JVM internals. Permit only `/actuator/health/**` publicly. Scrape Prometheus over a private network, or put basic auth in front of it.

### P1: No brute-force protection on login / reset
There is no rate limiting on `/api/auth/login`, `/register` or `/forgot-password`. Add Bucket4j (per-IP + per-email) or AWS WAF rate rules on the ALB.

### P1: Email case sensitivity
`Foo@x.com` and `foo@x.com` become two accounts, and login fails if the user types different casing. Normalize with `email.trim().toLowerCase(Locale.ROOT)` on register, login and reset. Add a unique index on `lower(email)`.

### P1: JWTs survive password reset and account deletion
Tokens last 24h with no revocation. Add a `tokenVersion` (or `passwordChangedAt`) column on `users`, embed it in the token, and reject tokens whose version is stale. Consider short access tokens (15 min) plus a refresh token.

### P2: Other hardening
- **CORS** allows `https://*.vercel.app`, which includes anyone's Vercel app. Pin it to your exact frontend origin(s) via config. Drop the per-controller `@CrossOrigin` annotations, which conflict (one hard-codes `localhost:5173`).
- **HTML injection in emails**: `fullName` is `String.format`-ed into the HTML emails unescaped. Escape it (`HtmlUtils.htmlEscape`).
- **CSV formula injection** in exports: prefix cells starting with `= + - @` with `'`.
- **Password reset tokens** are stored in plaintext. Store a SHA-256 hash and compare hashes.
- **Login timing**: an unknown email skips BCrypt, so response time reveals which emails exist. Run a dummy `matches` on miss.
- `User` uses Lombok `@Data`, so `toString()` includes `passwordHash`. Exclude it (`@ToString.Exclude`), and prefer `@Getter/@Setter` on entities.
- `FeatureAccessAspect.getCurrentUserId()` throws a plain `RuntimeException` (→ 500) instead of an authentication error (→ 401).

---

## 2. Billing (Stripe)

The money path needs the most work.

| Problem | Effect |
|---|---|
| `createSubscription` never sets `stripeSubscriptionId` | `cancelSubscription` sees `null`, skips Stripe, **doesn't even change local status**, and returns "cancelled successfully". The user stays billed. |
| `invoice.payment_succeeded` is a stub | The local `endDate` is never extended. `findActiveByUserId` requires `endDate > now`, so paying users silently drop to FREE after a month. |
| `customer.subscription.deleted/updated` and `invoice.payment_failed` are stubs | Cancellations from the Stripe portal, dunning failures and plan changes are ignored. |
| `handleCheckoutCompleted` catches all exceptions | Webhook returns 200 even on failure, so Stripe never retries. |
| No idempotency | Stripe retries can create duplicate local subscriptions. |
| `TRIAL` status isn't treated as active | `startTrial` subscriptions never grant access. |
| `findPlanByStripePriceId` uses `findAll()` + filter | Minor, but add `findByStripePriceIdMonthlyOrStripePriceIdYearly`. |

**Recommended model:** Stripe is the source of truth and the local table is a cache.
1. Pass `client_reference_id = userId` (and `metadata.userId`) when creating the Checkout Session.
2. On `checkout.session.completed` **and** `customer.subscription.created/updated/deleted`, upsert `user_subscriptions` by `stripe_subscription_id`, copying `status`, `current_period_end`, `cancel_at_period_end` and the price ID.
3. Cancel with `cancel_at_period_end=true` so users keep access until the period ends.
4. Add a `processed_stripe_events(event_id PK)` table and skip events already seen.
5. Let exceptions propagate (return 5xx) so Stripe retries.
6. Offer the Stripe Billing **Customer Portal** for card updates and cancellation, so you don't build those screens.

---

## 3. Feature gating & usage limits

`@RequiresPlan`, `@RequiresFeature`, `@TrackUsage` and `FeatureAccessAspect` are well designed but **applied nowhere**. Suggested wiring:

```java
@PostMapping("/chat")
@RequiresFeature("ai_coach_message")
@TrackUsage(feature = "ai_coach_message")
public ResponseEntity<...> chat(...)
```

Apply the same pattern to `export` (`report_export`), `POST /api/budgets` (`budget_create`) and `exchange-token` (`bank_account`; count active accounts rather than calls).

Also:
- `hasFeatureAccess` returns **false** when the flag row is missing. Seed `feature_flags` (see §7, Flyway), or every gated endpoint will 403.
- Limits are hard-coded in `getFeatureLimit` while `subscription_plans.limits` (jsonb) sits unused. Parse the JSON so you can change limits without a deploy.
- `trackUsage` does read-modify-write, so concurrent requests lose increments. Use an atomic `UPDATE … SET usage_count = usage_count + 1` with an upsert (`INSERT … ON CONFLICT`) and a unique key on (user_id, feature_name, period_start).
- Check and increment are separate steps, so two parallel chats can both pass at limit−1. Increment first, then refund on failure.
- Don't count failed AI calls. `ClaudeService` currently returns an apology string instead of throwing (see §5).

---

## 4. Plaid integration

### P1: Item vs account confusion
A Plaid **item** (one bank login) has one access token and many accounts. The code stores the token on every account row and syncs per account:
- `TransactionService.syncTransactions` calls `/transactions/get` with the item token, which returns transactions for **all** accounts in the item. Every one gets `accountId = this account`. The first account synced "wins" all of them. Later accounts skip them as duplicates.
- `InvestmentService.saveOrUpdateHolding` does the same with holdings, so each investment account in an item gets every holding, and **portfolio value is multiplied** by the number of accounts.
- Syncing N accounts makes N identical API calls.

**Fix:** introduce `plaid_items(id, user_id, item_id, access_token_enc, institution_id, cursor, status)`. Sync **per item**, and map each transaction or holding via `plaidTx.getAccountId()` → `bank_accounts.plaid_account_id`.

### P1: Use `/transactions/sync` instead of `/transactions/get`
The current code:
- only looks back 30 days, so a newly linked account gets 30 days of history instead of the 24 months Plaid provides;
- ignores pagination (`count` defaults to 100, so busy accounts lose transactions);
- never handles **removed** or **modified** transactions. When a pending charge posts, it gets a new ID, so you end up with both the pending and the posted copy, and spending is double-counted.

`/transactions/sync` with a stored cursor gives you added, modified and removed sets incrementally. Pair it with the `SYNC_UPDATES_AVAILABLE` **webhook** and you can drop most of the cron polling.

### P1: Balances never refresh
`currentBalance` is written once, in `exchangePublicToken`. Refresh it on each sync (`/accounts/get` is free with transactions). Net worth and cash figures are wrong until you do.

### P2: Other
- **Re-linking a disconnected account is impossible.** `existsByPlaidAccountId` (global, ignores `isActive`) skips it. Reactivate it and update the token instead.
- **Disconnect doesn't call `/item/remove`**, so Plaid keeps billing you for the item. Do the same on user deletion.
- **No handling of `ITEM_LOGIN_REQUIRED`.** Surface a "reconnect" state and use Link update mode.
- **`autoSyncHoldings` every 30 min is wasted work.** Plaid refreshes holdings about once a day. It also holds one DB transaction open for the whole loop, including the 2s sleeps, and blocks the single scheduler thread. Run it once daily.
- `Holding.type` is `nullable=false`, but the type is only set when a matching security is found, so a missing security makes the insert fail.
- Holdings that were sold are never deleted. Delete holdings for the account that weren't in the latest response.
- Gain/loss treats a missing cost basis as $0, which inflates gains. Exclude holdings without a cost basis from both totals.
- `PlaidConfig` maps only sandbox and production. Plaid's `development` environment has been retired, which is fine, but log a warning on unknown values.

---

## 5. AI coach & RAG

### P1: Pure vector retrieval can't answer aggregate questions
"How much did I spend on food last month?" retrieves the 10 most *similar* transaction sentences, not all food transactions in the month. Claude then sums 10 rows and reports a confidently wrong number. Options, in order of value:
1. **Tool use (recommended).** Give Claude tools such as `get_spending_by_category(start,end)`, `get_transactions(filter)`, `get_budget_status(month)` and `get_net_worth()` that call your existing `AnalyticsService`/`BudgetService`. Bedrock's Anthropic Messages API supports `tools`. Keep RAG for fuzzy lookups ("that sushi place I went to").
2. **Always inject a compact structured summary.** Put this month's totals by category, top merchants and budget status into the system prompt, alongside retrieved rows. `buildUserFinancialContext()` already exists but is unused.
3. **Hybrid retrieval.** Parse dates and categories from the question and filter SQL (`WHERE date BETWEEN … AND category = …`) before vector ranking.

### P1: Errors are saved as assistant replies
`ClaudeService.chat` catches every exception and returns "I'm having trouble connecting…". `AICoachService` then **stores that in conversation history**, and it will count against usage once gating is on. Throw a typed exception (`AiUnavailableException` → 503), and don't persist failed turns.

### P2: Retrieval quality and cost
- The embedded text includes the account **UUID** (`EmbeddingService:34`). It's random noise that hurts similarity. Use the institution or account name, or drop it.
- **No ANN index.** Add `CREATE INDEX ON transaction_embeddings USING hnsw (embedding vector_cosine_ops);`. It doesn't matter much at small per-user counts (the `user_id` filter dominates), but you'll want it as data grows. Add a `user_id` b-tree index too.
- **Embedding runs synchronously in the sync loop**, one OpenAI call per transaction. A 500-transaction first sync makes 500 sequential HTTP calls inside a user request. Batch them (`embeddingModel.embed(List<String>)`) and move the work to an async worker or queue.
- An OpenAI outage while indexing silently skips a transaction forever. Track `indexed_at` on `transactions` and re-index rows where it's null.
- No similarity threshold, so irrelevant rows still get injected. Filter by distance (`< 0.6` or similar).
- **Prompt injection:** merchant names come from banks and go straight into the system prompt. Low risk, but wrap the context in delimiters and tell the model that context is data, not instructions.
- **Cost controls:** set a max-tokens cap per endpoint (reports can be long; chat should be short), and consider caching weekly and monthly reports per user per period. They're expensive and deterministic enough.
- **Streaming:** `InvokeModelWithResponseStream` + SSE would make chat feel far faster.
- `ConversationService.getHistory` loads the whole session, then slices it. Query `ORDER BY created_at DESC LIMIT 10` instead.
- `ConversationService.clearSession` calls a derived `deleteBy…` without `@Transactional`. Spring Data needs a transaction for derived deletes, so this likely throws `TransactionRequiredException`. Add `@Transactional`.
- Region (`US_EAST_1`) and model are hard-coded or have defaults that aren't valid Bedrock IDs locally (`claude-sonnet-4-5-20250929` in `application.yml`). Prod overrides this via `CLAUDE_MODEL`. Make the region configurable and make the local default a real Bedrock ID.
- `anthropic-java` and the unused `CLAUDE_API_KEY` env var can go, since you call Claude through Bedrock.

---

## 6. Analytics, budgets, anomalies: correctness

- **Transfers and card payments counted as spending/income.** `AnalyticsService` sums every positive amount, so paying your credit card (`LOAN_PAYMENTS` / `TRANSFER_OUT`) counts as spending *on top of* the card purchases themselves. Exclude `TRANSFER_IN`, `TRANSFER_OUT` and `LOAN_PAYMENTS` (and treat `INCOME` explicitly) in one shared predicate. `BudgetService` already excludes some of these, so the two services disagree.
- **Pending transactions** are included. Decide on a rule (usually: exclude pending from totals, or replace them when they post; `/transactions/sync` handles this).
- **Anomaly duplicates daily:** `periodEnd = today + 1`, so the "already exists" check never matches on a later day. Every run inserts fresh anomalies. Use `periodEnd = month end`.
- **Anomaly statistics:** months with zero spend in a category are missing from the history list, which biases the mean up and the standard deviation down. Fill missing months with 0. You're also comparing a *partial* current month to *full* past months. Either pro-rate (`currentSpend / dayOfMonth * daysInMonth`) or compare month-to-date against the same days of past months.
- `analyzeCategorySpending` uses `isAfter(start) && isBefore(end)`, which excludes today and the boundary day. It also loads every transaction in the category ever. Use a dated query.
- **GET endpoints write to the DB:** `getBudgetsForMonth` updates `spent` during a read, without a transaction. Compute `spent` on read and don't persist it, or refresh it from the sync pipeline.
- `BudgetService` validates things Bean Validation already checks. That's harmless, but you can delete the duplicates.
- **Performance:** every analytics call loads all rows into Java, and `getMonthlySummary` queries the same range 5 times. Use SQL `GROUP BY` projections (`SELECT category, SUM(amount) … GROUP BY category`).
- `NetWorthService.getCreditCardDebt` NPEs on a null balance (the cash path filters nulls, this one doesn't). Plaid `loan` accounts (student loans, mortgages) are ignored.
- Money types mix `Double` and `BigDecimal` (`alertThreshold`, percentages, `BudgetRepository` totals). Keep money in `BigDecimal` with explicit `precision/scale` on every money column.

---

## 7. Data & persistence

- **`ddl-auto: update` in production.** Hibernate never drops or renames columns and can't add constraints safely. Adopt **Flyway**: generate a baseline from the current schema, set `ddl-auto: validate`, and put seed data (plans, feature flags) in migrations.
- **No foreign keys.** `DELETE /api/users/me` deletes the user row and orphans bank accounts (with encrypted access tokens), transactions, embeddings and conversations. The schedulers keep syncing the orphaned accounts. Add FKs with `ON DELETE CASCADE`, call Plaid `/item/remove`, and cancel Stripe on deletion. For privacy compliance, deletion should be complete.
- No uniqueness on `(user_id, snapshot_date)` for net-worth and portfolio snapshots, so a rerun or second instance creates duplicates.
- `UserSubscriptionRepository.findByUserId` returns `Optional`, but users can have many rows. That throws `IncorrectResultSizeDataAccessException` if anything ever calls it.
- Lists are unbounded: `GET /api/transactions` returns everything. Add pagination (`Pageable`) to transactions, anomalies and history endpoints.
- Unused tables and repositories (`payments` and most `PaymentRepository` queries; `TransactionEmbeddingRepository.findSimilarTransactions`) should be wired up or removed.
- Note: rotating `JASYPT_ENCRYPTOR_PASSWORD` makes all stored Plaid tokens undecryptable. Document a key-rotation procedure, or move to KMS envelope encryption.

---

## 8. Reliability & operations

- **Schedulers vs multiple tasks.** With ECS desired count > 1, every cron job runs N times (duplicate snapshots, N× Plaid calls). Add **ShedLock** (JDBC lock provider) and a thread-pool scheduler (`spring.task.scheduling.pool.size`).
- **CI deploys without tests.** The workflow builds with `-DskipTests` and pushes `:latest`. Add a `mvn verify` job (with Testcontainers Postgres + pgvector) before deploy. Tag images with the git SHA and register a new task-definition revision per deploy so rollbacks are one click.
- **`contextLoads` needs a real DB and secrets**, so it fails in CI as written. Use Testcontainers, or a `test` profile with mocks.
- **Timeouts:** Plaid (retrofit), Bedrock and OpenAI clients use default timeouts. Set explicit connect and read timeouts, and add retries with backoff (Resilience4j) for Plaid and OpenAI.
- **Health checks:** expose `/actuator/health/liveness` and `/readiness` to the ALB.
- **Logging:** use structured JSON logs (logstash encoder) with a request ID and user ID in MDC. Tone down `logger.info` in hot paths (`AnomalyDetectionService` has a leftover "ADD THIS DEBUG LOG").
- **Errors:** many services throw a bare `RuntimeException` ("Too many password reset requests", "Unauthorized access to asset"), which becomes a 500 with a generic message. Map them to typed exceptions (429, 403, 404). Some controllers (`InvestmentController`, `SubscriptionController`) also catch exceptions themselves and echo `e.getMessage()` to clients. Let `GlobalExceptionHandler` own this.
- **Stale config:** `OpenApiConfig` lists `localhost:8080` and an old Render URL, and `render.yaml` is unused. Update or remove them.
- `S3Service` hard-codes the bucket name and region. Move both to config, and add an S3 lifecycle rule to expire `exports/` after a few days.

---

## 9. Code quality

- **Duplicated `getCurrentUserId()`** in all 12 controllers. Replace it with `@AuthenticationPrincipal UUID userId` as a method parameter, or a small `CurrentUser` helper.
- **Mixed injection styles**: constructor injection in most places, field `@Autowired` in others (`MetricsService` everywhere, `SubscriptionService`, `InvestmentService`, `NetWorthService`). Standardize on constructor injection (`@RequiredArgsConstructor`). It makes the unit tests simpler.
- **Header comments say `userservice`** in several files (`// src/main/java/com/financecoach/userservice/...`). Delete them; the path is already known.
- `System.out.println` in `SecurityConfig`, `System.err` in `SubscriptionService`. Use the logger.
- Artifact is named `user-service` and the main class `UserServiceApplication`, but it's the whole backend. Consider renaming to `finance-coach-backend`.
- Leftover markers (`✅ UPDATED`, `// Add this annotation`, `// ADD THIS DEBUG LOG`) and commented-out code can go.
- Lombok `@Data` on JPA entities generates `equals`/`hashCode` over all fields, which breaks with lazy proxies and collections. Use `@Getter @Setter`, with ID-based equality if needed.
- Use a typed enum for plan names instead of the strings "FREE"/"PREMIUM"/"PRO". `getPlanLevel` is duplicated in the aspect and the service.

---

## 10. Testing

Current state: 47 unit tests across 4 classes, plus `contextLoads`. The biggest gaps, by risk:

1. **Security tests** (`@WebMvcTest` + `spring-security-test`): every non-public route returns 401 without a token, and ownership checks return 403 for another user's resource. These would have caught findings 1, 3 and 7.
2. **Stripe webhook tests** with fixture payloads, for each event type and for idempotency.
3. **Transaction sync** with a multi-account item fixture (catches finding 8).
4. **Repository tests** on Testcontainers Postgres + pgvector (vector query, custom JPQL).
5. **Anomaly detection** edge cases (zero months, partial month, duplicate runs).

Add JaCoCo to the build and aim coverage at services and security first.

---

## 11. Product & feature ideas

Ideas that build on what's already here:

- **Proactive insights feed.** After each sync, run anomaly detection and budget checks, then push notifications or email ("You've hit 90% of Dining with 12 days left"). The data and the `shouldAlert()` logic already exist.
- **Recurring-charge detection.** Group by merchant and roughly equal amount at monthly or yearly cadence, to find subscriptions and flag price increases ("Netflix went from $15.49 to $17.99"). Plaid also has `/transactions/recurring/get`.
- **Cash-flow forecast.** Project end-of-month balance from recurring income and bills plus average discretionary spend.
- **Goals.** Savings goals with target dates, with progress tracked from account balances. The AI coach can reference them.
- **User-editable categories and rules.** Let users re-categorize ("Costco → Groceries, not Shopping") and remember the rule. This also improves budgets.
- **AI coach memory.** Persist durable facts the user shares ("saving for a house, $60k by 2028") per user, and inject them into the system prompt.
- **Weekly digest email** using the existing `generateWeeklySummary` and SendGrid, sent from a Sunday cron.
- **Multi-currency.** `currencyCode` is stored but ignored in all sums.
- **Admin dashboard.** MRR, active subs and AI cost per user. `PaymentRepository` already has revenue queries waiting to be used.
- **AI cost telemetry.** Record input and output tokens from Bedrock responses per user, so you can price tiers against real cost.
