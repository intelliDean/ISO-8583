package com.dean.iso8583.server;

import com.dean.iso8583.core.dto.IsoMessage;
import com.dean.iso8583.core.reversal.ReversalEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Central ISO 8583 message dispatcher.
 *
 * <h2>Routing Logic</h2>
 * <pre>
 * Incoming MTI
 *    │
 *    ├── 0200 / 0100 (Authorisation Request)
 *    │       └─► process() → record approval in TransactionStore → return 0210/0110
 *    │
 *    ├── 0400 (Reversal Request)
 *    │       └─► ReversalEngine.processReversal() → return 0410
 *    │
 *    ├── 0420 (Reversal Advice)
 *    │       └─► ReversalEngine.processReversal() → return 0430 (always 00)
 *    │
 *    └── 0800 (Network Management / Echo)
 *            └─► process() → return 0810
 * </pre>
 *
 * <h2>Enterprise Relevance</h2>
 * This class acts as the single entry point for all inbound ISO 8583 messages
 * received over TCP. Routing is performed here so that each message type's
 * business logic remains encapsulated in dedicated components ({@link ReversalEngine},
 * future EchoScheduler, etc.) rather than in the transport layer ({@link IsoTcpServer}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IsoMessageProcessor {

    // ── Response MTI table ────────────────────────────────────────────────────

    /**
     * Developer Note:
     * <p>Only non-reversal request MTIs are in this table. Reversal MTIs (0400/0420)
     * are handled separately by the ReversalEngine and never reach these default
     * mappings.</p>
     */
    private static final Map<String, String> RESPONSE_MTIS = Map.of(
            "0800", "0810",   // Network Management
            "0100", "0110",   // Authorisation (legacy format)
            "0200", "0210"    // Authorisation (current format)
    );

    /**
     * DE fields that must always be echoed from request to response to satisfy
     * ISO 8583 matching and settlement reconciliation requirements.
     */
    private static final List<Integer> TRACE_FIELDS = List.of(2, 3, 4, 7, 11, 37, 41, 42, 49, 70);

    private final ReversalEngine reversalEngine;

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * <p>Routes and processes an inbound ISO 8583 message.</p>
     *
     * Developer Note: <p>Reversal messages (0400/0420) are dispatched to the
     * {@link ReversalEngine}, which manages transaction-state lookup, duplicate
     * detection, and atomic state transitions. All other messages are handled by
     * the standard approval flow and recorded in the store if approved.</p>
     *
     * @param request the inbound ISO 8583 message
     * @return the appropriate ISO 8583 response message
     */
    public IsoMessage process(IsoMessage request) {
        String mti = request.getMti();

        // ── Route reversals to the dedicated engine ───────────────────────
        if (reversalEngine.isReversalMessage(mti)) {
            return reversalEngine.processReversal(request);
        }

        // ── Standard authorization / network management flow ──────────────
        IsoMessage response = createResponse(request);
        copyTraceFields(request, response);
        populateResponseFields(request, response);

        // ── Record approved authorizations in the transaction store ───────
        if (reversalEngine.isAuthorisationRequest(mti)) {
            /*
             * Developer Note:
             * We only record the transaction AFTER the response is built, so
             * the store always holds the exact auth code and RRN that were
             * sent to the acquirer. This ensures the reversal matching returns
             * the same values the acquirer's receipt carries.
             */
            reversalEngine.recordApprovedAuthorisation(request, response);
        }

        return response;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Response construction
    // ─────────────────────────────────────────────────────────────────────────

    private IsoMessage createResponse(IsoMessage request) {
        // Default to 0210 if the MTI is not in our explicit table (e.g. future MTIs)
        String responseMti = RESPONSE_MTIS.getOrDefault(request.getMti(), "0210");
        IsoMessage response = new IsoMessage(responseMti);
        response.setHeader(request.getHeader());
        return response;
    }

    private void copyTraceFields(IsoMessage request, IsoMessage response) {
        for (int fieldId : TRACE_FIELDS) {
            copyField(request, response, fieldId);
        }
    }

    private void copyField(IsoMessage request, IsoMessage response, int fieldId) {
        if (request.hasField(fieldId)) {
            response.setField(fieldId, request.getField(fieldId));
        }
    }

    private void populateResponseFields(IsoMessage request, IsoMessage response) {
        response.setField(39, "00");
        response.setField(37, generateRrn(request));
        response.setField(38, generateAuthorizationCode(request));
    }

    /**
     * <p>Generates a deterministic 6-character auth code from DE 11 (STAN).</p>
     *
     * Developer Note: <p>In production, auth codes are issued by the HSM or
     * authorization engine and are globally unique. Here we derive one from
     * the STAN for simulation purposes.</p>
     */
    private String generateAuthorizationCode(IsoMessage request) {
        if (!request.hasField(11)) {
            return "AUTH01";
        }
        String stan = request.getField(11);
        String code = "A" + stan;
        return code.length() > 6 ? code.substring(0, 6) : String.format("%-6s", code);
    }

    /**
     * <p>Generates a 12-character Retrieval Reference Number (DE 37).</p>
     *
     * Developer Note: <p>In production, the RRN is issued by the acquirer
     * system and is unique per transaction per acquirer BIN. We use a static
     * value here for host simulation purposes.</p>
     */
    private String generateRrn(IsoMessage request) {
        return "123456789012";
    }
}