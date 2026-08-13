package com.dean.iso8583;

import com.dean.iso8583.core.IsoPacker;
import com.dean.iso8583.core.IsoUnpacker;
import com.dean.iso8583.core.dto.IsoMessage;
import com.dean.iso8583.core.dto.IsoSpecDefinition;
import com.dean.iso8583.core.spec.IsoSpecRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IsoSpecRegistryTest {

    private IsoSpecRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new IsoSpecRegistry(new ObjectMapper());
        registry.init();
    }

    @Test
    void testLoadClasspathSpecs() {
        assertNotNull(registry.getSpec("iso8583-1987"));
        assertNotNull(registry.getSpec("visa-sms"));
        assertNotNull(registry.getSpec("mastercard-ipm"));
        assertTrue(registry.getAllSpecs().size() >= 3);
    }

    @Test
    void testFallbackToDefaultSpec() {
        IsoSpecDefinition spec = registry.getSpec("unknown-dialect-xyz");
        assertNotNull(spec);
        assertEquals("iso8583-1987", spec.id());
    }

    @Test
    void testVisaSmsDialectPackAndUnpack() {
        IsoSpecDefinition visaSpec = registry.getSpec("visa-sms");
        assertEquals("Visa SMS (Single Message System)", visaSpec.name());

        IsoMessage visaReq = new IsoMessage("0200");
        visaReq.setHeader("6000000000");
        visaReq.setField(2, "4532015588991234");
        visaReq.setField(3, "000000");
        visaReq.setField(4, "000000002550");
        visaReq.setField(11, "000123");
        visaReq.setField(55, "9F26080102030405060708"); // Visa EMV Chip Data

        String packedHex = IsoPacker.packToString(visaReq, visaSpec);
        assertNotNull(packedHex);

        IsoMessage unpacked = IsoUnpacker.unpack(packedHex, true, visaSpec);
        assertEquals("4532015588991234", unpacked.getField(2));
        assertEquals("9F26080102030405060708", unpacked.getField(55));
    }
}
