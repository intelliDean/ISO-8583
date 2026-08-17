package com.dean.iso8583.core.echo.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Developer Note:
 * Standard ISO 8583 Data Element 70 (Network Management Information Code) definitions.
 *
 * <p>DE 70 is present in all {@code 0800} (Network Management Request) and
 * {@code 0810} (Network Management Response) messages. It defines the operational
 * action being performed across the interchange channel.</p>
 *
 * <h2>Enterprise Relevance</h2>
 * <ul>
 *   <li><b>{@link #ECHO_TEST} (301) </b> — Periodic heartbeat / ping used for dead-peer detection
 *       and preventing intermediate stateful firewalls/NATs from dropping idle TCP sessions.</li>
 *   <li><b>{@link #LOGON} (001)</b>  / <b>{@link #LOGOFF} (002)</b>  — Establishes or terminates the cryptographic
 *       and transaction processing session between payment nodes.</li>
 *   <li><b>{@link #CUTOVER} (201)</b>  — Signals end-of-day batch settlement cutover and reconciliation.</li>
 *   <li><b>{@link #KEY_EXCHANGE} (101)</b>  — Rotates Zone Master Keys (ZMK) or Working Keys (ZPK/ZAK).</li>
 * </ul>
 */
@Getter
@AllArgsConstructor
public enum NetworkManagementCode {

    /** 001 – System Log On / Sign-On */
    LOGON("001", "Logon / Sign-On"),

    /** 002 – System Log Off / Sign-Off */
    LOGOFF("002", "Logoff / Sign-Off"),

    /** 101 – Key Exchange (PIN/MAC Working Key Rotation) */
    KEY_EXCHANGE("101", "Key Exchange / Key Change"),

    /** 201 – Cutover / End-of-Day Reconciliation */
    CUTOVER("201", "Cutover / Reconciliation"),

    /** 301 – System Echo Test (Keep-Alive / Heartbeat) */
    ECHO_TEST("301", "Echo Test / Heartbeat"),

    /** UNKNOWN – Unregistered network management code */
    UNKNOWN("", "Unknown Network Management Code");

    private final String code;
    private final String description;

//    NetworkManagementCode(String code, String description) {
//        this.code = code;
//        this.description = description;
//    }

    /**
     * Resolves a DE 70 code string to its {@link NetworkManagementCode} enum constant.
     *
     * @param code DE 70 code string (e.g. "301")
     * @return matching enum constant or {@link #UNKNOWN}
     */
    public static NetworkManagementCode fromCode(String code) {
        if (code == null || code.isBlank()) return UNKNOWN;

        for (NetworkManagementCode networkMgtCode : values()) {
            if (networkMgtCode.code.equals(code.trim())) {
                return networkMgtCode;
            }
        }
        return UNKNOWN;
    }
}
