/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test.testext;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Probe for Supplier-shaped @Value injection: profile key as field Supplier, connection key as
 * method-parameter Supplier. Pinned by SupplierValueControllerTest.
 */
@RestController
public class SupplierValueController {

    @Value("${greeting}")
    Supplier<String> greetingSupplier;

    @GetMapping(value = "/probe", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> probe(@Value("${api.token}") Supplier<String> apiTokenSupplier) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("greeting", greetingSupplier.get());
        body.put("apiToken", apiTokenSupplier.get());
        return body;
    }
}
