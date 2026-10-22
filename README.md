# Idempotent Background Transaction Processing Service

**FDE L2 Assessment — Java Full Stack**

---

## Quick Start

### Prerequisites
- Java 17+
- Maven 3.8+

### Run the application

```bash
mvn spring-boot:run
```

The service starts on **http://localhost:8080**

Test data from the assessment (section 9.8) is auto-loaded on startup. The background scheduler processes pending transactions every **5 seconds**.

### View H2 console (in-browser DB viewer)
- URL: http://localhost:8080/h2-console
- JDBC URL: `jdbc:h2:mem:transactiondb`
- Username: `sa` / Password: *(empty)*

### Run tests

```bash
mvn test
```

---

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/transactions` | Submit a single transaction |
| POST | `/api/transactions/batch` | Submit multiple transactions |
| GET | `/api/transactions` | List all transactions |
| GET | `/api/transactions/summary` | Status count summary |
| POST | `/api/transactions/process` | Manually trigger processing cycle |

### Sample Request

```json
POST /api/transactions
{
  "transactionId": "TXN-2001",
  "requestId": "REQ-1",
  "accountId": "ACC-201",
  "transactionType": "CREDIT",
  "amount": 100.00,
  "currency": "USD",
  "sequenceNumber": 1
}
```

### Sample Summary Response

```json
GET /api/transactions/summary
{
  "RECEIVED": 0,
  "PROCESSING": 0,
  "PROCESSED": 4,
  "DUPLICATE": 1,
  "FAILED": 1,
  "RETRY_PENDING": 0
}
```

---

## Assessment Test Data (Section 9.8)

Loaded automatically on startup in the prescribed arrival order:

| Arrival | transactionId | requestId | seq | accountId | type | amount | Expected |
|---------|-------------|-----------|-----|-----------|------|--------|----------|
| 1 | TXN-2002 | REQ-2 | 2 | ACC-201 | DEBIT | 40 | RECEIVED (gap — seq 1 missing) |
| 2 | TXN-2001 | REQ-1 | 1 | ACC-201 | CREDIT | 100 | PROCESSED |
| 3 | TXN-2002 | REQ-2-DUP | 2 | ACC-201 | DEBIT | 40 | DUPLICATE |
| 4 | TXN-2003 | REQ-3 | 3 | ACC-201 | CREDIT | 25 | PROCESSED |
| 5 | TXN-2004 | REQ-4 | 4 | ACC-202 | DEBIT | -10 | FAILED (invalid amount) |
| 6 | TXN-2005 | REQ-5 | 5 | ACC-203 | CREDIT | 500 | RETRY once → PROCESSED |

**Final balance ACC-201:** +100 − 40 + 25 = **85 USD**

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                  REST API Layer                     │
│         TransactionController (/api/transactions)   │
└───────────────────┬─────────────────────────────────┘
                    │ submit / query
┌───────────────────▼─────────────────────────────────┐
│            TransactionProcessorService              │
│  - Duplicate detection (requestId uniqueness)       │
│  - Sequence-based out-of-order handling             │
│  - Balance updates (CREDIT / DEBIT / REVERSAL)      │
│  - Retry logic with max retry limit                 │
│  - Transient failure simulation                     │
└───────────┬────────────────────────┬────────────────┘
            │                        │
┌───────────▼──────────┐  ┌──────────▼──────────────┐
│  TransactionRequest  │  │    AccountBalance        │
│  Repository (JPA)    │  │    Repository (JPA)      │
└──────────────────────┘  └─────────────────────────┘
            ▲
┌───────────┴──────────────────────────────────────┐
│          TransactionProcessorScheduler           │
│   @Scheduled(fixedDelay = 5000ms)               │
│   Picks up RECEIVED + RETRY_PENDING             │
└──────────────────────────────────────────────────┘
```

---

## Design Notes

### 1. Idempotency Design
- Every transaction request carries a `requestId` (idempotency key)
- The `transaction_requests` table has a **unique constraint** on `requestId`
- On submit, the service checks `existsByRequestId()` before persisting
- If a duplicate `requestId` arrives, it is returned with status `DUPLICATE` without any database write or balance side effect
- This makes all retries from upstream clients safe — the same request can be submitted N times with the same outcome

