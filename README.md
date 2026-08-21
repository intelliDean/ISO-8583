# ISO 8583: Enterprise Payment Protocol Engine & Host Simulator

[![Java](https://img.shields.io/badge/Java-21%2B%20%2F%2026-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1.0-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?style=for-the-badge&logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7-DC382D?style=for-the-badge&logo=redis&logoColor=white)](https://redis.io/)
[![Apache Kafka](https://img.shields.io/badge/Apache_Kafka-3.6-231F20?style=for-the-badge&logo=apachekafka&logoColor=white)](https://kafka.apache.org/)
[![Prometheus](https://img.shields.io/badge/Prometheus-Micrometer-E6522C?style=for-the-badge&logo=prometheus&logoColor=white)](https://prometheus.io/)
[![Tests](https://img.shields.io/badge/Tests-83%20Passing%20(100%25)-success?style=for-the-badge)](https://github.com/intelliDean/ISO-8583)

A high-performance, enterprise-grade financial transaction engine and payment switch simulator implementing the **ISO 8583** international standard. Built with **Java 26 Virtual Threads**, featuring dual-interface operation (raw binary TCP and HTTP REST), TLS 1.3 / Mutual TLS (mTLS), EMV BER-TLV chip decoding, PIN cryptography (ISO 9564 Formats 0, 1, 3, 4), ANSI X9.24 DUKPT key tree derivation, ISO 9797-1 Retail MAC, Dual-Message System (DMS) clearing & settlement, distributed persistence, Prometheus operational metrics, and a real-time Web Control Center.

---

## 🏛️ System Architecture

```
                                      💳 Client / Terminal POS / Switch
                                                    │
                     ┌──────────────────────────────┴──────────────────────────────┐
                     │                                                             │
                     ▼                                                             ▼
         Raw Binary TCP Socket (8583)                                    HTTP / REST API (8080)
       ┌───────────────────────────────┐                             ┌───────────────────────────────┐
       │ 2-Byte Big-Endian Length Pref │                             │ Web Control Center & Swagger  │
       │ 10-Char TPDU Transport Header │                             │ Management, Telemetry, Crypto │
       │ Virtual Thread per Connection │                             │ Actuator & Prometheus Scraper │
       │ TLS 1.3 / mTLS Client Auth    │                             └──────────────┬────────────────┘
       └──────────────┬────────────────┘                                            │
                      │                                                             │
                      └──────────────────────────────┬──────────────────────────────┘
                                                     │
                                                     ▼
    ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
    │                                   Core Processing Pipeline                                      │
    ├────────────────────────────────────────────────┬────────────────────────────────────────────────┤
    │ 📦 Message Packing & Unpacking Engine          │ 💡 EMV BER-TLV Chip Data Parser (DE 55)        │
    │   • ISO 8583:1987, Visa SMS, Mastercard IPM    │   • ARQC (9F26), ATC (9F36), IAD (9F10)        │
    │   • 128-Bit Dynamic Bitmask Matrix Parser      │   • Fraud Signal Extraction & Audit Log        │
    ├────────────────────────────────────────────────┼────────────────────────────────────────────────┤
    │ 🔐 Cryptography & PIN Translation              │ 🏦 Batch Clearing & Settlement                 │
    │   • ISO 9564 PIN Blocks (Formats 0, 1, 3, 4)   │   • 1240 First Presentment Batch Generator     │
    │   • ANSI X9.24 DUKPT Engine (BDK/IPEK/PEK/MAK) │   • 1440 Chargeback Dispute Filing             │
    │   • ISO 9797-1 Retail MAC (Algorithm 3, DE 64) │   • 1644 File Header/Trailer Reconciliation    │
    │   • Cross-Zone PIN Translation (Acq -> Iss)    │   • Scheme Interchange Fee Calculator          │
    ├────────────────────────────────────────────────┼────────────────────────────────────────────────┤
    │ 💾 Distributed Persistence & Resiliency        │ 📡 Network Management & Observability          │
    │   • PostgreSQL 16 Schema V1.0 (Flyway)         │   • 0800 / 0810 Keep-Alive Echo Scheduler      │
    │   • Redis 7 Distributed State Locks (TTL safe) │   • Channel Health Telemetry (RTT Latency)     │
    │   • Kafka Transactional Outbox Event Streaming │   • Prometheus / Micrometer Metrics Exporter   │
    └────────────────────────────────────────────────┴────────────────────────────────────────────────┘
```

---

## 🌟 Key Features

### 1. Dual Interface: Raw Virtual-Threaded TCP Socket & REST API
- **TCP Host Simulator (`Port 8583`)**:
  - Powered by **Java 26 Virtual Threads** (`Executors.newVirtualThreadPerTaskExecutor()`) for lightweight, high-concurrency connection handling.
  - Listens for raw TCP packets framed with 2-byte big-endian length headers and 10-character TPDU headers (`6000000000`).
  - Synchronously processes and responds to financial requests (`0200` $\to$ `0210`), reversals (`0400`/`0420` $\to$ `0410`/`0430`), and network management messages (`0800` $\to$ `0810`).
  - **TLS 1.3 / 1.2 & Mutual TLS (mTLS)**: Configurable keystore/truststore support with flexible client authentication modes (`NONE`, `WANT`, `NEED`).
- **REST Management API (`Port 8080`)**: Exposes structured JSON endpoints for message parsing, dynamic packing, EMV decoding, cryptography lab, settlement batches, and resiliency telemetry.

### 2. Multi-Network Packager Dialects
- **ISO 8583:1987 Standard**: Global standard format for ATM/POS financial transaction switching.
- **Visa SMS (Single Message System)**: Custom field lengths, bitmap overrides, and format rules for Visa acquiring.
- **Mastercard IPM (Integrated Processing Mode)**: Specialized dialect for Mastercard presentment and clearing.

### 3. EMV BER-TLV Chip Card Engine (DE 55)
- Recursively parses ISO/IEC 8825-1 (BER-TLV) data elements from EMV contact and contactless transactions.
- Automated extraction of top-level fraud detection telemetry:
  - **ARQC (Tag 9F26)**: Application Request Cryptogram for issuer cryptographic verification.
  - **ATC (Tag 9F36)**: Application Transaction Counter (tracks card counter increments to detect replay attacks).
  - **IAD (Tag 9F10)**: Issuer Application Data.

### 4. Enterprise Cryptography & PIN Translation
- **ISO 9564 PIN Block Engine**:
  - **Format 0 (ANSI X9.8)**: PAN-XOR block format.
  - **Format 1 (ISO-1)**: Transaction-independent format with random padding.
  - **Format 3 (ISO-3)**: High-entropy random fill + PAN XOR.
  - **Format 4 (ISO-4)**: 16-byte AES-128 cryptographic block.
- **Cross-Zone PIN Block Translation**: Translates encrypted PIN blocks from Acquirer key zone ($ZPK_{acq}$) to Issuer key zone ($ZPK_{iss}$) and converts formats without cleartext exposure in memory.
- **ANSI X9.24 DUKPT Engine**:
  - Full Derived Unique Key Per Transaction implementation with 10-byte KSN parsing (Key Set ID, Device ID, Transaction Counter).
  - Two-key 3DES IPEK derivation from Base Derivation Key (BDK).
  - Future key register stepping and non-reversible derivation of current **Transaction Key**, **PEK** (PIN Encryption Key), **MAK** (Message Authentication Key), and **DEK** (Data Encryption Key).
  - Live DUKPT PIN decryption endpoint.
- **ISO 9797-1 Retail MAC**: Double-length 3DES CBC-MAC (Algorithm 3) over DE 64/DE 128 with tamper detection.

### 5. Dual-Message System (DMS) Batch Clearing & Settlement
- **1240 First Presentment**: Aggregates authorized transactions into settlement clearing batches.
- **1440 Chargeback Management**: Files issuer dispute records with scheme reason codes (`4837` Fraud, `4853` Defective Goods).
- **1644 Control Totals**: Generates batch headers and trailers with gross presentment amounts, interchange fees, and net settlement reconciliation.
- **Interchange Fee Calculator**: Precision-safe calculation based on scheme assessment rules (1.5% + $0.10).

### 6. Observability, Metrics & Telemetry
- **Prometheus / Micrometer Integration**: Real-time metric export available at `/actuator/prometheus`.
  - `iso.transactions.total`: Total message volume partitioned by MTI, response code, network, and status.
  - `iso.transaction.duration`: End-to-end processing latency histograms with percentile publishing.
  - `iso.crypto.operations.total`: Telemetry across PIN encoding, translation, MAC generation, and DUKPT derivations.
  - `iso.clearing.batches.total` & `iso.clearing.records.total`: Clearing batch and record volume tracking.
  - `iso.echo.heartbeats.total` & `iso.echo.duration`: Keep-alive heartbeat success rates and round-trip times.
  - `iso.transactions.active.count` & `iso.outbox.pending.count`: Gauge monitors for state store size and outbox backlog.

### 7. Distributed Persistence & Resiliency
- **PostgreSQL 16 Schema (Flyway V1.0)**: Indexed tables for transactions, clearing batches, outbox events, and crypto key registries.
- **Redis 7 Distributed Locking**: Coordinates cluster mutations on `STAN:MaskedPAN` to eliminate race conditions and double-reversals.
- **Transactional Outbox Event Streaming**: Guarantees at-least-once publishing of payment domain events to Apache Kafka topics (`iso.transactions.v1`, `iso.clearing.v1`, `iso.security.v1`).
- **Automated Echo Scheduler**: Background scheduler dispatching `0800` Echo Tests with automatic channel state transitions (`HEALTHY`, `DEGRADED`, `DOWN`).

### 8. Interactive 8-Tab Web Control Center
Accessible at `http://localhost:8080`:
1. **🔍 Message Inspector**: Unpack any ISO 8583 payload with interactive field tables, bitmap inspection, and preset loaders.
2. **⚡ Message Builder & TCP Simulator**: Compose custom messages and transmit live over binary TCP to the host simulator.
3. **🔲 128-Bit Bitmap Explorer**: Clickable matrix with live bit-toggling and synchronized primary/secondary bitmap computation.
4. **💡 EMV BER-TLV Explorer**: Interactive tree viewer for DE 55 ICC chip streams with ARQC/ATC extraction.
5. **🔐 Cryptography Lab**: PIN block encoder, cross-zone translator, Retail MAC calculator/verifier, and DUKPT derivation workspace.
6. **🏦 Clearing & Settlement Dashboard**: Batch generator, chargeback filing, and live transaction ledger.
7. **📡 Network Telemetry & Resiliency**: Echo health monitoring (RTT), Kafka outbox queue depth, and cluster status.
8. **📚 ISO 8583 Masterclass**: Comprehensive reference guide on specifications, bitmasks, DUKPT, MAC algorithms, and clearing flows.

---

## 🚀 Quick Start

### Prerequisites
- **JDK 21 or later** (Java 26 supported)
- **Maven 3.9+** (or use included `./mvnw`)
- **Docker & Docker Compose** (optional, for PostgreSQL, Redis, Kafka infrastructure)
- **Python 3.9+** (for automated E2E client testing)

---

### 1. Build & Run the Application

```bash
# Clone the repository
git clone https://github.com/intelliDean/ISO-8583.git
cd ISO-8583

# Build and execute all 83 unit/integration tests
./mvnw clean test

# Launch the Payment Engine & TCP Simulator
./mvnw spring-boot:run
```

Once started:
- 🌐 **Web Control Center**: [`http://localhost:8080`](http://localhost:8080)
- 📄 **ISO 8583 TCP Host**: `localhost:8583`
- 📊 **Prometheus Metrics**: [`http://localhost:8080/actuator/prometheus`](http://localhost:8080/actuator/prometheus)

---

### 2. Run the Automated E2E Protocol Test Suite

Run the full end-to-end automated test suite that exercises all 7 API/engine tabs:

```bash
python3 client/test_browser_suite.py
```

```
================================================================================
🚀 AUTOMATED E2E BROWSER & PROTOCOL TEST SUITE FOR ISO 8583 ENGINE
   Target: http://localhost:8080
================================================================================
  ✓ PASS  Tab 1: Message Inspector                      (364 ms)
  ✓ PASS  Tab 2: Message Builder + TCP Sim              (108 ms)
  ✓ PASS  Tab 3: Spec Registry                          (37 ms)
  ✓ PASS  Tab 4: EMV BER-TLV Parser                     (19 ms)
  ✓ PASS  Tab 5: Crypto Lab                             (127 ms)
  ✓ PASS  Tab 6: Clearing & Settlement                  (50 ms)
  ✓ PASS  Tab 7: Network Telemetry                      (102 ms)
--------------------------------------------------------------------------------
  7/7 tabs passed (100% OPERATIONAL & HEALTHY)
================================================================================
```

---

### 3. Run the Standalone Java Terminal Client

In a separate terminal, test the raw binary TCP socket:

```bash
# Run the included Java terminal client
./mvnw exec:java -Dexec.mainClass="com.dean.iso8583.client.IsoTerminalClient"

# Or run the Python terminal client
python3 client/iso_terminal_client.py
```

---

### 4. Launch Docker Infrastructure (PostgreSQL, Redis, Kafka)

To start the full distributed stack:

```bash
docker compose up -d
```

| Service | Port | Description |
|---|---|---|
| **PostgreSQL 16** | `5432` | Relational Ledger & Outbox Store (`iso8583_db`) |
| **Redis 7** | `6379` | Cluster-wide Distributed State Locking |
| **Apache Kafka** | `9092` | Event Streaming Broker (KRaft mode) |
| **Kafdrop** | `9000` | Web UI for Kafka Topics & Stream Inspection |

---

## 📡 REST API Reference

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/iso/spec` | Returns the master dictionary of all ISO 8583 Data Elements (DE 1–128) |
| `GET` | `/api/iso/specs` | Returns all registered specification dialects (`iso8583-1987`, `mastercard-ipm`, `visa-sms`) |
| `POST` | `/api/iso/unpack` | Disassembles an ISO 8583 payload into structured Data Elements |
| `POST` | `/api/iso/pack` | Packs Data Elements into a formatted ISO 8583 payload string |
| `POST` | `/api/iso/simulate` | Sends a raw payload over the local TCP socket to port 8583 and returns the response |
| `POST` | `/api/iso/emv/parse` | Decodes a DE 55 hex string into BER-TLV tag triplets with ARQC/ATC extraction |
| `GET` | `/api/iso/transactions` | Returns the live transaction state ledger |
| `POST` | `/api/iso/echo/trigger` | Fires an on-demand `0800` Echo test and returns RTT latency |
| `GET` | `/api/iso/echo/status` | Returns channel health status (`HEALTHY`, `DEGRADED`, `DOWN`) and heartbeat metrics |
| `POST` | `/api/iso/crypto/pin/encode` | Encodes and encrypts a PIN into an ISO 9564 PIN block (Formats 0, 1, 3, 4) |
| `POST` | `/api/iso/crypto/pin/translate` | Translates an encrypted PIN block across key zones / formats |
| `POST` | `/api/iso/crypto/mac/generate` | Computes an ISO 9797-1 Retail MAC for an ISO 8583 message |
| `POST` | `/api/iso/crypto/mac/verify` | Verifies message integrity against an expected DE 64 MAC |
| `POST` | `/api/iso/crypto/dukpt/derive-ipek` | Derives the Initial PIN Encryption Key (IPEK) from BDK and KSN |
| `POST` | `/api/iso/crypto/dukpt/derive-key` | Derives current Transaction Key, PEK, MAK, and DEK variants |
| `POST` | `/api/iso/crypto/dukpt/encrypt-pin` | Encrypts a PIN block using the terminal's derived DUKPT PEK key |
| `POST` | `/api/iso/crypto/dukpt/decrypt-pin` | Decrypts a DUKPT-encrypted PIN block using BDK and KSN |
| `POST` | `/api/iso/clearing/batch/generate` | Generates a 1240 First Presentment batch with 1644 control totals and fee calculations |
| `POST` | `/api/iso/clearing/batch/parse` | Parses a raw clearing batch file string into structured records |
| `POST` | `/api/iso/clearing/chargeback` | Files a 1440 Chargeback dispute record |
| `GET` | `/api/iso/clearing/batches` | Lists all generated clearing batches |
| `GET` | `/api/iso/clearing/chargebacks` | Lists all filed chargeback dispute records |
| `GET` | `/api/iso/resiliency/status` | Returns PostgreSQL, Redis lock, and Kafka Outbox telemetry |
| `GET` | `/actuator/prometheus` | Exposes Prometheus operational metrics for scraping |

---

## 🧪 Testing & Verification

The project contains **83 automated unit and integration tests** ensuring 100% build health:

```bash
./mvnw test
```

### Key Test Suites:
- **`IsoEngineTest`**: ISO 8583 packing, bitmap generation, and unpacking.
- **`IsoPinBlockEngineTest`**: PIN Block Formats 0/1/3/4 encoding, decoding, encryption, and cross-zone translation.
- **`DukptEngineTest`**: ANSI X9.24 DUKPT IPEK derivation, transaction counter stepping, PEK/MAK/DEK variant generation, and live PIN block decryption.
- **`MacEngineTest`**: ISO 9797-1 Algorithm 3 Retail MAC calculation and tamper detection.
- **`EmvTlvParserTest`**: Recursive BER-TLV parsing and ARQC/ATC fraud signal detection.
- **`BatchClearingEngineTest`**: 1240 presentment batch creation, interchange fee calculations, 1440 chargebacks, and 1644 file control totals.
- **`IsoEchoManagerTest`**: 0800 keep-alive heartbeat dispatch, latency tracking, and channel health degradation.
- **`DistributedLockTest`**: Redis and in-memory distributed locking contention, lease expiry, and concurrency.
- **`OutboxEventStreamingTest`**: Transactional outbox event recording and background poller streaming.
- **`ReversalEngineTest`**: 0200 auth recording, 0400 synchronous reversal matching, and partial reversals (DE 95).
- **`IsoTlsContextFactoryTest`**: TLS 1.3 / 1.2 and Mutual TLS (mTLS) socket security and certificate validation.

---

## 📂 Project Structure

```
iso_8583/
├── client/
│   ├── iso_terminal_client.py   # Python interactive ISO 8583 TCP terminal client
│   └── test_browser_suite.py    # Automated E2E protocol test suite
├── src/main/java/com/dean/iso8583/
│   ├── client/                  # Java terminal client for TCP socket testing
│   ├── core/
│   │   ├── clearing/            # Batch clearing, 1240 presentment, interchange calculator
│   │   ├── crypto/              # PIN Block (ISO 9564), DUKPT (X9.24), Retail MAC (ISO 9797-1)
│   │   ├── dto/                 # ISO Message, Field Definition, Spec DTOs
│   │   ├── echo/                # 0800/0810 Keep-alive network management & telemetry
│   │   ├── emv/                 # BER-TLV chip data parser (DE 55)
│   │   ├── event/               # Transactional Outbox pattern & Kafka stream poller
│   │   ├── lock/                # Distributed Lock Service (Redis / In-memory)
│   │   ├── metrics/             # Micrometer & Prometheus operational metrics engine
│   │   ├── persistence/         # Transaction & Clearing Batch repositories
│   │   ├── reversal/            # 0400 reversal engine & transaction state store
│   │   ├── spec/                # Multi-dialect specification registry
│   │   └── utils/               # Sanitizers, masking, hex/byte utilities
│   ├── server/                  # TCP socket server with Java 26 Virtual Threads
│   │   └── tls/                 # TLS / mTLS configuration and SSLContext factory
│   └── web/                     # Spring Boot REST Controller, DTOs & Service
├── src/main/resources/
│   ├── db/migration/            # Flyway PostgreSQL V1 schema migrations
│   ├── specs/                   # ISO 8583 dialect JSON definitions
│   └── static/                  # 8-Tab Web Dashboard (HTML5, Vanilla CSS, JS)
├── docker-compose.yml           # PostgreSQL 16, Redis 7, Kafka KRaft, Kafdrop
└── pom.xml
```

---

## 📜 License

This project is licensed under the [MIT License](LICENSE).
