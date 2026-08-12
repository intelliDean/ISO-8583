package com.example.iso8583;

import com.example.iso8583.core.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IsoEngineTest {

    @Test
    void testFinancialRequestPackAndUnpack() {
        IsoMessage req = new IsoMessage("0200");
        req.setHeader("6000000000");
        req.setField(2, "4532015588991234");       // PAN
        req.setField(3, "000000");                 // Purchase Processing Code
        req.setField(4, "000000002550");             // $25.50 Amount
        req.setField(11, "000123");                // STAN
        req.setField(41, "TERM0001");              // Terminal ID
        req.setField(42, "MERCHANT1234567");       // Merchant ID
        req.setField(49, "840");                   // USD

        String rawPacked = IsoPacker.packToString(req);
        assertNotNull(rawPacked);
        assertTrue(rawPacked.startsWith("60000000000200"));

        // Unpack raw payload
        IsoMessage unpacked = IsoUnpacker.unpack(rawPacked, true);
        assertEquals("6000000000", unpacked.getHeader());
        assertEquals("0200", unpacked.getMti());
        assertEquals("4532015588991234", unpacked.getField(2));
        assertEquals("000000", unpacked.getField(3));
        assertEquals("000000002550", unpacked.getField(4));
        assertEquals("000123", unpacked.getField(11));
        assertEquals("TERM0001", unpacked.getField(41));
        assertEquals("MERCHANT1234567", unpacked.getField(42));
        assertEquals("840", unpacked.getField(49));
    }

    @Test
    void testNetworkManagementEchoTest() {
        IsoMessage req = new IsoMessage("0800");
        req.setField(11, "987654");
        req.setField(70, "301"); // Echo Test NMIC

        String rawPacked = IsoPacker.packToString(req);

        IsoMessage unpacked = IsoUnpacker.unpack(rawPacked, false);
        assertEquals("0800", unpacked.getMti());
        assertEquals("987654", unpacked.getField(11));
        assertEquals("301", unpacked.getField(70));
    }
}
