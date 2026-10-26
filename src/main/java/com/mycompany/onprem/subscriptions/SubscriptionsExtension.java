/*
 * Copyright (c) 2026 MyCompany, Inc. All rights reserved.
 */

package com.mycompany.onprem.subscriptions;

import com.workato.agent.extension.api.AgentConfigSupport;
import com.workato.agent.extension.api.AgentConnectionLifecycleV1;
import com.workato.agent.extension.api.AgentExtensionApiV1;
import com.workato.agent.extension.api.AgentExtensionContextV1.AgentConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Demonstration cloud-managed extension — shows how to combine the three
 * configuration sources an {@code @AgentConfigSupport} controller sees:
 *
 * <ol>
 *   <li>Static per-adapter — admin-pinned keys from
 *       {@code agent-config-support.subscriptions.*} in the agent's local
 *       {@code config.yml}. Resolved once at bean init via raw-type
 *       {@code @Value}.</li>
 *   <li>Connection-dynamic — per-connection keys from
 *       {@code custom.subscriptions.connections.{id}.*} in the cloud push.
 *       Resolved lazily per request via {@code @Value Supplier<T>} and
 *       {@code getProfileConfiguration().get(...)}.</li>
 *   <li>Lifecycle statistics — counters and per-connection history populated
 *       from {@link AgentConnectionLifecycleV1} callbacks (the "Subscription
 *       API"). Framework invokes the callbacks whenever the cloud push adds,
 *       updates, or removes a connection; the controller mirrors that into
 *       its own in-memory state.</li>
 * </ol>
 *
 * <p>Single operation {@code GET /{connId}/inspect} returns a JSON envelope
 * with all three blocks so a recipe (or {@code curl}) can inspect what the
 * controller is seeing at any point in time.
 */
@AgentConfigSupport(name = "subscriptions")
@RestController
public class SubscriptionsExtension implements AgentConnectionLifecycleV1 {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionsExtension.class);

    // --- lifecycle statistics (populated from AgentConnectionLifecycleV1) ---
    // Controller is a singleton shared across connections and threads, so every
    // mutable field is thread-safe by construction.
    private final AtomicLong addedCount = new AtomicLong();
    private final AtomicLong updatedCount = new AtomicLong();
    private final AtomicLong removedCount = new AtomicLong();
    private final ConcurrentHashMap<Long, ConnectionStats> perConnection = new ConcurrentHashMap<>();

    // --- static per-adapter (admin-pinned, same value for every connection) ---
    // Raw-type @Value: Spring resolves against agent-config-support.subscriptions.*
    // once when the bean is created. If the admin has not set the key, the default
    // after the colon is used.
    @Value("${environment:unknown}")
    private String environment;
    @Value("${region:unknown}")
    private String region;
    @Value("${banner-text:no banner configured}")
    private String bannerText;

    // --- connection-dynamic (resolves against the current request's connection) ---
    // Supplier<T>-wrapped @Value: framework-provided BeanPostProcessor replaces
    // the field with a supplier that reads getProfileConfiguration().get(key) on
    // every .get() call, so the value tracks whichever connection is currently
    // in flight. ${label:unlabeled} applies the default when the connection has
    // no `label` key.
    @Value("${label:unlabeled}")
    private Supplier<String> label;

    @Autowired
    private AgentExtensionApiV1 agent;

    @GetMapping("/{connId}/inspect")
    public Map<String, Object> inspect(@PathVariable("connId") long connId) {
        AgentConfiguration cfg = agent.getContext().getProfileConfiguration();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("adapter", agent.getContext().getProfileName());
        response.put("connectionId", agent.getContext().getConnectionId());
        response.put("connectionIdFromPath", connId);
        response.put("connectionVersion", agent.getContext().getConnectionVersion());

        // (1) static per-adapter — same for every connection of this adapter
        Map<String, Object> staticBlock = new LinkedHashMap<>();
        staticBlock.put("environment", environment);
        staticBlock.put("region", region);
        staticBlock.put("bannerText", bannerText);
        response.put("staticPerAdapter", staticBlock);

        // (2) connection-dynamic — current connection's merged view
        Map<String, Object> connBlock = new LinkedHashMap<>();
        connBlock.put("label-via-supplier", label.get());
        connBlock.put("label-via-config", String.valueOf(cfg.get("label")));
        connBlock.put("allKeys", new TreeSet<>(cfg.keySet()));
        response.put("connection", connBlock);

        // (3) lifecycle stats — accumulated from AgentConnectionLifecycleV1 callbacks
        Map<String, Object> lifecycleBlock = new LinkedHashMap<>();
        lifecycleBlock.put("addedTotal", addedCount.get());
        lifecycleBlock.put("updatedTotal", updatedCount.get());
        lifecycleBlock.put("removedTotal", removedCount.get());
        lifecycleBlock.put("trackedConnections", new TreeSet<>(perConnection.keySet()));
        lifecycleBlock.put("thisConnection",
                perConnection.getOrDefault(connId, ConnectionStats.unseen()).toMap());
        response.put("lifecycle", lifecycleBlock);

        return response;
    }

    // --- AgentConnectionLifecycleV1 — the Subscription API ---
    //
    // Framework calls these from the cloud-reload thread (NOT a request-worker
    // thread), outside any HTTP request. Methods on AgentExtensionContextV1 that
    // depend on request state (getConnectionId / getProfileConfiguration /
    // getRequestMetadata / getConnectionVersion) return sentinel values here —
    // use the parameters instead.
    //
    // Exceptions thrown from a callback are logged ERROR by the framework and
    // cloud reload continues; we do no I/O here that could fail.

    @Override
    public void onConnectionAdded(long connectionId, AgentConfiguration mergedConfig) {
        addedCount.incrementAndGet();
        perConnection.put(connectionId, ConnectionStats.firstSeen(mergedConfig));
        log.info("[subscriptions] connection {} added, keys={}",
                connectionId, mergedConfig.keySet());
    }

    @Override
    public void onConnectionUpdated(long connectionId, AgentConfiguration newMergedConfig) {
        updatedCount.incrementAndGet();
        perConnection.compute(connectionId, (id, prior) ->
                prior == null
                        ? ConnectionStats.firstSeen(newMergedConfig).withBumpedUpdate(newMergedConfig)
                        : prior.withBumpedUpdate(newMergedConfig));
        log.info("[subscriptions] connection {} updated, keys={}",
                connectionId, newMergedConfig.keySet());
    }

    @Override
    public void onConnectionRemoved(long connectionId) {
        removedCount.incrementAndGet();
        perConnection.remove(connectionId);
        log.info("[subscriptions] connection {} removed", connectionId);
    }

    /**
     * Immutable snapshot of what the controller has observed for one connection
     * across lifecycle callbacks. Replace-on-update keeps the type trivially
     * thread-safe inside the enclosing {@code ConcurrentHashMap#compute}.
     */
    private record ConnectionStats(String firstSeenAt,
                                   String lastSeenAt,
                                   int updateCount,
                                   List<String> lastKnownKeys) {

        static ConnectionStats firstSeen(AgentConfiguration cfg) {
            String now = Instant.now().toString();
            return new ConnectionStats(now, now, 0, List.copyOf(new TreeSet<>(cfg.keySet())));
        }

        static ConnectionStats unseen() {
            return new ConnectionStats("never", "never", 0, List.of());
        }

        ConnectionStats withBumpedUpdate(AgentConfiguration cfg) {
            return new ConnectionStats(firstSeenAt, Instant.now().toString(),
                    updateCount + 1, List.copyOf(new TreeSet<>(cfg.keySet())));
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("firstSeenAt", firstSeenAt);
            m.put("lastSeenAt", lastSeenAt);
            m.put("updateCount", updateCount);
            m.put("lastKnownKeys", lastKnownKeys);
            return m;
        }
    }
}
