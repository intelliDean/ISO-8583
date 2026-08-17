package com.dean.iso8583.core.reversal;

import com.dean.iso8583.core.dto.IsoMessage;
import com.dean.iso8583.core.utils.IsoMessageSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Enterprise Reversal Engine for ISO 8583 Financial Transactions.
 *
 * <h2>Overview</h2>
 * Implements the full ISO 8583 reversal flow as defined in the ISO 8583:1993
 * specification and extended by Visa/Mastercard bilateral agreements:
 *
 * <ul>
 *   <li><b>0400</b> — Reversal Request: Acquirer requests cancellation of a
 *       prior authorisation. Must be responded to synchronously (0410).</li>
 *   <li><b>0420</b> — Reversal Advice: Acquirer notifies the issuer that a
 *       reversal has been applied offline. Must be acknowledged (0430).
 *       An advice cannot be declined; the issuer MUST accept it.</li>
 * </ul>
 *
 * <h2>Matching Logic</h2>
 * The original authorisation is located using a composite key:
 * <pre>
 *   STAN (DE 11) + ":" + masked PAN (DE 2)
 * </pre>
 *
 * <h2>Response Code Mapping (DE 39)</h2>
 * <ul>
 *   <li>{@code 00} — Reversal approved / advice acknowledged.</li>
 *   <li>{@code 25} — Unable to locate original transaction (no match in store).</li>
 *   <li>{@code 94} — Duplicate reversal (transaction already fully reversed).</li>
 * </ul>
 *
 * <h2>Partial Reversal (DE 95)</h2>
 * When DE 95 (Replacement Amounts) is present in a {@code 0420} advice,
 * the engine applies a partial reversal, setting the transaction state
 * to {@link TransactionState#PARTIALLY_REVERSED}. The remaining authorised
 * amount stays valid for clearing.
 *
 * <h2>Enterprise Relevance</h2>
 * <ul>
 *   <li>Prevents double-reversal losses: duplicate {@code 0400} for an already
 *       reversed transaction is declined with code {@code 94}.</li>
 *   <li>Advice flow (0420) is always accepted regardless of the original
 *       transaction state, per Visa BASE II and Mastercard IPM rules.</li>
 *   <li>All state transitions are logged via SLF4J with masked PAN data.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReversalEngine {

    // ── ISO 8583 Response Codes ───────────────────────────────────────────────

    /** DE 39: Approved / Advice Acknowledged */
    private static final String RC_APPROVED = "00";

    /** DE 39: Unable to locate original transaction */
    private static final String RC_NO_ORIGINAL = "25";

    /** DE 39: Duplicate transmission / already reversed */
    private static final String RC_DUPLICATE = "94";

    // ── MTI Constants ─────────────────────────────────────────────────────────

    private static final String MTI_REVERSAL_REQUEST  = "0400";
    private static final String MTI_REVERSAL_RESPONSE = "0410";
    private static final String MTI_REVERSAL_ADVICE   = "0420";
    private static final String MTI_REVERSAL_ADVICE_RESPONSE = "0430";
    private static final String MTI_AUTH_REQUEST      = "0200";
    private static final String MTI_AUTH_RESPONSE     = "0210";
    private static final String MTI_AUTH_REQUEST_ALT  = "0100";
    private static final String MTI_AUTH_RESPONSE_ALT = "0110";

    private final TransactionStore transactionStore;

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the given MTI represents an authorization
     * request that should be recorded in the transaction store.
     *
     * @param mti 4-digit ISO 8583 MTI
     */
    public boolean isAuthorisationRequest(String mti) {
        return MTI_AUTH_REQUEST.equals(mti) || MTI_AUTH_REQUEST_ALT.equals(mti);
    }

    /**
     * Returns {@code true} if the given MTI represents a reversal message
     * (either a synchronous request or an advice).
     *
     * @param mti 4-digit ISO 8583 MTI
     */
    public boolean isReversalMessage(String mti) {
        return MTI_REVERSAL_REQUEST.equals(mti) || MTI_REVERSAL_ADVICE.equals(mti);
    }

    /**
     * Records an approved authorization response in the transaction store so
     * that future reversals can locate it.
     *
     * <p>Developer Note: Only call this when DE 39 = "00" in the response.
     * Declined transactions (DE 39 ≠ "00") must NOT be recorded, because
     * attempting to reverse a declined transaction should yield code 25.</p>
     *
     * @param request  the 0200/0100 request message
     * @param response the 0210/0110 response message
     */
    public void recordApprovedAuthorisation(IsoMessage request, IsoMessage response) {
        if (!hasRequiredReversalFields(request)) {
            log.warn("Authorisation missing STAN or PAN — skipping transaction store record");
            return;
        }
        transactionStore.recordAuthorisation(request, response);
    }

    /**
     * Processes a reversal message ({@code 0400} or {@code 0420}) and returns
     * the appropriate reversal response ({@code 0410} or {@code 0430}).
     *
     * <h3>Processing Flow</h3>
     * <ol>
     *   <li>Resolve the response MTI (0410 or 0430).</li>
     *   <li>Look up the original transaction by composite STAN+PAN key.</li>
     *   <li>Validate the transaction state (decline duplicate reversals).</li>
     *   <li>Apply the reversal and update the store atomically.</li>
     *   <li>Build and return the reversal response.</li>
     * </ol>
     *
     * @param reversalRequest the incoming 0400/0420 IsoMessage
     * @return the reversal response (0410/0430) IsoMessage
     */
    public IsoMessage processReversal(IsoMessage reversalRequest) {
        String mti = reversalRequest.getMti();
        String responseMti = resolveResponseMti(mti);
        boolean isAdvice = MTI_REVERSAL_ADVICE.equals(mti);

        log.info("Processing reversal {} — STAN={} PAN={}",
                mti,
                reversalRequest.getField(11),
                IsoMessageSanitizer.maskPan(reversalRequest.getField(2))
        );

        // ── Step 1: Validate minimum required fields ───────────────────────
        if (!hasRequiredReversalFields(reversalRequest)) {
            log.warn("Reversal {} missing STAN or PAN — returning format error", mti);
            return buildReversalResponse(reversalRequest, responseMti, "30", null);
        }

        String stan      = reversalRequest.getField(11);
        String maskedPan = IsoMessageSanitizer.maskPan(reversalRequest.getField(2));

        // ── Step 2: Look up original transaction ──────────────────────────
        Optional<TransactionRecord> originalOpt = transactionStore.find(stan, maskedPan);

        if (originalOpt.isEmpty()) {
            /**
             * Developer Note:
             * Code 25 (Unable to locate record) is returned for 0400 requests
             * when no matching authorization is in the store. For 0420 advice
             * messages, per scheme rules we must still respond with 00 even
             * if we cannot find the original — the advice is treated as a
             * late notification and accepted unconditionally.
             */
            if (isAdvice) {
                log.warn("Reversal advice {} — original not found for STAN={}, accepting unconditionally", mti, stan);
                return buildReversalResponse(reversalRequest, responseMti, RC_APPROVED, null);
            }
            log.warn("Reversal {} — original transaction not found for STAN={}", mti, stan);
            return buildReversalResponse(reversalRequest, responseMti, RC_NO_ORIGINAL, null);
        }

        TransactionRecord original = originalOpt.get();

        // ── Step 3: Duplicate reversal check ─────────────────────────────
        if (original.state() == TransactionState.REVERSED && !isAdvice) {
            /**
             *  Developer Note:
             * A transaction that is already in REVERSED state cannot be reversed
             * again. This prevents a double-credit attack where an acquirer
             * intentionally sends duplicate 0400 messages. Response code 94
             * (Duplicate Transmission) is the correct ISO 8583 response.
             */
            log.warn("Duplicate reversal detected — STAN={} already REVERSED", stan);
            return buildReversalResponse(reversalRequest, responseMti, RC_DUPLICATE, original);
        }

        // ── Step 4: Determine reversal amount & new state ─────────────────
        String reversedAmount = resolveReversedAmount(reversalRequest, original);
        TransactionState newState = determineNewState(reversalRequest, original, reversedAmount);

        // ── Step 5: Atomically update store ───────────────────────────────
        Optional<TransactionRecord> updated = transactionStore.updateState(
                original.compositeKey(),
                newState,
                reversedAmount
        );

        log.info("Reversal applied — STAN={} OldState={} NewState={} ReversedAmount={}",
                stan, original.state(), newState, reversedAmount);

        return buildReversalResponse(
                reversalRequest,
                responseMti,
                RC_APPROVED,
                updated.orElse(original)
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resolves the ISO 8583 response MTI for a given reversal request MTI.
     *
     * <pre>
     *   0400 → 0410 (Reversal Request Response)
     *   0420 → 0430 (Reversal Advice Response)
     * </pre>
     */
    private String resolveResponseMti(String requestMti) {
        return switch (requestMti) {
            case MTI_REVERSAL_REQUEST -> MTI_REVERSAL_RESPONSE;
            case MTI_REVERSAL_ADVICE  -> MTI_REVERSAL_ADVICE_RESPONSE;
            default -> "0410";  // Safe fallback
        };
    }

    /**
     * Determines the reversed amount from the request.
     *
     * <p>Developer Note: For a full reversal ({@code 0400}), the reversed
     * amount equals the original authorized amount. For a partial reversal
     * advice ({@code 0420} with DE 95 present), DE 95 provides the amount
     * actually reversed, which may be less than the original.</p>
     *
     * DE 95 (Replacement Amounts) format: original amount (12) + transaction
     * amount (12) + billing fee (12). We take the first 12 digits as the
     * reversal amount.
     */
    private String resolveReversedAmount(IsoMessage request, TransactionRecord original) {
        // DE 95 — Replacement Amounts (present on partial reversal advice 0420)
        if (request.hasField(95)) {
            String de95 = request.getField(95);
            if (de95 != null && de95.length() >= 12) {
                return de95.substring(0, 12);
            }
        }
        // Full reversal: use original authorized amount
        return original.authorisedAmount();
    }

    /**
     * Determines the new transaction state after a reversal.
     *
     * <pre>If the reversed amount equals the <br>original authorized amount → {@link TransactionState#REVERSED}.</pre>
     * <p>If the reversed amount is less than the <br>original → {@link TransactionState#PARTIALLY_REVERSED}.</p>
     */
    private TransactionState determineNewState(
            IsoMessage request,
            TransactionRecord original,
            String reversedAmount
    ) {
        // Advice flow always transitions to REVERSED (scheme rule: advice is final)
        if (MTI_REVERSAL_ADVICE.equals(request.getMti())) {
            return TransactionState.REVERSED;
        }

        // Full reversal if amounts match
        if (original.authorisedAmount() != null
                && original.authorisedAmount().equals(reversedAmount)) {
            return TransactionState.REVERSED;
        }

        return TransactionState.PARTIALLY_REVERSED;
    }

    /**
     * Builds the reversal response message with all required echo fields.
     *
     * <p>Developer Note: Per ISO 8583, the response must echo all trace fields
     * from the request (DE 2, 3, 4, 7, 11, 37, 41, 42) plus the response
     * code (DE 39). The original auth code (DE 38) is echoed from the stored
     * record when available.</p>
     *
     * @param request     the reversal request
     * @param responseMti the MTI for the response (0410 or 0430)
     * @param responseCode DE 39 value
     * @param original    the original transaction record (may be null for not-found cases)
     * @return constructed response IsoMessage
     */
    private IsoMessage buildReversalResponse(
            IsoMessage request,
            String responseMti,
            String responseCode,
            TransactionRecord original
    ) {
        IsoMessage response = new IsoMessage(responseMti);
        response.setHeader(request.getHeader());

        // ── Echo trace fields from the request ────────────────────────────
        echoField(request, response, 2);   // PAN
        echoField(request, response, 3);   // Processing Code
        echoField(request, response, 4);   // Transaction Amount
        echoField(request, response, 7);   // Transmission Date & Time
        echoField(request, response, 11);  // STAN
        echoField(request, response, 37);  // RRN
        echoField(request, response, 41);  // Terminal ID
        echoField(request, response, 42);  // Merchant ID
        echoField(request, response, 49);  // Currency Code

        // ── Reversal-specific fields ───────────────────────────────────────
        response.setField(39, responseCode);

        // DE 38: Echo original auth code from the stored record if available
        if (original != null && original.authCode() != null) {
            response.setField(38, original.authCode());
        }

        // DE 95: Echo Replacement Amounts if present in request (partial reversal)
        echoField(request, response, 95);

        return response;
    }

    /** Copies a field from request to response if it exists in the request. */
    private void echoField(IsoMessage request, IsoMessage response, int fieldId) {
        if (request.hasField(fieldId)) {
            response.setField(fieldId, request.getField(fieldId));
        }
    }

    /**
     * Validates that a message contains the minimum fields required for
     * reversal matching: DE 11 (STAN) and DE 2 (PAN).
     */
    private boolean hasRequiredReversalFields(IsoMessage message) {
        return message.hasField(11) && message.hasField(2);
    }
}
