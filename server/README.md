# Speech-to-Text Web Service

A Spring Boot service that records audio in the browser, transcribes it via OpenAI's Cloud
speech-to-text API, and exposes an administration and statistics API. Built for COMP3011,
Assignment 1, Adelaide University.

---

## Table of contents

- [Architecture](#architecture)
- [Key design decisions](#key-design-decisions)
- [Getting started](#getting-started)
- [API reference](#api-reference)
- [Configuration](#configuration)
- [Testing](#testing)
- [Project structure](#project-structure)
- [Security notes](#security-notes)

---

## Architecture

```
Browser (static/) ──HTTP──▶ Controllers (web/) ──▶ Services (service/) ──▶ OpenAI API
                                    │                      │
                                    ▼                      ▼
                            Error handling (error/)   Shared state:
                                                       StatsService, ServerLifecycleService
```

The frontend is a static page with no server-rendered templating; controllers return JSON,
never HTML. Every piece of mutable state shared across concurrent requests is confined to two
services (`StatsService`, `ServerLifecycleService`); controllers themselves hold none.


| Layer | Package | Responsibility |
|---|---|---|
| Web | `web/` | REST controllers. Stateless; no business logic, no shared fields. |
| Service | `service/` | Business logic, lifecycle state, usage counters, the Cloud STT abstraction. |
| Model | `model/` | Immutable `record` DTOs matching the API's JSON contracts. |
| Error | `error/` | Application exceptions and a single `@RestControllerAdvice`. |
| Config | `config/` | Framework wiring; the OpenAI `RestClient` bean and its bound properties. |

---

## Key design decisions

### Concurrency: virtual threads, not a bigger thread pool

The dominant cost of a transcription request is waiting on the network call to OpenAI. With
`spring.threads.virtual.enabled=true`, each request runs on a virtual thread that parks during
that wait instead of occupying a platform thread, so hundreds of simultaneous blocking requests
cost almost nothing in OS threads. This meets the >200 concurrent request target with plain,
readable blocking code; no reactive rewrite, no oversized thread pool.

Verified by `ConcurrencyLoadTest`: 250 concurrent uploads against a 200ms artificial latency
complete in well under one second, versus ~50 seconds if handled sequentially.

### A `TranscriptionService` interface, with a stub behind a profile

Controllers depend on the `TranscriptionService` interface, never on a concrete provider.
`OpenAiTranscriptionService` (`@Profile("!stub")`) calls the real API and is the default — a bare
`java -jar` with no profile flag resolves to it, exactly how TITAN launches the JAR.
`StubTranscriptionService` (`@Profile("stub")`) takes over for local development and regression
testing, returning a fixed result with a deliberate artificial delay so it still exercises
blocking-on-IO behaviour under load.

This is what makes the controller and load tests possible without a real API key, network
access, or cost.

### Shared mutable state is confined to two objects, both race-tested

`StatsService` holds token counters in a `LongAdder`; `ServerLifecycleService` holds the shutdown
flag in an `AtomicBoolean` guarded by `compareAndSet`. Nothing else in the application is shared
and mutable. Both are proven correct under genuine concurrent pressure, not just inspected:

- `StatsServiceConcurrencyTest` fires 200 concurrent writers and asserts the *exact* expected
  total; a regression to a plain `long +=` would silently under-count here, not throw.
- `ServerLifecycleServiceConcurrencyTest` fires 50 simultaneous shutdown requests and asserts
  *exactly one* succeeds; a regression to check-then-set would let more than one through.

### Errors are assembled in one place

`GlobalExceptionHandler` produces the API's `ErrorResponse` shape for every failure path.
Controllers contain no try/catch. The catch-all handler returns a fixed message rather than the
exception text, because HTTP client exceptions can carry request details — including the
`Authorization` header; that must never reach a client.

---

## Getting started

### Prerequisites

- Java 25 (Temurin recommended)
- No local Maven install required — the wrapper (`mvnw` / `mvnw.cmd`) is included

### Run locally against the stub (no API key required)

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=stub
```

Open `http://localhost:8080/`, record a clip, and confirm a fixed transcript is returned.

### Run locally against the real API

```bash
export OPENAI_API_KEY=sk-...
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

### Build and run the fat JAR

```bash
./mvnw clean package
java -jar target/Assignment_1-0.0.1-SNAPSHOT.jar
```

With no profile flag, this resolves to the real `OpenAiTranscriptionService` and reads
`OPENAI_API_KEY` from the environment; exactly how TITAN invokes it.

---

## API reference

### Administration and statistics (per the supplied YAML specification)

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/admin/uptime` | Server start time, current time, and uptime in seconds. |
| `POST` | `/api/v1/admin/shutdown` | Requests graceful shutdown. `202` on success, `409` if already in progress. |
| `GET` | `/api/v1/global/stats` | Cumulative input/output token usage since server start. |

### Transcription (application-defined; not part of the YAML spec)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/transcribe` | Accepts `multipart/form-data` with a `file` field; returns `{"text": "..."}`. |

All non-2xx responses share one shape:

```json
{
  "timestamp": "2026-09-16T10:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "No audio data was supplied.",
  "path": "/api/v1/transcribe"
}
```

| Status | Cause |
|---|---|
| `400` | Empty file, missing `file` part, or request isn't `multipart/form-data` at all. |
| `409` | Shutdown requested while one is already in progress. |
| `413` | Upload exceeds `spring.servlet.multipart.max-file-size` (25MB). |
| `500` | The Cloud STT provider failed, or an unanticipated server error occurred. |

---

## Configuration

Configuration is entirely profile- and environment-driven; no commented-out code paths.

| Profile | Purpose | Transcription provider |
|---|---|---|
| *(none)* | Default; what TITAN runs | Real OpenAI client |
| `local` | Local development against the real API | Real OpenAI client |
| `stub` | Offline development, regression tests, load tests | `StubTranscriptionService` |
| `titan` | TITAN deployment (logging tuned for production) | Real OpenAI client |

| Property | Purpose |
|---|---|
| `OPENAI_API_KEY` *(env var)* | Bearer token for the Cloud STT provider. Read at runtime only, never logged, persisted, or returned in a response. |
| `spring.threads.virtual.enabled` | Enables virtual threads for request handling. |
| `stt.stub.latency-millis` | Artificial delay in the stub, so load tests exercise real blocking behaviour. |

---

## Testing

17 tests across 8 classes, all passing.

| Test class | What it proves |
|---|---|
| `StubTranscriptionServiceTest` | Profile-based wiring resolves correctly; stub output is deterministic. |
| `OpenAiTranscriptionServiceTest` | Real client sends the correct request shape (auth header, multipart body) and parses OpenAI's response; verified with `MockRestServiceServer`, no real key or network call. |
| `AudioControllerTest` | Full HTTP-level regression tests against the stub: happy path with stats verification, empty file, missing part, and non-multipart requests. |
| `StatsServiceConcurrencyTest` | 200 concurrent writers produce an exact total; the counter is race-safe under contention, not just correct single-threaded. |
| `ServerLifecycleServiceConcurrencyTest` | 50 simultaneous shutdown requests: exactly one succeeds. Verifies the `compareAndSet` race guard directly, without ever reaching a real process exit. |
| `ConcurrencyLoadTest` | 250 concurrent uploads complete in under 1 second against a 200ms artificial latency — proof of genuine concurrent handling, not sequential processing. |

```bash
./mvnw test
```

**A note on test design:** `ServerLifecycleService.performExit()` is a small `protected` seam
introduced specifically so the shutdown race condition could be tested safely. The real
implementation calls `System.exit()`; without this seam, a passing test would schedule a real JVM
exit roughly 500ms later, silently killing the test runner itself, well after the assertion had
passed. The concurrency test overrides this one method to a no-op.

---

## Project structure

```
src/main/java/comp3011/assignment1/
├── Assignment1Application.java
├── config/          # RestClient bean, OpenAI properties binding
├── error/           # Exceptions + the central @RestControllerAdvice
├── model/            # Immutable record DTOs
├── service/          # Business logic, lifecycle state, usage counters
└── web/               # REST controllers

src/main/resources/
├── static/            # Frontend: index.html, styles.css, app.js
├── application.properties
└── application-{local,stub,titan}.properties

src/test/java/comp3011/assignment1/
├── service/           # Unit + concurrency tests
└── web/               # HTTP-level regression + load tests
```

---

## Security notes

- The OpenAI API key is read once from `OPENAI_API_KEY` at startup and held in a `final` field.
  It is never written to a properties file, never committed, never logged, and never included in
  any response body, including error bodies from `GlobalExceptionHandler`.
- `GlobalExceptionHandler`'s catch-all returns a fixed message rather than exception text, since
  upstream HTTP client exceptions can carry request details.
- `management.endpoints.web.exposure.include=health` limits the actuator surface to prevent
  configuration (and the key) from being exposed via `/actuator/env`.