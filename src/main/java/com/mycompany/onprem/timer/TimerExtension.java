package com.mycompany.onprem.timer;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.workato.agent.extension.api.AgentExtensionApiV1;
import com.workato.agent.extension.api.AgentWebhookApiV1.WebhookResponse;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/webhook")
public class TimerExtension implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(TimerExtension.class);
    private final ConcurrentHashMap<String, Subscription> active = new ConcurrentHashMap<>();
    @Autowired
    private AgentExtensionApiV1 agent;
    @Value("${min-period-seconds:5}")
    private int minPeriodSeconds;
    private volatile ScheduledExecutorService scheduler;

    @GetMapping("/health")
    public ResponseEntity<Void> health() {
        return ResponseEntity.ok().build();
    }

    @PostMapping("/subscribe")
    public ResponseEntity<?> subscribe(@RequestBody SubscribeRequest req) {
        if (StringUtils.isBlank(req.message())) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("message must not be blank"));
        }
        if (req.periodSeconds() == null || req.periodSeconds() < minPeriodSeconds) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("period_seconds must be >= " + minPeriodSeconds));
        }
        String id = UUID.randomUUID().toString();
        Subscription sub = new Subscription(req.workatoWebhookUrl(), req.message(), req.periodSeconds());
        active.put(id, sub);
        sub.future = scheduler().scheduleWithFixedDelay(
                () -> deliver(id, sub),
                req.periodSeconds(), req.periodSeconds(), TimeUnit.SECONDS);
        log.info("[{}] subscribed id={}, period={}s, url={}",
                agent.getContext().getProfileName(), id, req.periodSeconds(), req.workatoWebhookUrl());
        return ResponseEntity.ok(new SubscribeResponse(id));
    }

    @PostMapping("/unsubscribe")
    public ResponseEntity<Void> unsubscribe(@RequestBody UnsubscribeRequest req) {
        Subscription sub = active.remove(req.subscriptionId());
        if (sub != null && sub.future != null) {
            sub.future.cancel(false);
        }
        log.info("[{}] unsubscribed id={}", agent.getContext().getProfileName(), req.subscriptionId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/fire_now")
    public ResponseEntity<?> fireNow(@RequestBody FireNowRequest req) {
        Subscription sub = active.get(req.subscriptionId());
        if (sub == null) {
            return ResponseEntity.status(404)
                    .body(new ErrorResponse("unknown subscription_id: " + req.subscriptionId()));
        }
        WebhookResponse resp = send(req.subscriptionId(), sub);
        return ResponseEntity.ok(new FireNowResponse(resp.isSuccessful(), resp.statusCode()));
    }

    private void deliver(String id, Subscription sub) {
        try {
            send(id, sub);
        } catch (RuntimeException e) {
            log.warn("[{}] scheduled delivery failed for sub={}: {}",
                    agent.getContext().getProfileName(), id, e.toString());
        }
    }

    private WebhookResponse send(String id, Subscription sub) {
        long t = sub.tick.incrementAndGet();
        TimerEvent event = new TimerEvent(
                sub.message,
                DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                t,
                id,
                sub.periodSeconds);
        return agent.getWebhookApi().send(sub.webhookUrl, event,
                Map.of("X-Workato-Event-Id", UUID.randomUUID().toString()));
    }

    private ScheduledExecutorService scheduler() {
        ScheduledExecutorService local = scheduler;
        if (local == null) {
            synchronized (this) {
                local = scheduler;
                if (local == null) {
                    AtomicInteger n = new AtomicInteger();
                    ThreadFactory tf = r -> {
                        Thread thread = new Thread(r, "timer-ext-" + n.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    };
                    local = Executors.newSingleThreadScheduledExecutor(tf);
                    scheduler = local;
                }
            }
        }
        return local;
    }

    @Override
    public void destroy() {
        active.values().forEach(s -> {
            if (s.future != null) {
                s.future.cancel(false);
            }
        });
        active.clear();
        ScheduledExecutorService local = scheduler;
        if (local != null) {
            local.shutdownNow();
            try {
                local.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
    }

    public record SubscribeRequest(
            @JsonProperty("workato_webhook_url") String workatoWebhookUrl,
            @JsonProperty("recipe_id") String recipeId,
            @JsonProperty("connection_id") String connectionId,
            @JsonProperty("message") String message,
            @JsonProperty("period_seconds") Integer periodSeconds) {
    }

    public record UnsubscribeRequest(
            @JsonProperty("recipe_id") String subscriptionId,
            @JsonProperty("connection_id") String connectionId) {
    }

    public record FireNowRequest(
            @JsonProperty("subscription_id") String subscriptionId) {
    }

    public record SubscribeResponse(@JsonProperty("id") String id) {
    }

    public record FireNowResponse(
            @JsonProperty("delivered") boolean delivered,
            @JsonProperty("status_code") int statusCode) {
    }

    public record ErrorResponse(@JsonProperty("error") String error) {
    }

    public record TimerEvent(
            @JsonProperty("message") String message,
            @JsonProperty("generated_at") String generatedAt,
            @JsonProperty("tick") long tick,
            @JsonProperty("subscription_id") String subscriptionId,
            @JsonProperty("period_seconds") int periodSeconds) {
    }

    private static final class Subscription {
        final String webhookUrl;
        final String message;
        final int periodSeconds;
        final AtomicLong tick = new AtomicLong(0);
        volatile ScheduledFuture<?> future;

        Subscription(String webhookUrl, String message, int periodSeconds) {
            this.webhookUrl = webhookUrl;
            this.message = message;
            this.periodSeconds = periodSeconds;
        }
    }
}
