package com.dean.iso8583;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

import lombok.extern.slf4j.Slf4j;

/**
 * ISO 8583 Payment Engine & Switch Server — Spring Boot entry point.
 *
 * <h2>Architecture Overview</h2>
 * <pre>
 * Terminal (POS)                                Engine (Switch / Host)
 *    │                                                  │
 *    │ ──── 1. 0200 Purchase ($25.50, STAN: 000123) ───►│ (Unpacks message,
 *    │                                                  │  records auth in TransactionStore,
 *    │ ◄─── 2. 0210 Approved (DE 39: 00, Auth: A000123) │  sanitizes logs, returns 0210)
 *    │                                                  │
 * [Customer aborts / Printer fails at POS]                │
 *    │                                                  │
 *    │ ──── 3. 0400 Reversal (STAN: 000123) ───────────►│ (Locates original 0200,
 *    │                                                  │  marks state REVERSED,
 *    │ ◄─── 4. 0410 Reversal Approved (DE 39: 00) ──────│  returns 0410)
 *    │                                                  │
 * [If Terminal accidentally retries 0400 again]           │
 *    │                                                  │
 *    │ ──── 5. 0400 Duplicate Reversal ────────────────►│ (Detects already REVERSED,
 *    │ ◄─── 6. 0410 Duplicate Declined (DE 39: 94) ─────│  declines with RC 94)
 * </pre>
 *
 * <h2>Terminal Host Configuration Reference</h2>
 * <pre>
 *  ===================================================================
 *  ISO 8583 TERMINAL HOST CONFIGURATION PROFILE
 *  ===================================================================
 *
 *  [Network Connection]
 *  Host Address          = your-server-ip-or-domain.com
 *  Connection Mode       = Persistent TCP Socket (or Connect-Per-Transaction)
 *  Framing Protocol      = 2-Byte Big-Endian Binary Length Header
 *  Encoding              = US-ASCII (or ISO-8859-1)
 *  Socket Timeout        = 15000 ms (15 seconds)
 *
 *  [ISO Protocol Settings]
 *  Protocol Dialect      = ISO 8583:1987 / Visa SMS
 *  TPDU Header (10 chars)= 6000000000
 *  TCP Port              = 8583
 *  Keep-Alive Echo MTI   = 0800 (DE 70 = 301)
 *  Echo Frequency        = Every 30–60 seconds
 *
 *  [Terminal Identity]
 *  Card Acceptor Terminal ID (DE 41) = TERM0001
 *  Card Acceptor Merchant ID (DE 42) = MERCHANT1234567
 *  Currency Code (DE 49)             = 840 (USD) / 566 (NGN) / 978 (EUR)
 *  Country Code (DE 19)              = 840
 *
 *  [Transaction Processing Codes (DE 3)]
 *  Purchase (Goods & Services)   = 000000
 *  Cash Withdrawal (ATM)         = 010000
 *  Balance Inquiry               = 300000
 *  Reversal (DE 3 preserved)     = 000000
 *
 *  [Field Rules for Terminal Developer]
 *  DE 2  (PAN)                = LLVAR numeric (max 19)
 *  DE 4  (Amount)             = 12-digit fixed numeric (e.g. 000000002550 = $25.50)
 *  DE 11 (STAN)               = 6-digit rolling numeric counter (000001 -&gt; 999999)
 *  DE 55 (ICC EMV Chip Data)  = LLLVAR BER-TLV hex stream (ARQC: 9F26, ATC: 9F36)
 * </pre>
 */
@Slf4j
@EnableScheduling
@SpringBootApplication
@ConfigurationPropertiesScan
public class Iso8583Application {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(Iso8583Application.class);
        application.setBannerMode(Banner.Mode.LOG);

        ConfigurableApplicationContext context = application.run(args);
        Environment environment = context.getEnvironment();

        String port    = environment.getProperty("local.server.port");
        String appName = environment.getProperty("spring.application.name", "ISO-8583");

        log.info("""
                        
                        -----------------------------------------------
                        🚀 {} is Running!
                        🌐 URL:     http://localhost:{}
                        📄 ISO TCP PORT: http://localhost:8583
                        -----------------------------------------------
                        """,
                appName, port
        );
    }
}