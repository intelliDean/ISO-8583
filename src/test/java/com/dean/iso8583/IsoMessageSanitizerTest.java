package com.dean.iso8583;

import com.dean.iso8583.core.dto.IsoMessage;
import com.dean.iso8583.core.utils.IsoMessageSanitizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IsoMessageSanitizerTest {

    @Test
    void testMaskPanStandard16Digits() {
        String pan = "4532015588991234";
        String masked = IsoMessageSanitizer.maskPan(pan);
        assertEquals("453201******1234", masked);
    }

    @Test
    void testMaskPanLong19Digits() {
        String pan = "4532015588991234999";
        String masked = IsoMessageSanitizer.maskPan(pan);
        assertEquals("453201*********4999", masked);
    }

    @Test
    void testMaskTrack2() {
        String track2 = "4532015588991234=26121010000";
        String masked = IsoMessageSanitizer.maskTrack2(track2);
        assertEquals("453201******1234=****1010000", masked);
    }

    @Test
    void testSanitizeIsoMessage() {
        IsoMessage msg = new IsoMessage("0200");
        msg.setHeader("6000000000");
        msg.setField(2, "4532015588991234");
        msg.setField(3, "000000");
        msg.setField(4, "000000002550");
        msg.setField(52, "0102030405060708"); // Encrypted PIN block

        IsoMessage sanitized = IsoMessageSanitizer.sanitizeMessage(msg);

        assertEquals("453201******1234", sanitized.getField(2));
        assertEquals("000000", sanitized.getField(3));
        assertEquals("000000002550", sanitized.getField(4));
        assertEquals("[PIN BLOCK MASKED]", sanitized.getField(52));

        // Original message must remain unmutated
        assertEquals("4532015588991234", msg.getField(2));
        assertEquals("0102030405060708", msg.getField(52));
    }

    @Test
    void testSanitizePayloadForLogging() {
        String rawPayload = "600000000002007020000000C08000164532015588991234000000000000002550000123TERM0001MERCHANT1234567840";
        String logStr = IsoMessageSanitizer.sanitizePayloadForLogging(rawPayload, true);

        assertTrue(logStr.contains("453201******1234"));
        assertFalse(logStr.contains("4532015588991234"));
    }
}
