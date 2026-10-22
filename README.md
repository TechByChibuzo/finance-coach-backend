# Finance Coach — Backend API

An AI-powered personal finance coaching platform. Users connect their bank accounts via Plaid, and an AI coach built on Claude analyses their real transaction data to deliver personalised spending insights, budget tracking, and financial recommendations — using a full RAG (Retrieval-Augmented Generation) pipeline backed by pgvector.

## Live Demo
- **Frontend:** https://finance-coach-frontend.vercel.app
- **API:** https://api.aifinancecoach.dev
- **API Docs:** https://api.aifinancecoach.dev/swagger-ui.html
---

## Table of Contents

- [Features](#features)
- [Tech Stack](#tech-stack)
- [Architecture](#architecture)
- [AWS Infrastructure](#aws-infrastructure)
- [Getting Started](#getting-started)
- [Environment Variables](#environment-variables)
- [Running the App](#running-the-app)
- [API Overview](#api-overview)
- [Key Design Decisions](#key-design-decisions)
- [Testing](#testing)
- [Observability](#observability)
- [Docker](#docker)

For the full architecture, see [docs/SYSTEM_DESIGN.md](docs/SYSTEM_DESIGN.md). Open issues and ideas are in [docs/REVIEW.md](docs/REVIEW.md).

---

## Features

- **AI Finance Coach** — Conversational AI coach powered by Claude via AWS Bedrock, with multi-turn conversation memory and session management
- **RAG Pipeline** — Transaction data is embedded via OpenAI (in the background, in batches) and stored in pgvector; each question gets exact monthly totals plus the most relevant transactions, grounding Claude's answers in real spending data
- **Spending Anomaly Detection** — Z-score statistical algorithm detects unusual spending patterns per category compared to 6 months of historical data
- **Plaid Integration** — Full bank connection flow (Link token → public token → access token), incremental transaction sync with `/transactions/sync` (manual + scheduled at 6AM/6PM UTC), balance refresh, investment holdings
- **Budget Tracking** — Create and manage monthly budgets per category; spending is automatically calculated and mapped from Plaid's Personal Finance Categories (PFC)
- **Analytics** — Spending by category, top merchants, daily trends, monthly summaries, month-over-month comparisons
- **Net Worth Tracking** — Manual assets and liabilities, automated net worth snapshots, portfolio holdings from Plaid
- **Subscription & Payments** — Stripe-powered subscription tiers (webhooks as the source of truth) with feature gating and atomic usage limits
- **JWT Authentication** — Stateless auth with token revocation, rate-limited auth endpoints, and password reset via SendGrid email
- **Account Deletion** — Cancels Stripe billing, revokes Plaid access and erases all of the user's data
- **Observability** — Prometheus metrics (token-protected), Spring Actuator health checks, CloudWatch logs

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.3.6 |
| Database | Amazon RDS PostgreSQL 16 + pgvector extension |
| AI / LLM | AWS Bedrock (Claude Sonnet 4.6) |
| Embeddings | OpenAI text-embedding-3-small |
| RAG Framework | Spring AI 1.0.0 |
| Bank Data | Plaid API |
| Payments | Stripe |
| Email | SendGrid |
| Auth | JWT (jjwt 0.12.5) + Spring Security |
| Property Encryption | Jasypt |
| Infrastructure | Terraform |
| Tests | JUnit 5, Mockito, JaCoCo |
| Metrics | Micrometer + Prometheus |
| API Docs | SpringDoc OpenAPI (Swagger UI) |
| Build | Maven |
| Container | Docker (eclipse-temurin:21) |

---

## Architecture

The backend is structured in standard Spring Boot layers:

```
Frontend (React/Vercel)
│
▼
https://api.aifinancecoach.dev
│
▼
AWS ALB (Application Load Balancer)
│
▼
Auth Rate Limiter → JWT Auth Filter
│
▼
Controllers  (REST endpoints — one per domain)
│
▼
Services    (business logic)
┌──────────────────────────────────────────┐
│  AICoachService ──► VectorStoreService   │
│       │                   │              │
│       ▼                   ▼              │
│  ClaudeService      EmbeddingService     │
│  (AWS Bedrock)      (OpenAI API)         │
│       │                                  │
│  ConversationService                     │
│  (PostgreSQL)                            │
└──────────────────────────────────────────┘
│  TransactionService ──► PlaidService     │
│       │                                  │
│       ▼                                  │
│  VectorStoreService (async embeddings)   │
│  AnomalyDetectionService (Z-score)       │
└──────────────────────────────────────────┘
│
▼
Amazon RDS PostgreSQL + pgvector
```

### RAG Pipeline

When a user sends a chat message:
1. One use of the plan's AI allowance is reserved atomically (given back if the call fails)
2. Exact totals for this month and last month (spending, income, categories, top merchants) are computed from the database
3. The message is embedded via OpenAI (`text-embedding-3-small`) and pgvector finds the 10 most similar transactions (`<=>`)
4. Both go into Claude's system prompt, so "how much" questions get real numbers and specific questions get matching transactions
5. The turn is saved to `conversation_messages` for multi-turn context

### Spending Anomaly Detection

1. Calculates mean and standard deviation of spending per category over 6 months
2. Computes Z-score for current month: `z = (current - mean) / stdDev`
3. Flags anomalies above threshold: LOW (1.5), MEDIUM (2.0), HIGH (3.0+)
4. Saves detected anomalies with severity for user review

### Transaction Sync

- Each Plaid item (bank login) is synced with `/transactions/sync` from a stored cursor, so only added, modified and removed transactions come back; a new connection gets up to 2 years of history
- Each transaction is attributed to the account Plaid reports, and categorized from Plaid's PFC (Personal Finance Category)
- Account balances are refreshed on every sync
- New and changed transactions are indexed into pgvector in the background, in batches; ones that failed are retried on later syncs
- A scheduler runs at 6AM and 6PM UTC to sync every connected item

---

## AWS Infrastructure

The backend runs on AWS (us-east-2). All infrastructure is defined in Terraform under [`infra/terraform`](infra/terraform), and deploys are fully automated.

| Component | Service |
|---|---|
| Container Runtime | AWS ECS Fargate (0.5 vCPU / 1 GB) |
| Container Registry | Amazon ECR |
| Database | Amazon RDS PostgreSQL 16 (db.t4g.micro, private subnets) |
| AI Inference | AWS Bedrock (Claude) |
| Load Balancer | Application Load Balancer (ALB) |
| SSL Certificate | AWS Certificate Manager (ACM) |
| Secrets | AWS Secrets Manager |
| File Storage | Amazon S3 (CSV exports) |
| Monitoring | CloudWatch Logs + Container Insights |
| Infrastructure as Code | Terraform |
| CI/CD | GitHub Actions → tests → ECR → ECS |

### Deployment Flow

```
git push main
↓
GitHub Actions runs the tests (./mvnw verify, with a coverage gate)
↓
Builds the Docker image, tagged with the git SHA, and pushes it to Amazon ECR
↓
Registers a new ECS task definition revision and deploys it
↓
Waits for the rollout; the job fails if ECS rolls back
↓
Live at https://api.aifinancecoach.dev
```

---

## Getting Started

### Prerequisites

- Java 21
- Maven 3.9+
- PostgreSQL 15+ with the `pgvector` extension available
- AWS credentials with Bedrock access to Claude (model access enabled in the Bedrock console)
- API keys for: OpenAI, Plaid, Stripe, SendGrid

### Database Setup

```sql
CREATE DATABASE financecoach;
```

On startup the app enables the `vector` extension, creates the tables (`ddl-auto: update`), adds the constraints Hibernate can't, and seeds the subscription plans.

### Clone & Build

```bash
git clone https://github.com/TechByChibuzo/finance-coach-backend.git
cd finance-coach-backend
./mvnw clean package -DskipTests
```

---

## Environment Variables

Create a `.env` file or set these in your environment before running:

```env
# Database
DB_URL=jdbc:postgresql://localhost:5432/financecoach
DB_USERNAME=your_db_username
DB_PASSWORD=your_db_password

# AWS (Bedrock for Claude, S3 for exports); credentials come from your AWS profile
AWS_REGION=us-east-2
CLAUDE_MODEL=us.anthropic.claude-sonnet-4-6
S3_EXPORT_BUCKET=your-export-bucket

# OpenAI (Embeddings)
OPENAI_API_KEY=sk-proj-...

# Plaid
PLAID_CLIENT_ID=your_plaid_client_id
PLAID_SECRET=your_plaid_secret
PLAID_ENVIRONMENT=sandbox

# Stripe
STRIPE_API_KEY=sk_live_...
STRIPE_WEBHOOK_SECRET=whsec_...

# SendGrid
SENDGRID_API_KEY=SG....
SENDGRID_FROM_EMAIL=noreply@yourdomain.com
SENDGRID_FROM_NAME=Finance Coach

# Security
JWT_SECRET=at_least_32_bytes_of_random_data
JASYPT_ENCRYPTOR_PASSWORD=your_encryption_password
METRICS_TOKEN=optional_token_for_actuator_metrics

# App
APP_FRONTEND_URL=http://localhost:5173
APP_API_URL=http://localhost:8090
SPRING_PROFILES_ACTIVE=dev
```

> **Important:** Never commit real API keys. Locally, keep them in the gitignored `application-dev.yml`. In production they come from AWS Secrets Manager (`finance-coach/prod`) and are injected into the ECS task as environment variables.

---

## Running the App

### Local (Maven)

```bash
./mvnw spring-boot:run
```

The API will be available at `http://localhost:8090`.

Swagger UI: `http://localhost:8090/swagger-ui.html`

API Docs: `http://localhost:8090/api-docs`

### Local (JAR)

```bash
./mvnw clean package -DskipTests
java -jar target/*.jar
```

---

## API Overview

| Domain | Base Path | Key Endpoints |
|---|---|---|
| Auth | `/api/auth` | `POST /register`, `POST /login`, `POST /forgot-password`, `POST /reset-password` |
| Users | `/api/users` | `GET /me`, `DELETE /me` |
| Plaid | `/api/plaid` | `POST /create-link-token`, `POST /exchange-token`, `GET /accounts`, `DELETE /accounts/{id}`, `POST /items/{itemId}/update-link-token` |
| Transactions | `/api/transactions` | `POST /sync`, `GET /`, `GET /search`, `PATCH /{id}`, `PATCH /{id}/category`, `PUT /{id}/split`, `POST /import` |
| AI Coach | `/api/ai-coach` | `POST /chat`, `DELETE /chat/{sessionId}`, `GET /weekly-summary`, `GET /monthly-report` |
| Anomalies | `/api/anomalies` | `POST /detect`, `GET /unreviewed`, `GET /count`, `PUT /{id}/review` |
| Analytics | `/api/analytics` | `GET /spending-by-category`, `GET /monthly-summary`, `GET /monthly-report`, `GET /compare-months`, `GET /spending-trend` |
| Budgets | `/api/budgets` | `POST /`, `GET /current`, `GET /recommendations`, `POST /copy-previous`, `GET /exceeded` |
| Investments | `/api/investments` | `POST /sync`, `GET /portfolio`, `GET /holdings/{accountId}`, `GET /allocation`, `GET /history` |
| Net Worth | `/api/net-worth` | `GET /current`, `GET /history`, CRUD `/assets`, CRUD `/liabilities` |
| Subscriptions | `/api/subscriptions` | `GET /plans`, `POST /checkout`, `GET /current`, `POST /cancel`, `POST /webhook` |
| Export | `/api/export` | `GET /transactions` (paid plans) |

All endpoints require a `Bearer` JWT token in the `Authorization` header, except `/api/auth/**`, `GET /api/subscriptions/plans`, the Stripe webhook (signature-verified), `/actuator/health` and Swagger.

### Plan limits

| Feature | FREE | PREMIUM | PRO |
|---|---|---|---|
| AI coach requests per month | 5 | Unlimited | Unlimited |
| Active budgets per month | 3 | Unlimited | Unlimited |
| Bank connections | 1 | 5 | Unlimited |
| CSV export | — | ✓ | ✓ |

---

## Key Design Decisions

**RAG over fine-tuning** — Rather than fine-tuning a model on financial data, transactions are embedded and retrieved at query time. This keeps advice current without retraining and grounds Claude's responses in the user's actual data.

**AWS Bedrock over direct Anthropic API** — Using Bedrock for Claude inference consolidates all AWS costs on one bill, leverages IAM roles for authentication (no API keys needed at runtime), and enables enterprise features like request logging and guardrails.

**JdbcTemplate for vector inserts** — Hibernate doesn't natively support pgvector's `vector` type. Rather than fighting with type converters, vector inserts use raw JDBC with `?::vector` casting, while reads use JPA normally.

**pgvector over a dedicated vector DB** — Storing embeddings in PostgreSQL alongside relational data avoids introducing a separate vector database. The `<=>` cosine similarity operator handles retrieval efficiently for this scale.

**Z-score anomaly detection** — A simple but effective statistical approach. By comparing current spending to 6 months of historical data per category, the algorithm flags genuinely unusual patterns without requiring ML model training or external services.

**Conversation memory in PostgreSQL** — Conversation history is persisted per user/session. Each chat request loads the last 10 turns to maintain context without exceeding Claude's context window.

**Secrets Manager for credentials** — All sensitive API keys are stored in AWS Secrets Manager and injected into the container at startup, so no secret values appear in task definitions, the repository, or Terraform variables.

**Stripe as the source of truth** — Subscription state is copied from Stripe webhooks rather than tracked separately, so renewals, failed payments and cancellations made in Stripe are always reflected in the app.

**Atomic usage limits** — Plan limits are enforced with a single `INSERT … ON CONFLICT DO UPDATE … WHERE usage_count < limit`, so parallel requests can't go over the limit, and a use is given back if the call fails.

**Terraform for infrastructure** — The whole AWS setup (VPC, ALB, ECS, RDS, IAM, secrets) is code, so it can be recreated in a new account with one `terraform apply`.

---

## Observability

| Endpoint | Description |
|---|---|
| `GET /actuator/health` | App health + DB status (public; used by the ALB health check) |
| `GET /actuator/metrics` | All metrics (requires `Authorization: Bearer $METRICS_TOKEN`) |
| `GET /actuator/prometheus` | Prometheus scrape endpoint (requires `Authorization: Bearer $METRICS_TOKEN`) |

Custom metrics tracked via `MetricsService`:
- AI coach request count and response duration
- Transactions synced count and sync duration
- Bank accounts connected
- Budgets created and exceeded
- Registrations, logins and password resets
- Plaid API call duration

Application logs go to CloudWatch Logs (`/ecs/finance-coach-backend`). CloudWatch Container Insights provides:
- CPU and memory utilization graphs
- Task count and deployment history
- Alarm state monitoring

---

## Testing

```bash
./mvnw verify
```

Runs the unit tests and enforces a coverage gate on the service layer (≥ 90% lines, ≥ 80% branches) with JaCoCo. The HTML report is written to `target/site/jacoco/index.html`. CI runs the same command before every deploy.

`UserServiceApplicationTests` (the full Spring context) needs a real database and secrets, so CI skips it with `-Dtest='!UserServiceApplicationTests'`.

---

## Docker

Build and run with Docker:

```bash
docker build -t finance-coach-backend .
docker run -p 8090:8090 \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/financecoach \
  -e DB_USERNAME=admin \
  -e DB_PASSWORD=password \
  -e JWT_SECRET=... \
  -e JASYPT_ENCRYPTOR_PASSWORD=... \
  -e OPENAI_API_KEY=sk-proj-... \
  -e PLAID_CLIENT_ID=... \
  -e PLAID_SECRET=... \
  -e STRIPE_API_KEY=... \
  -e STRIPE_WEBHOOK_SECRET=... \
  -e SENDGRID_API_KEY=... \
  -e SENDGRID_FROM_EMAIL=... \
  -e APP_FRONTEND_URL=http://localhost:5173 \
  finance-coach-backend
```

The Dockerfile uses a multi-stage build — Maven compiles the JAR in the build stage, and only the JRE is included in the final image, keeping it lean. The image starts with the `prod` profile unless `SPRING_PROFILES_ACTIVE` is set. Bedrock and S3 use the default AWS credential chain, so pass AWS credentials (or run on ECS with a task role) for the AI coach and exports to work.