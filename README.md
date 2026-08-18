# ISO 8583 Enterprise Payment Protocol Engine & Host Simulator

[![Java](https://img.shields.io/badge/Java-21%2B%20%2F%2026-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1.0-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?style=for-the-badge&logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7-DC382D?style=for-the-badge&logo=redis&logoColor=white)](https://redis.io/)
[![Apache Kafka](https://img.shields.io/badge/Apache_Kafka-3.6-231F20?style=for-the-badge&logo=apachekafka&logoColor=white)](https://kafka.apache.org/)
[![Tests](https://img.shields.io/badge/Tests-75%20Passing%20(100%25)-success?style=for-the-badge)](https://github.com/intelliDean/ISO-8583)

A high-performance, enterprise-grade financial transaction engine and payment switch simulator implementing the **ISO 8583** international standard. Includes dual-protocol operation (raw binary TCP and HTTP REST), EMV BER-TLV chip decoding, PIN cryptography, Retail MAC, Dual-Message System (DMS) batch clearing & settlement, distributed persistence, and a real-time 8-tab Web Control Center.

---

## 🏛️ System Architecture

```
                                      💳 Client / Terminal POS
                                                │
                 ┌──────────────────────────────┴──────────────────────────────┐
                 │                                                             │
                 ▼                                                             ▼
     Raw Binary TCP Socket (8583)                                   HTTP / REST API (8080)
   ┌───────────────────────────────┐                             ┌───────────────────────────────┐
   │ 2-Byte Big-Endian Length Pref │                             │ Web Control Center & Swagger  │
   │ 10-Char TPDU Transport Header │                             │ Management, Telemetry, Crypto │
   └──────────────┬────────────────┘                             └──────────────┬────────────────┘
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
│ 🔐 Cryptography & PIN Translation (Option A)   │ 🏦 Batch Clearing & Settlement (Option B)      │
│   • ISO 9564 PIN Blocks (Formats 0, 1, 3, 4)   │   • 1240 First Presentment Batch Generator     │
│   • ANSI X9.24 DUKPT Key Management (BDK/IPEK) │   • 1440 Chargeback Dispute Filing             │
│   • ISO 9797-1 Retail MAC (DE 64 / DE 128)     │   • 1644 File Header/Trailer Reconciliation    │
│   • Cross-Zone PIN Translation (Acq -> Iss)    │   • Scheme Interchange Fee Calculator          │
├────────────────────────────────────────────────┼────────────────────────────────────────────────┤
│ 💾 Distributed Persistence (Option C)          │ 📡 Network Management (Echo / Keep-Alive)      │
│   • PostgreSQL 16 Schema V1.0 (Flyway)         │   • 0800 / 0810 Heartbeat Automation           │
│   • Redis 7 Distributed State Locks (TTL safe) │   • Channel Health Telemetry (RTT latency)     │
│   • Kafka Transactional Outbox Event Streaming │   • Auto-Recovery on Socket Interruption       │
└────────────────────────────────────────────────┴────────────────────────────────────────────────┘
```

---

## 🌟 Key Features

### 1. Dual Interface: Raw TCP Socket & Web Management
- **TCP Host Simulator (`Port 8583`)**: Listens for raw TCP packets with 2-byte big-endian framing and 10-character TPDU headers (`6000000000`), decoding MTI requests (`0200`, `0400`, `0800`) and responding synchronously with approvals (`0210`, `0410`, `0810`).
- **REST Management API (`Port 8080`)**: Exposes microservices for packing, unpacking, EMV chip decoding, PIN cryptography, batch clearing, and telemetry.

### 2. Multi-Network Packager Dialects
- **ISO 8583:1987 Standard**: Global standard format for ATM/POS financial switches.
- **Visa SMS (Single Message System)**: Custom field lengths and format definitions for Visa acquiring.
- **Mastercard IPM (Integrated Processing Mode)**: Dialect for Mastercard presentment and clearing.

### 3. EMV BER-TLV Chip Card Engine (DE 55)
- Recursively parses ISO/IEC 8825-1 (BER-TLV) data elements from EMV contact and contactless transactions.
- Automated extraction of top-level fraud detection telemetry:
  - **ARQC (Tag 9F26)**: Application Request Cryptogram.
  - **ATC (Tag 9F36)**: Application Transaction Counter (tracks card counter increments).
  - **IAD (Tag 9F10)**: Issuer Application Data.

### 4. Enterprise Cryptography & PIN Translation (Option A)
- **ISO 9564 PIN Block Engine**:
  - **Format 0 (ANSI X9.8)**: PAN-XOR block format.
  - **Format 1 (ISO-1)**: Transaction-independent format with random padding.
  - **Format 3 (ISO-3)**: High-entropy random fill + PAN XOR.
  - **Format 4 (ISO-4)**: 16-byte AES-128 cryptographic block.
- **Cross-Zone PIN Block Translation**: Translates encrypted PIN blocks from Acquirer key zone ($ZPK_{acq}$) to Issuer key zone ($ZPK_{iss}$) and converts formats without cleartext exposure.
- **ANSI X9.24 DUKPT Engine**: Derived Unique Key Per Transaction with 10-byte KSN parsing, IPEK derivation, and non-reversible future key registers.
- **ISO 9797-1 Retail MAC**: Double-length 3DES CBC-MAC (Algorithm 3) over DE 64/DE 128 with tamper detection.

### 5. Dual-Message System (DMS) Batch Clearing & Settlement (Option B)
- **1240 First Presentment**: Aggregates authorized transactions into settlement clearing batches.
- **1440 Chargeback Management**: Files issuer dispute records with scheme reason codes (`4837` Fraud, `4853` Defective Goods).
- **1644 Control Totals**: Generates batch headers and trailers with gross, interchange fee, and net settlement reconciliation.
- **Interchange Fee Calculator**: Precision-safe `BigDecimal` calculation (1.5% + $0.10 scheme assessment).

### 6. Distributed Persistence & Resiliency (Option C)
- **PostgreSQL 16 Schema (Flyway V1.0)**: Indexed tables for transactions, clearing batches, outbox events, and crypto key registries.
- **Redis 7 Distributed Locking**: Coordinates cluster mutations on `STAN:MaskedPAN` to eliminate race conditions and double-reversals.
- **Transactional Outbox Event Streaming**: Guarantees at-least-once publishing of payment domain events to Apache Kafka topics (`iso.transactions.v1`, `iso.clearing.v1`, `iso.security.v1`).

### 7. Interactive 8-Tab Web Control Center (Option D)
Accessible at `http://localhost:8080`:
1. **🔍 Message Inspector**: Unpack any payload with interactive field tables and summary cards.
2. **⚡ Message Builder & TCP Simulator**: Compose and fire live messages to the TCP Host.
3. **🔲 128-Bit Bitmap Explorer**: Clickable matrix with live bit-toggling and synchronized hex computation.
4. **💡 EMV BER-TLV Explorer**: Interactive tree viewer for DE 55 chip streams.
5. **🔐 Cryptography Lab**: PIN block encoder, translator, and Retail MAC verifier.
6. **🏦 Clearing & Settlement Dashboard**: Batch generator, chargeback filing, and live transaction ledger.
7. **📡 Network Telemetry & Resiliency**: Echo health monitoring (RTT), outbox stream queue, and cluster status.
8. **📚 ISO 8583 Masterclass**: Comprehensive reference guide on specifications, DUKPT, MAC, and clearing.

---

## 🚀 Quick Start

### Prerequisites
- **JDK 21 or later** (Java 26 supported)
- **Maven 3.9+** (or use included `./mvnw`)
- **Docker & Docker Compose** (optional, for PostgreSQL, Redis, Kafka infrastructure)

---

### 1. Clone & Run the Application

```bash
# Clone the repository
git clone https://github.com/intelliDean/ISO-8583.git
cd ISO-8583

# Build and run all unit/integration tests
./mvnw clean test

# Launch the Payment Engine & TCP Simulator
./mvnw spring-boot:run
```

Once started:
- 🌐 **Web Control Center**: [`http://localhost:8080`](http://localhost:8080)
- 📄 **ISO 8583 TCP Host**: `localhost:8583`

---

### 2. Run the Standalone Terminal Client

In a separate terminal, test the raw binary TCP socket:

```bash
# Run the included Java terminal client
./mvnw exec:java -Dexec.mainClass="com.dean.iso8583.client.IsoTerminalClient"
```

---

### 3. Launch Docker Infrastructure (PostgreSQL, Redis, Kafka)

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
| `POST` | `/api/iso/unpack` | Disassembles an ISO 8583 payload into structured Data Elements |
| `POST` | `/api/iso/pack` | Packs Data Elements into a formatted ISO 8583 payload string |
| `POST` | `/api/iso/simulate` | Sends a raw payload over the local TCP socket to port 8583 |
| `POST` | `/api/iso/emv/parse` | Decodes a DE 55 hex string into BER-TLV tag triplets |
| `GET` | `/api/iso/transactions` | Returns the live transaction state ledger |
| `POST` | `/api/iso/echo/trigger` | Fires an on-demand `0800` Echo test and returns RTT latency |
| `GET` | `/api/iso/echo/status` | Returns channel health status and heartbeat metrics |
| `POST` | `/api/iso/crypto/pin/encode` | Encodes and encrypts a PIN into an ISO 9564 PIN block (DE 52) |
| `POST` | `/api/iso/crypto/pin/translate` | Translates an encrypted PIN block across key zones / formats |
| `POST` | `/api/iso/crypto/mac/generate` | Computes an ISO 9797-1 Retail MAC for an ISO 8583 message |
| `POST` | `/api/iso/crypto/mac/verify` | Verifies message integrity against an expected DE 64 MAC |
| `POST` | `/api/iso/clearing/batch/generate` | Generates a 1240 First Presentment batch with 1644 control totals |
| `POST` | `/api/iso/clearing/chargeback` | Files a 1440 Chargeback dispute record |
| `GET` | `/api/iso/clearing/batches` | Lists all generated clearing batches |
| `GET` | `/api/iso/resiliency/status` | Returns PostgreSQL, Redis lock, and Kafka Outbox telemetry |

---

## 🧪 Testing & Verification

The project includes an automated test suite with **75 unit and integration tests**:

```bash
./mvnw test
```

### Key Test Suites:
- `IsoEngineTest`: ISO 8583 packing, bitmap generation, and unpacking.
- `IsoPinBlockEngineTest`: PIN Block Formats 0/1/3/4 encoding, decoding, encryption, and cross-zone translation.
- `DukptEngineTest`: ANSI X9.24 DUKPT IPEK derivation and transaction counter increments.
- `MacEngineTest`: ISO 9797-1 Algorithm 3 Retail MAC generation and tamper detection.
- `EmvTlvParserTest`: BER-TLV parsing and ARQC/ATC fraud signal detection.
- `BatchClearingEngineTest`: 1240 presentment batch creation, fee calculations, and 1440 chargebacks.
- `DistributedLockTest`: Redis/in-memory distributed locking contention, lease expiry, and concurrency.
- `OutboxEventStreamingTest`: Transactional outbox event creation and background poller streaming.
- `ReversalEngineTest`: 0200 auth recording, 0400 synchronous reversal matching, and partial reversals (DE 95).

---

## 📂 Project Structure

```
iso_8583/
├── src/main/java/com/dean/iso8583/
│   ├── client/                  # Terminal client for TCP socket testing
│   ├── core/
│   │   ├── clearing/            # Batch clearing, 1240 presentment, interchange calculator
│   │   ├── crypto/              # PIN Block (ISO 9564), DUKPT (X9.24), Retail MAC (ISO 9797-1)
│   │   ├── dto/                 # ISO Message, Field Definition, Spec DTOs
│   │   ├── echo/                # 0800/0810 Keep-alive network management & telemetry
│   │   ├── emv/                 # BER-TLV chip data parser (DE 55)
│   │   ├── event/               # Transactional Outbox pattern & Kafka stream poller
│   │   ├── lock/                # Distributed Lock Service (Redis / In-memory)
│   │   ├── persistence/         # Transaction & Clearing Batch repositories
│   │   ├── reversal/            # 0400 reversal engine & transaction state store
│   │   ├── spec/                # Multi-dialect specification registry
│   │   └── utils/               # Sanitizers, masking, hex/byte utilities
│   ├── server/                  # Raw Netty/TCP socket server on port 8583
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
