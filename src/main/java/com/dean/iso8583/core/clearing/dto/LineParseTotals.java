package com.dean.iso8583.core.clearing.dto;

import com.dean.iso8583.core.clearing.enums.ClearingRecordType;
import lombok.Getter;

/**
 * Mutable running totals accumulated while parsing lines of the batch file.
 */
@Getter
public final class LineParseTotals {
    private long grossCents = 0;
    private int presentmentCount = 0;
    private int chargebackCount = 0;

    public void accumulate(ClearingRecordType type, long amountCents) {
        grossCents += amountCents;
        if (type == ClearingRecordType.CHARGEBACK) {
            chargebackCount++;
        } else {
            presentmentCount++;
        }
    }
}
