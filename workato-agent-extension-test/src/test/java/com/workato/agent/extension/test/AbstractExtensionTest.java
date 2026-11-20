/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Common helpers for extension-harness tests. Reading the raw {@link MvcResult} and asserting
 * through AssertJ (instead of Spring's matcher-style {@code status().isOk()} / {@code jsonPath(...)})
 * keeps the test suite's dependency closure to the harness's own — no Hamcrest, no json-path.
 */
public abstract class AbstractExtensionTest {

    protected static final ObjectMapper JSON = new ObjectMapper();

    protected static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    protected static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> jsonMap(MvcResult r) throws Exception {
        return JSON.readValue(body(r), Map.class);
    }

    protected static MvcResult perform(MockMvc mvc, RequestBuilder rb) throws Exception {
        return mvc.perform(rb).andReturn();
    }
}
