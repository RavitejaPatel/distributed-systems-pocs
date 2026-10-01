# RPC with gRPC — Synchronous Service-to-Service Communication (POC)

![Java](https://img.shields.io/badge/Java-17-orange)
![gRPC](https://img.shields.io/badge/gRPC-1.62-blue)
![Protobuf](https://img.shields.io/badge/Protobuf-3.25-blue)
![Maven](https://img.shields.io/badge/Build-Maven-blue)
![Status](https://img.shields.io/badge/Status-POC-yellow)

A hands-on proof-of-concept of **Remote Procedure Call (RPC)** communication using **gRPC**,
built around two Java microservices — `OrderService` (client) and `InventoryService` (server) —
that talk to each other the way real distributed systems do, and then deliberately break that
communication in three different ways to understand *why* RPC calls need more than just a
request and a response.

This is the **RPC leg** of a larger **Communication Paradigms** POC
(`distributed-systems-pocs/communication-paradigms/`), which also covers message queues and
event streaming using Kafka.

---

## Table of Contents

- [Objective](#objective)
- [Real-World Analogy](#real-world-analogy)
- [Architecture](#architecture)
- [The Contract: Protocol Buffers](#the-contract-protocol-buffers)
- [Prerequisites](#prerequisites)
- [Setup](#setup)
- [Project Structure](#project-structure)
- [Goal 1 — Basic RPC Call](#goal-1--basic-rpc-call)
- [Goal 2 — Race Condition Under Concurrency](#goal-2--race-condition-under-concurrency)
- [Goal 3 — Slow/Hung Dependency &amp; Deadlines](#goal-3--slowhung-dependency--deadlines)
- [Goal 4 — Lost Responses &amp; Idempotent Retries](#goal-4--lost-responses--idempotent-retries)
- [Failure Modes at a Glance](#failure-modes-at-a-glance)
- [Running Everything Yourself](#running-everything-yourself)
- [Known Limitations / Next Steps](#known-limitations--next-steps)
- [What This POC Demonstrates](#what-this-poc-demonstrates)

---

## Objective

**RPC (Remote Procedure Call)** lets one service call a method on another service running in a
different process — potentially on a different machine — and have it *feel* like a normal local
method call: you pass arguments, you block, you get a return value back. gRPC is the modern,
industry-standard implementation of this idea: it uses **Protocol Buffers** for a strongly-typed
contract and binary serialization, and **HTTP/2** as the transport.

The problem RPC solves: in a microservices architecture, `OrderService` doesn't have direct
access to the stock numbers that live inside `InventoryService` — that data is owned and
encapsulated by a different process entirely. RPC gives `OrderService` a type-safe way to ask
`InventoryService` to check and reserve stock, without either service needing to know the
other's internal implementation — only the shared `.proto` contract.

What RPC does *not* solve for free — and what this POC exists to demonstrate — is everything
that goes wrong *because* the call now crosses a network boundary instead of staying inside one
process: concurrent callers racing each other, a dependency that's slow or down, and a response
that gets lost in transit. Goals 2–4 below exist specifically to reproduce each of these and fix
them one at a time.

## Real-World Analogy

Think of placing an order on DoorDash. When you tap "Place Order," the Order app doesn't manage
restaurant inventory itself — it makes a call to the Restaurant/Inventory system and says "do you
have 1 of this item, and can you reserve it for me?" That's RPC: a direct, synchronous,
request-and-wait-for-an-answer call to another service that owns different data.

Now imagine two customers tap "order the last burger" at the same instant (Goal 2), the
restaurant's system is slow to respond because it's slammed (Goal 3), or your app's request
reaches the kitchen but the confirmation never makes it back to your phone before your app gives
up and retries (Goal 4). A production-grade ordering system has to handle all three — this POC
builds the smallest version of each problem and its fix.

## Architecture

![Architecture diagram: OrderService acts as a gRPC client calling InventoryService, a gRPC server, over HTTP/2 on localhost:50051, with both generated from a shared inventory.proto contract](images/architecture.svg)

- **`OrderService`** — the gRPC *client*. Opens a `ManagedChannel` to `InventoryService` and
  calls `checkAndReserve()` through a generated **blocking stub**.
- **`InventoryService`** — the gRPC *server*. Implements the generated
  `InventoryServiceImplBase`, holds an in-memory `stock` counter, and runs on port `50051`.
- Both services are generated from the **same `inventory.proto` file** (kept in sync across both
  modules) — this is the contract neither side can silently drift from.
- Everything in this leg runs **locally via Maven** (`mvn compile exec:java`) — no Docker
  container for `OrderService`/`InventoryService` themselves (Docker is used for the Kafka leg
  of this POC instead).

## The Contract: Protocol Buffers

```protobuf
syntax = "proto3";

package inventory;

option java_multiple_files = true;
option java_package = "com.poc.inventory.grpc";

service InventoryService {
  rpc CheckAndReserve (ReserveRequest) returns (ReserveResponse);
}

message ReserveRequest {
  string item_id = 1;
  int32 quantity = 2;
  string idempotency_key = 3;   // added in Goal 4
}

message ReserveResponse {
  bool success = 1;
  string message = 2;
  int32 remaining_stock = 3;
}
```

`protoc` (via the `protobuf-maven-plugin`) generates two kinds of Java classes from this file at
build time:

| Generated kind | Purpose | Example |
|---|---|---|
| **Message classes** | Plain data carriers, immutable, built via a builder | `ReserveRequest.newBuilder().setItemId(...).build()` |
| **Service classes** | The RPC plumbing | `InventoryServiceGrpc.InventoryServiceImplBase` (server side to extend), `InventoryServiceGrpc.InventoryServiceBlockingStub` (client side to call) |

The `.proto` file is language-agnostic — a .NET service could generate a C# client from this
exact same file and call `CheckAndReserve` (PascalCase, per .NET convention) against this same
Java server, with no code shared between the two languages at all. The contract is the only
thing that has to match.

## Prerequisites

- Java 17+
- Maven (`mvn` on PATH)

## Setup

```bash
# From the repo root
cd communication-paradigms/rpc-grpc

# Build + generate gRPC/protobuf code for both services
cd inventory-service && mvn compile && cd ..
cd order-service && mvn compile && cd ..
```

Verify codegen worked:
```bash
find inventory-service/target/generated-sources -name "*.java"
# should list ReserveRequest.java, ReserveResponse.java, InventoryServiceGrpc.java, etc.
```

## Project Structure

```
communication-paradigms/rpc-grpc/
├── proto/
│   └── inventory.proto                 # reference copy of the shared contract
├── inventory-service/
│   ├── pom.xml
│   └── src/main/
│       ├── proto/inventory.proto       # must stay in sync with order-service's copy
│       └── java/com/poc/inventory/
│           └── InventoryServiceApp.java
└── order-service/
    ├── pom.xml
    └── src/main/
        ├── proto/inventory.proto       # must stay in sync with inventory-service's copy
        └── java/com/poc/order/
            ├── OrderServiceApp.java              # Goal 1: basic client call
            ├── OrderServiceConcurrencyTest.java  # Goal 2: race condition proof
            ├── OrderServiceTimeoutDemo.java       # Goal 3: no-deadline hang
            ├── OrderServiceDeadlineDemo.java      # Goal 3: deadline fix
            └── OrderServiceRetryDemo.java          # Goal 4: idempotent retry proof
```

---

## Goal 1 — Basic RPC Call

**What it proves:** a working end-to-end RPC call — client stub, server implementation, shared
contract, serialized over the network and back.

**Run it:**
```bash
# Terminal 1
cd inventory-service && mvn exec:java -Dexec.mainClass="com.poc.inventory.InventoryServiceApp"

# Terminal 2
cd order-service && mvn exec:java -Dexec.mainClass="com.poc.order.OrderServiceApp"
```

**Expected output (OrderService):**
```
Sending request: item=item-1, qty=2
Response received:
  success: true
  message: Reserved successfully
  remainingStock: -1   (demo stock starts at 1; adjust quantity/stock for a clean run)
```

## Goal 2 — Race Condition Under Concurrency

**The problem:** `InventoryService` starts with `stock = 1`. If 20 threads call
`checkAndReserve()` at the same instant, and the check-then-decrement isn't atomic, multiple
threads can read `stock >= 1` as true *before any of them has decremented it yet* — oversubscribing
a resource that only exists once.

**The fix:** wrap the check-and-decrement in a single `synchronized` block in
`InventoryServiceApp`, so only one thread can evaluate and mutate `stock` at a time — closing the
race window entirely.

**Run it:**
```bash
# Terminal 1: restart InventoryService fresh (stock = 1)
cd inventory-service && mvn exec:java -Dexec.mainClass="com.poc.inventory.InventoryServiceApp"

# Terminal 2
cd order-service && mvn exec:java -Dexec.mainClass="com.poc.order.OrderServiceConcurrencyTest"
```

**Result:**
```
Successful reservations: 1 out of 20 (stock started at 1)
```
(Before the `synchronized` fix, with an artificial `Thread.sleep(50)` between check and decrement
to widen the race window for demonstration, this reliably printed `20 out of 20` — proving the bug.)

## Goal 3 — Slow/Hung Dependency & Deadlines

**The problem:** a well-formed RPC call is still vulnerable to a dependency that never answers.
`InventoryService` simulates this for `item-slow` with a 10-second artificial delay. Without a
client-side deadline, `OrderService` blocks for the full 10 seconds — tying up its own thread and
giving the caller no way to react, retry, or fail fast.

**The fix:** `stub.withDeadlineAfter(2, TimeUnit.SECONDS)` — gRPC cancels the call client-side
once the deadline elapses, regardless of what the server is doing.

**Run it (no deadline — hangs):**
```bash
cd order-service && mvn exec:java -Dexec.mainClass="com.poc.order.OrderServiceTimeoutDemo"
```
```
Sending request for item-slow (server will simulate a 10s stall)...
Response received after ~10000ms: Reserved successfully
```

**Run it (with a 2s deadline — fails fast):**
```bash
cd order-service && mvn exec:java -Dexec.mainClass="com.poc.order.OrderServiceDeadlineDemo"
```
```
Sending request for item-slow with a 2-second deadline (server will stall 10s)...
Call failed after 2005ms with status: DEADLINE_EXCEEDED
OrderService can now react immediately instead of hanging for 10s.
```

## Goal 4 — Lost Responses & Idempotent Retries

**The problem:** a request can succeed on the server *and still look like a failure to the
client* — the response gets lost, the connection drops, or the client's own deadline fires a
moment too early. The client's only safe-looking move is to retry. But if the original request
already succeeded, a naive retry reserves the item a second time — a classic double-charge bug.

**The fix:** the client generates a UUID `idempotency_key` once per logical order and sends it on
every attempt (original + retries). `InventoryService` keeps a `ConcurrentHashMap<String,
ReserveResponse>` keyed by that UUID: the first time a key is seen, it's processed normally and
the response is cached; every subsequent call with the *same* key returns the cached response
immediately, without touching `stock` again.

**Run it:**
```bash
# Terminal 1: restart InventoryService fresh (stock = 1)
cd inventory-service && mvn exec:java -Dexec.mainClass="com.poc.inventory.InventoryServiceApp"

# Terminal 2
cd order-service && mvn exec:java -Dexec.mainClass="com.poc.order.OrderServiceRetryDemo"
```

**Result:**
```
--- Attempt 1 (simulating the original request) ---
success=true, message=Reserved successfully, remainingStock=0

--- Attempt 2 (simulating a client retry after a lost/timed-out response) ---
success=true, message=Reserved successfully, remainingStock=0
```
Both attempts return the identical, successful result — proving the retry was deduplicated
instead of double-reserving stock. (Without the idempotency key, Attempt 2 would have returned
`success=false, message=Insufficient stock`.)

---

## Failure Modes at a Glance

![Diagram summarizing the three failure modes reproduced and fixed in this POC: race condition fixed with a synchronized block, slow dependency fixed with a gRPC deadline, and lost response fixed with an idempotency key cache](images/failure-modes.svg)

Every goal in this POC follows the same loop: **break it deliberately → prove the bug with a
reproducible test → apply the minimal fix → re-run the same test to prove the fix.** That loop is
the thing worth remembering for interviews more than any single line of code.

## Running Everything Yourself

1. Always start `InventoryService` fresh before a demo that depends on a specific starting
   `stock` value (Goals 1, 2, 4) — its state is in-memory and persists across client runs against
   the same server process.
2. If a port conflict or stale state shows up, check for leftover Java processes:
   ```bash
   # Windows (Git Bash)
   tasklist | grep -i java
   taskkill //F //IM java.exe
   ```
3. Watch the `InventoryService` terminal, not just the client's — the dedup log line
   (`Duplicate request detected for key=...`) and the incoming-request log are both printed
   server-side.

## Known Limitations / Next Steps

- **In-memory state only** — `stock` and the idempotency-key cache live inside
  `InventoryService`'s JVM and vanish on restart; a real service would back these with a
  database or distributed cache.
- **No circuit breaker (yet)** — right now a down/slow `InventoryService` means every caller
  pays the full deadline cost finding that out, every time. A circuit breaker (CLOSED → OPEN →
  HALF_OPEN) would let `OrderService` stop calling a known-bad dependency and fail instantly
  instead — a natural optional Goal 5 for this leg, either hand-rolled or via Resilience4j.
- **No TLS** — plaintext gRPC (`usePlaintext()`), fine for a local POC, not for production.
- **Single server instance** — no load balancing or service discovery considered.
- **Next legs of this POC:** Message Queues and Event Streaming, both via Kafka in Docker, under
  `communication-paradigms/queue/` and `communication-paradigms/event-streaming/` — contrasting
  this leg's synchronous, blocking RPC model against asynchronous, decoupled messaging.

## What This POC Demonstrates

- Practical gRPC usage: `.proto` contracts, generated stubs, blocking clients, server
  implementations
- The difference between a message class (data) and a service class (the RPC plumbing) in
  generated gRPC code
- Diagnosing and fixing a real concurrency race condition across a network boundary
- Deadlines as the mechanism for bounding how long a caller waits on a dependency
- Idempotency keys as the standard pattern for making retries safe in distributed systems
- The same "break it → prove it → fix it → prove the fix" methodology applied to three distinct,
  realistic failure modes
