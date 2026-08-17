package com.dean.iso8583.client;

import com.dean.iso8583.core.dto.IsoMessage;

import java.io.IOException;

public class Testing {

    static void main() throws IOException {

// 1. Initialize client connecting to localhost:8583
        IsoTerminalClient client = new IsoTerminalClient("localhost", 8583, "6000000000");

// 2. Send 0800 Keep-Alive Echo
        IsoMessage echoResp = client.sendEcho("000001");
        System.out.println("Echo MTI: " + echoResp.getMti() + ", RC: " + echoResp.getField(39));

// 3. Send 0200 Purchase ($25.50)
        IsoMessage authResp = client.sendPurchase(
                "4532015588991234",  // Card PAN
                "000000002550",      // Amount ($25.50)
                "000123",            // STAN
                "TERM0001",          // Terminal ID
                "MERCHANT1234567"    // Merchant ID
        );
        System.out.println("Approval: " + authResp.getField(38) + ", RC: " + authResp.getField(39));

// 4. Send 0400 Reversal
        IsoMessage revResp = client.sendReversal(
                "4532015588991234",
                "000000002550",
                "000123",
                "TERM0001",
                "MERCHANT1234567"
        );
        System.out.println("Reversal Response MTI: " + revResp.getMti() + ", RC: " + revResp.getField(39));
    }
}




