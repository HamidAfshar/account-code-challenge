# Account Code Challenge

A Java 21 and Spring Boot application implementing the Concurrent Balance Service
coding challenge. It supports credit, debit, transfer, and balance queries through
`BalanceService`, with an optional REST API and interchangeable database and Java
collection implementations. Amounts are integral `long` values in a consistent
unit; no floating-point arithmetic or currency conversion is used.

I implemented this challenge in three configurations: H2, PostgreSQL, and an
in-memory implementation using pure Java data structures. H2 and PostgreSQL share
the JPA-backed service, while the in-memory implementation manages state and
concurrency without a database.

## Build and test

Install JDK 21 and select it through `JAVA_HOME`. The Maven wrapper is included,
so a separate Maven installation is unnecessary. The first build needs network
access to download Maven and dependencies.

```sh
./mvnw test
./mvnw package
```

Tests use H2 and Java collections; no external database or Docker daemon is
required. They cover normal operations, validation, insufficient funds, overflow,
rollback/retry behavior, duplicate requests, competing debits, simultaneous
creation, opposite-direction transfers, independent-account progress, and 1,000
concurrent mixed operations. HTTP tests exercise both storage implementations.


The `postgres` profile wiring test substitutes H2. It does not establish live
PostgreSQL compatibility; that requires running the Compose stack described below.
Mockito uses JVM instrumentation in the database service unit tests. In environments
that prohibit dynamic agent attachment, pass Mockito's locally resolved
`mockito-core` JAR through Surefire's `-DargLine=-javaagent:/path/to/mockito-core.jar`.

I used an AI coding assistant to help generate and refine test cases. I reviewed the tests, verified their assertions, and ran the suite.
## Run locally

Activate exactly one storage profile. The HTTP server listens on port 8080.

| Profile | Implementation | State lifetime |
| --- | --- | --- |
| `h2` (default) | JPA with an embedded H2 database | Application lifetime |
| `postgres` | JPA with PostgreSQL | Database-backed, subject to the schema setting below |
| `inMemory` | Java collections and per-account locks | Application lifetime |

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=h2
./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres
./mvnw spring-boot:run -Dspring-boot.run.profiles=inMemory
```

For a local JVM using PostgreSQL, start only the database service first:

```sh
docker compose up -d postgres-db
```

The local PostgreSQL configuration connects to `localhost:5432/postgres-db` with
username/password `postgres`. Spring datasource settings can be overridden through
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and
`SPRING_DATASOURCE_PASSWORD`. Only one server should use host port 8080 at a time.

To run a packaged application:

```sh
java -jar target/code-challenge-0.0.1-SNAPSHOT.jar --spring.profiles.active=inMemory
```

## Run with Docker

Install Docker with the Compose plugin and start its daemon. Run tests before
building the image: the Docker build packages the application with tests skipped.

```sh
./mvnw test
docker compose up --build -d
docker compose logs -f app
```

Compose builds a Java 21 application image, starts PostgreSQL 17, waits for its
`pg_isready` health check, then starts the application with the `postgres` profile.
The app connects to the database through the Compose hostname `postgres-db`, rather
than `localhost`. Access the API at `http://localhost:8080`. Host ports 8080 and
5432 are bound to the loopback interface. The final image runs as a non-root user
and contains a Java runtime and executable JAR; Maven and source code stay in the
build stage.

```sh
docker compose ps
docker compose down
```

The named `postgres_data` volume survives `docker compose down`. **However, the
current PostgreSQL profile uses `spring.jpa.hibernate.ddl-auto: create`, which
recreates mapped tables at each application startup. Accounts and transaction
records are therefore erased on restart despite the persistent volume.** This is
a disposable challenge configuration, not a durable deployment setup.

To use the image without PostgreSQL, build it and start one of these alternatives:

```sh
docker build -t code-challenge:local .
docker run --rm -p 127.0.0.1:8080:8080 -e SPRING_PROFILES_ACTIVE=inMemory code-challenge:local
docker run --rm -p 127.0.0.1:8080:8080 -e SPRING_PROFILES_ACTIVE=h2 code-challenge:local
```

## Architecture

- `api`: controllers, validated request DTOs, and HTTP exception mapping.
- `application`: `BalanceService`, the account-creation extension `AccountService`,
  and the two implementations. Account creation returns only the account ID.
- `domain`: account operations and invariants, transaction metadata, and exceptions.
- `infra`: Spring Data JPA repositories and the persistence helper.

`DefaultAccountService` and `EntityCreator` run under `h2` or `postgres`.
`InMemoryAccountService` runs under `inMemory`; YAML excludes datasource,
Hibernate, and JPA repository auto-configuration in that profile. The database
implementation retains operation metadata, while the collection implementation
retains successful transaction IDs only. Controllers depend on `AccountService`,
so their behavior does not depend on the selected backend.

## Concurrency and consistency

### Database implementation

Mutations run in Spring transactions at `READ_COMMITTED` isolation. Each affected
account is loaded with a pessimistic write lock, serializing conflicting updates
until commit or rollback. Balance reads are read-only transactions. Account
creation uses the account ID primary key to resolve concurrent duplicate creation.
Separate account rows can be updated independently, subject to database contention.

Transfers acquire account locks in sorted account-ID order. Consistent ordering
prevents opposite-direction transfers through this service from forming a circular
wait for account locks. External transactions using a different lock order can
still introduce database deadlocks; this application has no automatic deadlock
retry policy.

