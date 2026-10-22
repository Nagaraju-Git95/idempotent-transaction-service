# Interview Presentation Guide
## Idempotent Background Transaction Processing Service

---

## Table of Contents

1. [What This Project Is About](#1-what-this-project-is-about)
2. [Technologies Used](#2-technologies-used)
3. [How to Clone and Run the Application](#3-how-to-clone-and-run-the-application)
4. [Project Structure Explained](#4-project-structure-explained)
5. [How Each File Works](#5-how-each-file-works)
6. [Key Concepts — What to Say in Interview](#6-key-concepts--what-to-say-in-interview)
7. [API Endpoints — How to Demo](#7-api-endpoints--how-to-demo)
8. [How to View Data in H2 Console](#8-how-to-view-data-in-h2-console)
9. [Test Cases Explained](#9-test-cases-explained)
10. [Assessment Test Data Flow (Section 9.8)](#10-assessment-test-data-flow-section-98)
11. [Common Interview Questions and Answers](#11-common-interview-questions-and-answers)

---

## 1. What This Project Is About

This is a **Background Transaction Processing Service** built in Java using Spring Boot.

**The main problem it solves:**
- Multiple systems send the same transaction request multiple times (network retries, duplicate submissions)
- Transactions can arrive **out of order** (sequence 2 arrives before sequence 1)
- Some transactions fail temporarily and need to be **retried automatically**

**The solution provides:**
- **Idempotency** — same request submitted 10 times = processed only once
- **Out-of-order handling** — transactions are sorted by sequence number before processing
- **Automatic retry** — failed transactions are retried up to 3 times by a background scheduler
- **Status tracking** — every transaction has a lifecycle: RECEIVED → PROCESSING → PROCESSED / FAILED / DUPLICATE

---

## 2. Technologies Used

| Technology | Purpose | Why Chosen |
|---|---|---|
| **Java 17** | Programming language | LTS version, modern features |
| **Spring Boot 3.2.0** | Application framework | Auto-configuration, embedded server |
| **Spring Data JPA** | Database access | Simplifies DB queries, no boilerplate SQL |
| **H2 Database** | In-memory database | No setup needed, good for demos |
| **Spring Validation** | Input validation | Annotations like @NotBlank, @DecimalMin |
| **@Scheduled** | Background job | Built-in Spring scheduler, no extra library |
| **JUnit 5 + Spring Test** | Testing | Integration tests with real DB |
| **Maven** | Build tool | Dependency management and build |

---

## 3. How to Clone and Run the Application

### Step 1 — Prerequisites (Install these first)
```
Java 17 or higher   →  java -version
Maven 3.8 or higher →  mvn -version
```

### Step 2 — Clone the repository
```bash
git clone https://github.com/Nagaraju-Git95/idempotent-transaction-service.git
cd idempotent-transaction-service
```

### Step 3 — Run the application
```bash
mvn spring-boot:run
```

You will see logs like:
```
Loading assessment test data...
Transaction submitted: transactionId=TXN-2002 requestId=REQ-2 sequence=2 status=RECEIVED
Transaction submitted: transactionId=TXN-2001 requestId=REQ-1 sequence=1 status=RECEIVED
...
Processing 5 pending transaction(s).
PROCESSED: transactionId=TXN-2001 requestId=REQ-1 accountId=ACC-201
```

The application starts on: **http://localhost:8080**

### Step 4 — Run the tests
```bash
mvn clean test
```

Expected output:
```
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

---

## 4. Project Structure Explained

```
idempotent-transaction-service/
│
├── pom.xml                          ← Maven build file (dependencies)
├── README.md                        ← Project overview
│
└── src/
    ├── main/
    │   ├── java/com/fde/transaction/
    │   │   │
    │   │   ├── TransactionServiceApplication.java   ← Main class (entry point)
    │   │   │
    │   │   ├── model/                               ← Data classes
    │   │   │   ├── TransactionRequest.java          ← DB table for transactions
    │   │   │   ├── AccountBalance.java              ← DB table for balances
    │   │   │   ├── TransactionStatus.java           ← Enum: RECEIVED, PROCESSED...
    │   │   │   └── TransactionType.java             ← Enum: CREDIT, DEBIT, REVERSAL
    │   │   │
    │   │   ├── repository/                          ← Database access layer
    │   │   │   ├── TransactionRequestRepository.java
    │   │   │   └── AccountBalanceRepository.java
    │   │   │
    │   │   ├── service/                             ← Core business logic
    │   │   │   ├── TransactionProcessorService.java ← Main processing logic
    │   │   │   └── TransientProcessingException.java← Custom exception
    │   │   │
    │   │   ├── scheduler/                           ← Background job
    │   │   │   └── TransactionProcessorScheduler.java
    │   │   │
    │   │   ├── controller/                          ← REST API
    │   │   │   └── TransactionController.java
    │   │   │
    │   │   └── config/
    │   │       └── DataLoader.java                  ← Loads test data on startup
    │   │
    │   └── resources/
    │       └── application.properties               ← App configuration
    │
    └── test/
        └── TransactionProcessorServiceTest.java     ← 9 automated tests
```

---

## 5. How Each File Works

### TransactionServiceApplication.java
- The **entry point** of the application
- `@SpringBootApplication` — enables auto-configuration
- `@EnableScheduling` — activates the background scheduler
- Contains the `main()` method

```
"This is where the application starts. The two annotations tell Spring Boot
to auto-configure everything and to enable scheduled background tasks."
```

---

### model/TransactionRequest.java
- Represents one transaction record in the database (table: `transaction_requests`)
- Key fields:
  - `requestId` — the **idempotency key** (must be unique in DB)
  - `transactionId` — the business transaction ID
  - `sequenceNumber` — used for ordering per account
  - `status` — current lifecycle state
  - `retryCount` — how many times it has been retried
  - `failureReason` — why it failed (if applicable)
- Has a **unique constraint** on `requestId` — this is what enforces idempotency at the database level

---

### model/AccountBalance.java
- Stores the running balance for each account
- `accountId` is the primary key (one row per account)
- Updated whenever a CREDIT, DEBIT, or REVERSAL is PROCESSED

---

### model/TransactionStatus.java (Enum)
```
RECEIVED      → Transaction submitted, waiting to be processed
PROCESSING    → Currently being processed (in-flight)
PROCESSED     → Successfully completed, balance updated
DUPLICATE     → Same requestId submitted again — ignored
FAILED        → Permanently failed after all retries exhausted
RETRY_PENDING → Failed temporarily, will be retried
```

---

### repository/TransactionRequestRepository.java
- Extends `JpaRepository` — gives free CRUD methods (save, findById, findAll, delete)
- Custom methods written as method names (Spring generates the SQL automatically):
  - `findByRequestId(requestId)` → find by idempotency key
  - `findByStatusIn(list)` → find all RECEIVED + RETRY_PENDING
  - `existsByRequestId(requestId)` → duplicate check (returns true/false)
  - `countByStatus(status)` → count records per status (for summary)

---

### service/TransactionProcessorService.java
This is the **heart of the application**. It has 3 main responsibilities:

**1. submit() — Accept new transactions**
```
- Check if requestId already exists (existsByRequestId)
- If YES → return with status DUPLICATE (no DB write, no balance change)
- If NO  → save to DB with status RECEIVED
```

**2. processPending() — Called every 5 seconds by scheduler**
```
- Fetch all RECEIVED + RETRY_PENDING transactions
- Group them by accountId
- Sort each group by sequenceNumber
- For each group, find the next expected sequence
- Process in order, stop at any gap
```

**3. processSingle() — Process one transaction**
```
- Set status to PROCESSING
- Check for simulated transient failure (TXN-2005)
- Apply balance update (CREDIT/DEBIT/REVERSAL)
- Set status to PROCESSED
- If transient failure → RETRY_PENDING (retryCount++)
- If retries exhausted → FAILED
```

---

### scheduler/TransactionProcessorScheduler.java
- Runs `processPending()` automatically every **5 seconds**
- `@Scheduled(fixedDelay = 5000ms)` — waits 5 seconds after each run completes
- This is what makes it a background service — no human needs to trigger it

---

### controller/TransactionController.java
- Exposes REST API endpoints
- `POST /api/transactions` — submit one transaction
- `POST /api/transactions/batch` — submit multiple at once
- `GET /api/transactions` — list all transactions
- `GET /api/transactions/summary` — count by status
- `POST /api/transactions/process` — manually trigger one processing cycle

---

### config/DataLoader.java
- Runs automatically when the application starts (implements `CommandLineRunner`)
- Loads the 6 test records from section 9.8 of the problem statement
- Has `@Profile("!test")` — meaning it does NOT run during automated tests
  (prevents test data from interfering with test assertions)

---

### application.properties
```properties
# H2 in-memory database (resets on restart — good for demos)
spring.datasource.url=jdbc:h2:mem:transactiondb

# Scheduler runs every 5 seconds
transaction.processor.interval.ms=5000

# Max retries before marking FAILED
transaction.processor.max-retries=3

# H2 browser console enabled
spring.h2.console.enabled=true
```

---

## 6. Key Concepts — What to Say in Interview

### What is Idempotency?
> "Idempotency means that sending the same request multiple times produces the same result as sending it once. In this service, each transaction has a `requestId`. Before saving any new transaction, we check if that `requestId` already exists in the database. If it does, we return DUPLICATE and do nothing — no second balance update, no duplicate record. This makes the service safe for retries from upstream clients."

### How is Idempotency enforced at two levels?
> "First, at the application level — we call `existsByRequestId()` before saving. Second, at the database level — the `requestId` column has a unique constraint. Even if two threads race past the application check, the database will reject the second insert. This is defense in depth."

### What is Out-of-Order Handling?
> "Transactions can arrive in any order due to network conditions. For example, sequence 2 may arrive before sequence 1. We can't process sequence 2 first because it might depend on the balance after sequence 1. So we group transactions by account, sort by sequence number, and use a gap-detection algorithm. If sequence 1 is missing and we see sequence 2, we leave it as RECEIVED and wait. When sequence 1 arrives in the next cycle, it processes first, then sequence 2 becomes processable in the cycle after."

### What is the Retry Mechanism?
> "When a transient failure occurs — like a temporary network issue or downstream service unavailability — we don't want to permanently fail the transaction. Instead, we mark it RETRY_PENDING and increment a retry counter. The scheduler picks it up again in the next cycle and tries again. After 3 retries (configurable), if it still fails, it moves to FAILED permanently. To demonstrate this, TXN-2005 is hardcoded to fail on the first attempt and succeed on the second."

### Why H2 database?
> "H2 is an in-memory database that requires zero setup. For this assessment, it's perfect — just run the application and it works. In production, I would replace it with PostgreSQL or MySQL, and the only change needed is the datasource configuration and JDBC driver — the JPA code stays exactly the same."

---

## 7. API Endpoints — How to Demo

Open a terminal and run these after starting the app:

### Submit a single transaction
```bash
curl -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "TXN-100",
    "requestId": "REQ-100",
    "accountId": "ACC-100",
    "transactionType": "CREDIT",
    "amount": 500.00,
    "currency": "USD",
    "sequenceNumber": 1
  }'
```

### Submit the same request again (idempotency demo)
```bash
curl -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "TXN-100",
    "requestId": "REQ-100",
    "accountId": "ACC-100",
    "transactionType": "CREDIT",
    "amount": 500.00,
    "currency": "USD",
    "sequenceNumber": 1
  }'
```
Response will show `"status": "DUPLICATE"` — balance not applied twice.

### View all transactions
```bash
curl http://localhost:8080/api/transactions
```

### View status summary
```bash
curl http://localhost:8080/api/transactions/summary
```

### Manually trigger processing (without waiting 5 seconds)
```bash
curl -X POST http://localhost:8080/api/transactions/process
```

---

## 8. How to View Data in H2 Console

1. Start the application: `mvn spring-boot:run`
2. Open browser: **http://localhost:8080/h2-console**
3. Enter these details:
   - **JDBC URL:** `jdbc:h2:mem:transactiondb`
   - **Username:** `sa`
   - **Password:** *(leave empty)*
4. Click **Connect**

### Useful SQL queries to run in H2 console:
```sql
-- View all transactions
SELECT * FROM TRANSACTION_REQUESTS;

-- View account balances
SELECT * FROM ACCOUNT_BALANCES;

-- Count by status
SELECT STATUS, COUNT(*) FROM TRANSACTION_REQUESTS GROUP BY STATUS;

-- Check for duplicates
SELECT * FROM TRANSACTION_REQUESTS WHERE STATUS = 'DUPLICATE';

-- Check retry records
SELECT TRANSACTION_ID, REQUEST_ID, STATUS, RETRY_COUNT, FAILURE_REASON
FROM TRANSACTION_REQUESTS
WHERE STATUS IN ('RETRY_PENDING', 'FAILED');
```

---

## 9. Test Cases Explained

The test file has **9 tests**. All 9 pass (BUILD SUCCESS).

| Test | What It Verifies |
|---|---|
| `duplicateRequestId_shouldBeMarkedDuplicate` | Same requestId submitted twice → second one is DUPLICATE, only 1 DB record |
| `duplicateRequestId_shouldNotCreateDuplicateBalanceEffect` | Process, then submit duplicate → balance is only 200, not 400 |
| `outOfOrderRequests_shouldProcessInSequenceOrder` | seq=2 arrives first, then seq=1 → seq=1 processes first, then seq=2 → final balance=60 |
| `sequenceGap_shouldLeaveLaterRequestsPending` | seq=1 and seq=3 submitted, seq=2 missing → seq=3 stays RECEIVED |
| `transientFailure_TXN2005_shouldRetryAndEventuallyProcess` | TXN-2005 fails first cycle (RETRY_PENDING), succeeds second cycle (PROCESSED) |
| `retryDoesNotCreateDuplicateBalanceEffect` | TXN-2005 retries but balance is only 300, not 600 |
| `validTransaction_shouldBeProcessedWithCorrectBalance` | Basic CREDIT 500 → PROCESSED, balance=500 |
| `summary_shouldReturnCorrectStatusCounts` | After processing 2 transactions, PROCESSED count >= 2 |
| `assessmentTestData_shouldProduceExpectedOutcomes` | Full section 9.8 scenario: REQ-2-DUP=DUPLICATE, REQ-1=PROCESSED, REQ-3=PROCESSED, ACC-201 balance=85 |

---

## 10. Assessment Test Data Flow (Section 9.8)

This is the key scenario loaded on startup. Here is what happens step by step:

```
ARRIVAL ORDER         ACTION TAKEN
─────────────────────────────────────────────────────────────────
1. TXN-2002 REQ-2 seq=2  → Saved as RECEIVED (gap! seq=1 not arrived yet)
2. TXN-2001 REQ-1 seq=1  → Saved as RECEIVED
3. TXN-2002 REQ-2-DUP    → DUPLICATE (same transactionId TXN-2002 already in DB)
4. TXN-2003 REQ-3 seq=3  → Saved as RECEIVED
5. TXN-2004 REQ-4 amt=-10→ Rejected at submit (validation: amount must be > 0)
6. TXN-2005 REQ-5 seq=1  → Saved as RECEIVED (different account ACC-203)

SCHEDULER CYCLE 1:
  ACC-201: next expected = seq 1 → processes REQ-1 (CREDIT +100) ✓
           seq 2 now unblocked — but cycle 1 stops here, cycle 2 will pick it up
  ACC-203: processes TXN-2005 → TRANSIENT FAILURE → RETRY_PENDING

SCHEDULER CYCLE 2:
  ACC-201: next expected = seq 2 → processes REQ-2 (DEBIT -40) ✓
           seq 3 now unblocked
  ACC-203: retries TXN-2005 → PROCESSED (CREDIT +500) ✓

SCHEDULER CYCLE 3:
  ACC-201: next expected = seq 3 → processes REQ-3 (CREDIT +25) ✓

FINAL STATE:
  ACC-201 balance = 100 - 40 + 25 = 85 USD ✓
  REQ-2-DUP = DUPLICATE ✓
  REQ-1, REQ-2, REQ-3 = PROCESSED ✓
  REQ-5 = PROCESSED (after retry) ✓
```

---

## 11. Common Interview Questions and Answers

### Q: What happens if the application crashes while a transaction is PROCESSING?
> "Good question. In the current implementation, if the app crashes while a record is in PROCESSING state, it stays PROCESSING on restart and won't be picked up by the scheduler (which only fetches RECEIVED and RETRY_PENDING). A production fix would be to add a startup recovery step that resets PROCESSING records back to RECEIVED, or use a distributed transaction lock with a timeout."

### Q: How would you handle concurrent requests for the same account?
> "Currently, the scheduler runs in a single thread so there's no concurrent access. In production, you'd need per-account locking — for example, using `SELECT FOR UPDATE` in the database to lock an account's rows while processing, or using a distributed lock (Redis SETNX) keyed by accountId. This prevents two threads from processing the same account simultaneously."

### Q: Why use H2 instead of a real database?
> "H2 is used for simplicity in this assessment. The JPA abstraction means switching to PostgreSQL requires only changing three lines in application.properties and adding the PostgreSQL driver dependency to pom.xml. Zero code changes in the service or repository layer."

### Q: What is @Transactional and why is it used?
> "The `@Transactional` annotation wraps a method in a database transaction. If any exception is thrown, all database changes in that method are rolled back. I use it on `submit()` and `processSingle()` to ensure that if something goes wrong mid-way, the database doesn't end up in an inconsistent state — for example, the balance updated but the status not saved as PROCESSED."

### Q: How does Spring's @Scheduled work?
> "The `@Scheduled` annotation tells Spring to call the method automatically at a fixed interval. I use `fixedDelay` which waits for the method to finish before starting the next delay countdown. This prevents overlapping runs — if processing takes 10 seconds and the interval is 5 seconds, it won't start a second run while the first is still going."

### Q: What is the difference between requestId and transactionId?
> "The `transactionId` is the business identifier for the transaction itself — it represents the financial operation. The `requestId` is the idempotency key for the API call. The same transaction could theoretically be submitted via multiple API calls, each with a different requestId. In this design, we deduplicate at the requestId level — which is the right place for idempotency since it represents 'this exact API request'."

### Q: How would this scale to millions of transactions?
> "For production scale: (1) Replace H2 with a partitioned PostgreSQL or Cassandra table. (2) Use Kafka to distribute transactions across multiple processor instances — each partition handles a subset of accounts, ensuring per-account ordering without cross-instance coordination. (3) Store idempotency keys in Redis with a TTL for fast lookup. (4) Deploy multiple instances behind a load balancer with Kubernetes for auto-scaling."

---

*GitHub Repository: https://github.com/Nagaraju-Git95/idempotent-transaction-service*
