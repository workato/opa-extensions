/*
 * Copyright (c) 2026 MyCompany, Inc. All rights reserved.
 */

package com.mycompany.onprem.configuration_sample;

import com.workato.agent.extension.api.AgentConfigSupport;
import com.workato.agent.extension.api.AgentConnectionLifecycleV1;
import com.workato.agent.extension.api.AgentExtensionApiV1;
import com.workato.agent.extension.api.AgentExtensionContextV1.AgentConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Minimal demonstration cloud-managed extension — exercises the two
 * configuration sources an {@code @AgentConfigSupport} controller sees:
 *
 * <ul>
 *   <li><b>Static per-adapter</b> — one string key {@code environment}, pinned
 *       by the admin in {@code agent-config-support.configuration_sample.environment}
 *       of the agent's local {@code config.yml}. Resolved once at bean init via
 *       raw-type {@code @Value}.</li>
 *   <li><b>Connection-dynamic</b> — one integer key {@code timeout}, delivered
 *       per connection in {@code custom.configuration_sample.connections.{id}.timeout}
 *       of the cloud push. Resolved lazily per request via {@code @Value Supplier<T>}.</li>
 * </ul>
 *
 * <p>Two operations are exposed to the recipe:
 * <ul>
 *   <li>{@code GET /{connId}/test} — connection health check: 200 when
 *       {@code timeout > 0}, 400 otherwise.</li>
 *   <li>{@code GET /{connId}/info} — returns the two parameter values plus the
 *       connection's {@code connectionVersion} and the timestamp of the last
 *       cloud-reload callback for that connection.</li>
 * </ul>
 */
@AgentConfigSupport(name = "configuration_sample")
@RestController
public class ConfigurationSampleExtension implements AgentConnectionLifecycleV1 {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationSampleExtension.class);
    // Last cloud-reload callback timestamp per connection. The controller is a
    // singleton shared across connections and threads — ConcurrentHashMap keeps
    // the map thread-safe by construction.
    private final ConcurrentHashMap<Long, Instant> updatedAt = new ConcurrentHashMap<>();
    @Value("${environment:unknown}")
    private String environment;
    @Value("${timeout:0}")
    private Supplier<Integer> timeout;
    @Autowired
    private AgentExtensionApiV1 agent;

    @GetMapping("/{connId}/test")
    public ResponseEntity<Map<String, Object>> test(@PathVariable("connId") long connId) {
        Integer t = timeout.get();
        log.info("[configuration_sample] test(connId={}, timeout={})", connId, t);
        Map<String, Object> body = new LinkedHashMap<>();
        if (t == null || t <= 0) {
            body.put("status", "error");
            body.put("error", "timeout must be > 0");
            body.put("timeout", t);
            return ResponseEntity.badRequest().body(body);
        }
        body.put("status", "ok");
        body.put("timeout", t);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{connId}/info")
    public Map<String, Object> getInfo(@PathVariable("connId") long connId) {
        log.info("[configuration_sample] getInfo(connId={})", connId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("environment", environment);
        response.put("timeout", timeout.get());
        response.put("connectionVersion", agent.getContext().getConnectionVersion());
        Instant ts = updatedAt.get(connId);
        response.put("updatedAt", ts == null ? null : ts.toString());
        return response;
    }

    @Override
    public void onConnectionAdded(long connectionId, AgentConfiguration mergedConfig) {
        log.info("[configuration_sample] onConnectionAdded(connectionId={})", connectionId);
        updatedAt.put(connectionId, Instant.now());
    }

    @Override
    public void onConnectionUpdated(long connectionId, AgentConfiguration newMergedConfig) {
        log.info("[configuration_sample] onConnectionUpdated(connectionId={})", connectionId);
        updatedAt.put(connectionId, Instant.now());
    }

    @Override
    public void onConnectionRemoved(long connectionId) {
        log.info("[configuration_sample] onConnectionRemoved(connectionId={})", connectionId);
        updatedAt.remove(connectionId);
    }
}
