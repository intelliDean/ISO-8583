package com.dean.iso8583.web.data.utils;

import java.util.Map;

public final class IsoResponseCodes {

    private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
            Map.entry("00", "Approved / Successful"),
            Map.entry("01", "Refer to Card Issuer"),
            Map.entry("04", "Pick-Up Card (Hot Card)"),
            Map.entry("05", "Do Not Honor"),
            Map.entry("14", "Invalid Card Number (PAN)"),
            Map.entry("51", "Insufficient Funds"),
            Map.entry("54", "Expired Card"),
            Map.entry("55", "Incorrect PIN"),
            Map.entry("91", "Issuer Timeout / Down"),
            Map.entry("96", "System Error")
    );

    private IsoResponseCodes() {
    }

    public static String descriptionOf(String code) {
        if (code == null) {
            return "No Response Code";
        }

        return DESCRIPTIONS.getOrDefault(
                code,
                "Response Code " + code
        );
    }
}