### 2. Duplicate Detection Logic
- **At submit time:** `existsByRequestId()` check before any write
- **At process time:** only `RECEIVED` and `RETRY_PENDING` records are picked up — already `PROCESSED` or `DUPLICATE` records are never re-processed
- **Stale sequence:** if an out-of-order request arrives with a sequence number lower than what has already been processed for that account, it is marked `DUPLICATE` with a stale-sequence reason

### 3. Out-of-Order Handling Strategy — Option A: Sequence-Based
Each transaction request has a `sequenceNumber` per account. The processor:
1. Groups pending requests by `accountId`
2. Sorts each group by `sequenceNumber` ascending
3. Determines the next expected sequence (lowest unprocessed sequence for the account)
4. Processes only the matching sequence; stops at the first gap
5. Requests after a gap remain `RECEIVED` until the gap is filled in a future cycle

**Trade-off:** Simple and auditable. Works well for ordered event streams (queues, CDC). Does not require global ordering across accounts — only per-account ordering is enforced. For high-throughput scenarios, a more sophisticated buffering strategy (e.g., event sourcing with compaction) would be preferred.

### 4. Retry and Failure Handling
- On transient failure (`TransientProcessingException`), the record moves to `RETRY_PENDING` and `retryCount` is incremented
- The scheduler picks up `RETRY_PENDING` records in subsequent cycles
- After `maxRetries` (default: 3) exhausted, the record is permanently `FAILED`
- **Idempotency is preserved during retries** — the balance update only happens on `PROCESSED` transition; a retried record that already failed partially does not double-apply any effect
- `TXN-2005` simulates a transient failure: fails once, succeeds on second attempt

### 5. Transaction Status Lifecycle

```
RECEIVED → PROCESSING → PROCESSED
                      → RETRY_PENDING → PROCESSING → PROCESSED
                                                    → FAILED
                      → FAILED
DUPLICATE (set at submit, never enters processing)
```

### 6. Observability
- Structured SLF4J logs at key lifecycle events: submit, duplicate detected, processing start, balance update, retry, failure, processed
- `GET /api/transactions/summary` returns counts by status
- H2 console provides full real-time view of all transaction and balance records
- In production: replace with structured JSON logs (Logback + logstash-logback-encoder), expose metrics via Micrometer/Prometheus

### 7. AI-Assisted Development
This solution was built with AI assistance (Claude). The generated code was reviewed and validated against the requirements at each step:
- Model and enum design verified against the transaction model in section 5
- Sequence-based ordering logic reviewed against Option A in section 9.5
- Retry idempotency verified — balance update only occurs on the `PROCESSED` transition, not on `RETRY_PENDING`
- Test data matches section 9.8 exactly, including arrival order

### 8. Production Hardening Recommendations
| Area | Current (Assessment) | Production Recommendation |
|------|---------------------|--------------------------|
| Persistence | H2 in-memory | PostgreSQL / Aurora with connection pooling |
| Idempotency store | DB unique constraint | Distributed idempotency store (Redis) for multi-instance |
| Queue | Simulated in-memory | Kafka / SQS with at-least-once delivery |
| Concurrency | Single scheduler thread | Concurrent executor with per-account locking |
| Retry | In-process | Dead letter queue + separate retry processor |
| Observability | SLF4J + H2 console | Micrometer + Prometheus + Grafana + structured JSON logs |
| Security | None | OAuth2 / API key, input sanitization, secrets in Vault |
| Deployment | Local JAR | Containerized (Docker) + Kubernetes with health probes |

---

## Project Structure

```
src/
├── main/java/com/fde/transaction/
│   ├── TransactionServiceApplication.java   # Entry point
│   ├── model/
│   │   ├── TransactionRequest.java          # JPA entity + validation
│   │   ├── AccountBalance.java              # Account balance entity
│   │   ├── TransactionStatus.java           # Status enum
│   │   └── TransactionType.java             # Type enum
│   ├── repository/
│   │   ├── TransactionRequestRepository.java
│   │   └── AccountBalanceRepository.java
│   ├── service/
│   │   ├── TransactionProcessorService.java # Core processing logic
│   │   └── TransientProcessingException.java
│   ├── scheduler/
│   │   └── TransactionProcessorScheduler.java
│   ├── controller/
│   │   └── TransactionController.java
│   └── config/
│       └── DataLoader.java                  # Auto-loads test data on startup
└── test/java/com/fde/transaction/service/
    └── TransactionProcessorServiceTest.java # Integration tests
```
