package com.dean.iso8583.web.data.utils;

public final class IsoMtiDescriptions {

    private IsoMtiDescriptions() {
    }

    public static String describe(String mti) {
        if (mti == null || mti.length() != 4) {
            return "Unknown MTI";
        }

        return "%s %s %s".formatted(
                version(mti.charAt(0)),
                messageClass(mti.charAt(1)),
                function(mti.charAt(2))
        );
    }

    private static String version(char value) {
        return switch (value) {
            case '0' -> "ISO 8583:1987";
            case '1' -> "ISO 8583:1993";
            case '2' -> "ISO 8583:2003";
            default -> "Custom Version";
        };
    }

    private static String messageClass(char value) {
        return switch (value) {
            case '1' -> "Authorization";
            case '2' -> "Financial";
            case '3' -> "File Action";
            case '4' -> "Reversal / Chargeback";
            case '5' -> "Reconciliation";
            case '8' -> "Network Management";
            default -> "Other Message";
        };
    }

    private static String function(char value) {
        return switch (value) {
            case '0' -> "Request";
            case '1' -> "Response";
            case '2' -> "Advice";
            case '3' -> "Advice Response";
            default -> "Notification";
        };
    }
}