### In-memory implementation

Accounts live in a `ConcurrentHashMap`; creation uses `putIfAbsent`. Each stored
account has its own `ReentrantReadWriteLock`: reads hold the read lock, while credit,
debit, and transfer hold write locks. Balance access always occurs under these
locks. Credit and debit mutate the stored entity directly because their domain
methods validate before assigning a new balance.

A transfer acquires both write locks in sorted account-ID order and releases them
in reverse order in `finally`. It applies debit and credit to independent account
snapshots and replaces both stored entities only after both operations succeed.
Readers cannot observe an intermediate account state. Two separate balance-query
calls do not constitute a shared snapshot across multiple accounts.

No service-wide account lock is used. Unrelated accounts can progress concurrently;
short contention can still occur in the concurrent maps. Account locks are acquired
before entering the transaction-ID map's `compute` callback, so waiting for an
account does not hold a map bucket lock.

## Idempotency and failure semantics

A transaction ID identifies one financial operation globally across accounts and
operation types. Successful IDs cannot be reused, even with different parameters.
A duplicate raises `TransactionDuplicationException` (HTTP 409); retries are not
silently treated as successful responses. The guarantee is that the financial
effect is applied at most once for a successful ID.

- Database: insert the transaction record before changing accounts. The transaction
  ID primary key resolves racing requests. The record and balances commit together;
  a failure rolls them back together, leaving the ID available for retry.
- In-memory: `ConcurrentHashMap.compute` atomically checks the ID, performs the
  locked operation, and records success. A competing request with that ID is
  serialized and rejected after success. If the operation throws, no completed ID
  is added, allowing a later or waiting request to retry.

Insufficient funds, unknown accounts, and overflow leave balances unchanged. Failed
transfers never publish only one side. In-memory account lookup occurs before the
completed-ID check, so an unknown-account error can take precedence over a duplicate
ID; the database implementation reserves/checks the ID before locking accounts.

The in-memory guarantee applies to one JVM and its lifetime. Accounts and successful
IDs disappear on restart, and separate application instances do not share state.
Successful IDs have no eviction policy, so memory grows with transaction volume.
Introducing expiry would require defining a bounded deduplication window.

## Validation and API

Account and transaction IDs must be nonblank and at most 255 characters. Initial
balances must be nonnegative; transaction amounts must be positive. Unknown accounts
are rejected. Transfers to the same account are rejected before any mutation or
transaction recording: treating them as invalid avoids ambiguous financial no-ops.
Credits use checked addition to reject `long` overflow; debits reject insufficient
funds, preventing negative balances.

| Endpoint | Request | Successful response |
| --- | --- | --- |
| `POST /accounts` | `accountId`, `initAmount` | 201, plain-text account ID |
| `GET /accounts/{accountId}/balance` | None | 200, JSON `accountId` and `balance` |
| `POST /accounts/{accountId}/credit` | `amount`, `transactionId` | 204, no body |
| `POST /accounts/{accountId}/debit` | `amount`, `transactionId` | 204, no body |
| `POST /transfers` | `sourceAccountId`, `destinationAccountId`, `amount`, `transactionId` | 204, no body |

Errors use HTTP problem details: 400 for invalid input or same-account transfers,
404 for unknown accounts, and 409 for duplicate accounts/transactions, insufficient
funds, or balance overflow.

Run this sequence against a fresh application:

```sh
curl -i -X POST http://localhost:8080/accounts -H 'Content-Type: application/json' -d '{"accountId":"alice","initAmount":1000}'
curl -i -X POST http://localhost:8080/accounts -H 'Content-Type: application/json' -d '{"accountId":"bob","initAmount":500}'
curl -i -X POST http://localhost:8080/accounts/alice/credit -H 'Content-Type: application/json' -d '{"amount":500,"transactionId":"salary"}'
curl -i -X POST http://localhost:8080/accounts/alice/debit -H 'Content-Type: application/json' -d '{"amount":700,"transactionId":"rent"}'
curl -i -X POST http://localhost:8080/transfers -H 'Content-Type: application/json' -d '{"sourceAccountId":"alice","destinationAccountId":"bob","amount":300,"transactionId":"payment"}'
curl -s http://localhost:8080/accounts/alice/balance
curl -s http://localhost:8080/accounts/bob/balance
```

Creation returns `alice` and `bob` as plain text. Final balances are
`{"accountId":"alice","balance":500}` and
`{"accountId":"bob","balance":800}`. Repeating `payment` returns 409 and leaves
those balances unchanged.

## Engineering tradeoffs and further work

JPA and PostgreSQL demonstrate database-managed atomicity, row locking, and shared
storage across application instances; their cost is database setup, I/O, and
transaction management. H2 provides a self-contained development/test backend, but
passing H2 tests does not prove identical behavior on PostgreSQL. The collection
implementation removes database setup and I/O but requires explicit locking and
cannot provide durable or cross-instance guarantees. Docker makes runtime and
database setup repeatable at the cost of image builds and container tooling.

The implementation prioritizes correctness and focused concurrency tests over extra
infrastructure. With more time, the next steps would be PostgreSQL integration tests
against a real server, migrations replacing schema recreation, and an explicit
state/transaction-ID retention policy. Redis and Kafka are not needed for the
current requirements.

With more time, I would be happy to extend this solution using double-entry
accounting, recording each financial operation as balanced debit and credit ledger
entries to improve auditability and reconciliation.
