# 🎫 Distributed Ticketing System

A Spring Boot backend demonstrating **distributed locking** and **concurrency-safe design** for managing support tickets in a multi-instance environment.

This README covers setup instructions, the implemented locking strategy, a sample concurrency test case, and notes on AI tool usage.

---

## Assumptions and considerations

- there was no business logic outlined other than the locking, so there are some placeholder logic injected
- configuration is really bare bones, for actual production apps I would add more customization
- due to the size of this app, I went with plain Java instead of using industry standard libs (e.g Lombok).
- there are a lot of ways to achieve complete isolated sections
    - this time, I chose distributed locking with some optimistic locking on the database level. This ensures integrity and correctness even during peaks, while performance is not hit too much.
    - the other option is combining it with pessimistic locking, however in this case I feel like that's an overkill, as reads are much more frequent in this system.

---

## 🚀 Setup and Run Instructions

### Prerequisites

* Java 17+
* Docker & Docker Compose

### Starting Locally 
#### Start Redis

```bash
docker compose --file docker-compose.dev.yml up -d
docker ps  # Verify Redis is running on port 6379
```

#### Run the Application

```bash
./gradlew bootRun
```

### Run using docker compose

From the repository root, run:
```
docker-compose up
```

This should build an image for the app and start it up alongside Redis.

Application runs at [http://localhost:8080](http://localhost:8080)

### Run Tests

Integration test simulating concurrent updates:

```bash
./gradlew test --tests TicketServiceConcurrencyTest.testConcurrentAssignment_OnlyOneSucceeds
```

---

## 🏗️ Locking Strategy

| Operation              | Locking Mechanism       | Purpose                                                    | Notes                                                 |
| ---------------------- | ----------------------- | ---------------------------------------------------------- | ----------------------------------------------------- |
| **Create Ticket**      | None                    | New entities have no collision risk                        | No locking needed                                     |
| **Update Description** | Optimistic (`@Version`) | Handle high concurrency on simple field updates            | Demonstrates optimistic concurrency control           |
| **Assign Ticket**      | **Distributed Lock**    | Ensure only one agent claims a ticket                      | Critical business rule; prevents multiple assignments |
| **Update Status**      | **Distributed Lock**    | Prevent conflicts during complex validation & side effects | Ensures data integrity                                |


## 🧪 Sample Concurrent Update Test Case

**Scenario:** Multiple agents attempt to assign the same ticket simultaneously.

**Steps:**

1. Create a single ticket.
2. Spawn 10 threads representing agents attempting assignment.
3. Verify that only 1 agent succeeds; others fail with `AlreadyAssignedException` or lock acquisition failures.

**Expected Output:**

```
✓ agent-3 successfully assigned ticket
✗ agent-0 - ticket already assigned
✗ agent-1 - ticket already assigned
✗ agent-2 - failed to acquire lock
...
```

**Outcome:** Only one assignment persists, demonstrating proper concurrency control.

---

## Test script

```
bash assignment-test.sh
```
It creates a ticket, fires requests against it, you see the outcome on the console.

---


## 🤖 AI Tool Usage

This README and supporting code documentation were assisted by AI for clarity and formatting. 
Production code implementation was assisted, and test cases were fully implemented by AI. Architectural decisions were not made by AI.

No AI-generated code was blindly copied; all examples were validated and tested.


---

## 🧰 Technology Stack

| Component | Technology               | Purpose                  |
| --------- |--------------------------| ------------------------ |
| Framework | Spring Boot              | Core application         |
| Database  | H2 (in-memory)           | JPA persistence          |
| Locking   | Redis + Redisson         | Distributed coordination |
| Testing   | JUnit 5 + Testcontainers | Integration tests        |
| Build     | Gradle                   | Dependency management    |
