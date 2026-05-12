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
- [Getting Started](#getting-started)
- [Environment Variables](#environment-variables)
- [Running the App](#running-the-app)
- [API Overview](#api-overview)
- [Key Design Decisions](#key-design-decisions)
- [Observability](#observability)
- [Docker](#docker)

---

## Features

- **AI Finance Coach** — Conversational AI coach powered by Claude (Anthropic), with multi-turn conversation memory and session management
- **RAG Pipeline** — Transaction data is embedded via OpenAI and stored in pgvector; user questions retrieve semantically relevant transactions before calling Claude, grounding responses in real spending data
- **Plaid Integration** — Full bank account connection flow (Link token → public token → access token), transaction sync (manual + scheduled at 6AM/6PM daily), investment holdings
- **Budget Tracking** — Create and manage monthly budgets per category; spending is automatically calculated and mapped from Plaid's Personal Finance Categories (PFC)
- **Analytics** — Spending by category, top merchants, daily trends, monthly summaries, month-over-month comparisons
- **Net Worth Tracking** — Manual assets and liabilities, automated net worth snapshots, portfolio holdings from Plaid
- **Subscription & Payments** — Stripe-powered subscription tiers with feature gating and usage limits
- **JWT Authentication** — Stateless auth with password reset via SendGrid email
- **Observability** — Prometheus metrics, Spring Actuator health checks, structured logging

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.3.6 |
| Database | PostgreSQL + pgvector extension |
| AI / LLM | Anthropic Claude (claude-sonnet-4-6) |
| Embeddings | OpenAI text-embedding-3-small |
| RAG Framework | Spring AI 1.0.0 |
| Bank Data | Plaid API |
| Payments | Stripe |
| Email | SendGrid |
| Auth | JWT (jjwt 0.12.5) + Spring Security |
| Property Encryption | Jasypt |
| Metrics | Micrometer + Prometheus |
| API Docs | SpringDoc OpenAPI (Swagger UI) |
| Build | Maven |
| Container | Docker (eclipse-temurin:21) |

---

## Architecture

The backend is structured in standard Spring Boot layers:

```
Frontend (React)
      │
      ▼
 JWT Auth Filter
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
   │  (Anthropic API)    (OpenAI API)         │
   │       │                                  │
   │  ConversationService                     │
   │  (PostgreSQL)                            │
   └──────────────────────────────────────────┘
   │  TransactionService ──► PlaidService     │
   │       │                                  │
   │       ▼                                  │
   │  VectorStoreService (indexes embeddings) │
   └──────────────────────────────────────────┘
      │
      ▼
 PostgreSQL + pgvector
```

### RAG Pipeline

When a user sends a chat message:
1. The message is embedded via OpenAI (`text-embedding-3-small`)
2. pgvector performs a cosine similarity search (`<=>`) over the user's indexed transactions
3. The top 10 most relevant transactions are injected into Claude's system prompt
4. Claude responds with advice grounded in the user's actual spending data
5. The turn is saved to `conversation_messages` for multi-turn context

### Spending Anomaly Detection

1. Calculates mean and standard deviation of spending per category over 6 months
2. Computes Z-score for current month: `z = (current - mean) / stdDev`
3. Flags anomalies above threshold: LOW (1.5), MEDIUM (2.0), HIGH (3.0+)
4. Saves detected anomalies with severity for user review

### Transaction Sync

- Transactions are fetched from Plaid using the `/transactions/get` endpoint
- Each transaction is converted from Plaid's PFC (Personal Finance Category) format to the app's budget categories
- New transactions are saved to PostgreSQL and asynchronously indexed into pgvector
- A scheduler runs at 6AM and 6PM daily to auto-sync all active accounts

---

## AWS Infrastructure

The backend is deployed on AWS using a fully automated CI/CD pipeline.

| Component | Service |
|---|---|
| Container Runtime | AWS ECS Fargate |
| Container Registry | Amazon ECR |
| Database | Amazon RDS PostgreSQL 15 |
| AI Inference | AWS Bedrock (Claude) |
| Load Balancer | Application Load Balancer (ALB) |
| SSL Certificate | AWS Certificate Manager (ACM) |
| Secrets | AWS Secrets Manager |
| Monitoring | CloudWatch + Container Insights |
| CI/CD | GitHub Actions → ECR → ECS |

## Getting Started

### Prerequisites

- Java 21
- Maven 3.9+
- PostgreSQL 15+ with the `pgvector` extension enabled
- API keys for: Anthropic, OpenAI, Plaid, Stripe, SendGrid

### Database Setup

```sql
CREATE DATABASE financecoach;
\c financecoach
CREATE EXTENSION IF NOT EXISTS vector;
```

Spring Boot will auto-create all tables on first run (`ddl-auto: update`).

### Clone & Build

```bash
git clone https://github.com/<your-username>/finance-coach-backend.git
cd finance-coach-backend
mvn clean package -DskipTests
```

---

## Environment Variables

Create a `.env` file or set these in your environment before running:

```env
# Database
DB_USERNAME=your_db_username
DB_PASSWORD=your_db_password

# Anthropic (Claude)
CLAUDE_API_KEY=sk-ant-...

# OpenAI (Embeddings)
OPENAI_API_KEY=sk-proj-...

# Plaid
PLAID_CLIENT_ID=your_plaid_client_id
PLAID_SECRET=your_plaid_secret

# Stripe
STRIPE_API_KEY=sk_live_...
STRIPE_WEBHOOK_SECRET=whsec_...
STRIPE_PREMIUM_MONTHLY_PRICE_ID=price_...
STRIPE_PREMIUM_YEARLY_PRICE_ID=price_...
STRIPE_PRO_MONTHLY_PRICE_ID=price_...
STRIPE_PRO_YEARLY_PRICE_ID=price_...

# SendGrid
SENDGRID_API_KEY=SG....
SENDGRID_FROM_EMAIL=noreply@yourdomain.com
SENDGRID_FROM_NAME=Finance Coach

# Security
JASYPT_ENCRYPTOR_PASSWORD=your_encryption_password

# App
APP_FRONTEND_URL=http://localhost:5173
SPRING_PROFILES_ACTIVE=dev
```

> **Important:** Never commit real API keys. The `application.yml` fallback values are for local development only and must be replaced before deploying.

---

## Running the App

### Local (Maven)

```bash
mvn spring-boot:run
```

The API will be available at `http://localhost:8090`.

Swagger UI: `http://localhost:8090/swagger-ui.html`

API Docs: `http://localhost:8090/api-docs`

### Local (JAR)

```bash
mvn clean package -DskipTests
java -jar target/*.jar
```

---

## API Overview

| Domain | Base Path | Key Endpoints |
|---|---|---|
| Auth | `/api/auth` | `POST /register`, `POST /login`, `POST /forgot-password`, `POST /reset-password` |
| Users | `/api/users` | `GET /me`, `DELETE /me` |
| Plaid | `/api/plaid` | `POST /create-link-token`, `POST /exchange-token`, `GET /accounts` |
| Transactions | `/api/transactions` | `POST /sync/{accountId}`, `GET /` |
| AI Coach | `/api/ai-coach` | `POST /chat`, `DELETE /chat/{sessionId}`, `GET /weekly-summary`, `GET /monthly-report` |
| Analytics | `/api/analytics` | `GET /spending-by-category`, `GET /monthly-summary`, `GET /compare-months`, `GET /spending-trend` |
| Budgets | `/api/budgets` | `POST /`, `GET /current`, `GET /recommendations`, `POST /copy-previous`, `GET /exceeded` |
| Investments | `/api/investments` | `GET /portfolio`, `GET /holdings` |
| Net Worth | `/api/networth` | `GET /summary`, `POST /assets`, `POST /liabilities` |
| Subscriptions | `/api/subscriptions` | `POST /checkout`, `GET /current`, `POST /cancel` |

All endpoints except `/api/auth/**` require a `Bearer` JWT token in the `Authorization` header.

---

## Key Design Decisions

**RAG over fine-tuning** — Rather than fine-tuning a model on financial data, transactions are embedded and retrieved at query time. This keeps advice current without retraining and grounds Claude's responses in the user's actual data.

**Plaid PFC categories** — Plaid's Personal Finance Category (PFC) system returns standardised categories like `FOOD_AND_DRINK`. These are mapped to user-friendly budget categories (`Food & Dining`) in `BudgetService`, keeping the UI clean while preserving the original data.

**pgvector over a dedicated vector DB** — Storing embeddings in PostgreSQL alongside relational data avoids introducing a separate vector database. The `<=>` cosine similarity operator handles the retrieval efficiently for this scale.

**Conversation memory in PostgreSQL** — Conversation history is persisted per user/session. Each chat request loads the last 10 turns to maintain context without exceeding Claude's context window.

**Scheduled + manual sync** — Transactions sync automatically twice daily (6AM and 6PM) but users can also trigger a manual sync. Each sync fetches the last 30 days and skips already-indexed transactions by `plaidTransactionId`.

---

## Observability

| Endpoint | Description |
|---|---|
| `GET /actuator/health` | App health + DB status |
| `GET /actuator/metrics` | All metrics |
| `GET /actuator/prometheus` | Prometheus scrape endpoint |

Custom metrics tracked via `MetricsService`:
- AI coach request count and response duration
- Transactions synced count and sync duration
- Bank accounts connected
- Budgets created
- Plaid API call duration

---

## Docker

Build and run with Docker:

```bash
docker build -t finance-coach-backend .
docker run -p 8090:8090 \
  -e DB_USERNAME=admin \
  -e DB_PASSWORD=password \
  -e CLAUDE_API_KEY=sk-ant-... \
  -e OPENAI_API_KEY=sk-proj-... \
  -e PLAID_CLIENT_ID=... \
  -e PLAID_SECRET=... \
  -e STRIPE_API_KEY=... \
  -e JASYPT_ENCRYPTOR_PASSWORD=... \
  finance-coach-backend
```

The Dockerfile uses a multi-stage build — Maven compiles the JAR in the build stage, and only the JRE is included in the final image, keeping it lean.