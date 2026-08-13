package com.dean.iso8583.web.service;

import com.dean.iso8583.core.dto.IsoFieldDef;
import com.dean.iso8583.web.data.dto.*;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

public interface ISO8583Service {

    Map<Integer, IsoFieldDef> getCatalog();

    UnpackResult unpackMessage(UnpackRequest request);

    PackResult packMessage(@RequestBody PackRequest request);

    SimulateResult simulateTransaction(@RequestBody SimulateRequest request);



}
