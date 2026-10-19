package com.mycompany.onprem.timer;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.workato.agent.extension.api.AgentExtensionApiV1;
import com.workato.agent.extension.api.AgentWebhookApiV1.WebhookResponse;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/webhook")
public class TimerExtension implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(TimerExtension.class);

    private final ConcurrentHashMap<String, Subscription> active = new ConcurrentHashMap<>();

    @Autowired
    private AgentExtensionApiV1 agent;

    @Value("${min-period-seconds:5}")
    private int minPeriodSeconds;

    private String profile;
    private ScheduledExecutorService scheduler;

    @Override
    public void afterPropertiesSet() {
        this.profile = agent.getContext().getProfileName();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "timer-ext");
            t.setDaemon(true);
            return t;
        });
    }

    @GetMapping("/health")
    public void health() {
    }

    @PostMapping("/subscribe")
    public SubscribeResponse subscribe(@RequestBody SubscribeRequest req) {
        if (StringUtils.isBlank(req.message())) {
            throw new BadRequestException("message must not be blank");
        }
        if (StringUtils.isBlank(req.workatoWebhookUrl())) {
            throw new BadRequestException("workato_webhook_url must not be blank");
        }
        if (req.periodSeconds() == null || req.periodSeconds() < minPeriodSeconds) {
            throw new BadRequestException("period_seconds must be >= " + minPeriodSeconds);
        }
        String id = UUID.randomUUID().toString();
        Subscription sub = new Subscription(id, req.workatoWebhookUrl(), req.message(), req.periodSeconds());
        // Schedule and set `sub.future` BEFORE publishing to `active`, so any
        // concurrent /unsubscribe that finds the subscription is guaranteed to
        // see a non-null future to cancel.
        sub.future = scheduler.scheduleWithFixedDelay(
                () -> deliver(sub),
                req.periodSeconds(), req.periodSeconds(), TimeUnit.SECONDS);
        active.put(id, sub);
        log.info("[{}] subscribed id={}, period={}s, url={}",
                profile, id, req.periodSeconds(), req.workatoWebhookUrl());
        return new SubscribeResponse(id);
    }

    @PostMapping("/unsubscribe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@RequestBody UnsubscribeRequest req) {
        Subscription sub = active.remove(req.subscriptionId());
        if (sub != null) {
            sub.future.cancel(false);
        }
        log.info("[{}] unsubscribed id={}", profile, req.subscriptionId());
    }

    @PostMapping("/fire_now")
    public FireNowResponse fireNow(@RequestBody FireNowRequest req) {
        Subscription sub = active.get(req.subscriptionId());
        if (sub == null) {
            throw new NotFoundException("unknown subscription_id: " + req.subscriptionId());
        }
        WebhookResponse resp = emit(sub);
        return new FireNowResponse(resp.isSuccessful(), resp.statusCode());
    }

    private void deliver(Subscription sub) {
        try {
            emit(sub);
        } catch (RuntimeException e) {
            // Scheduler-thread invariant: swallow + log so the next tick still runs.
            // Manual `/fire_now` deliberately does NOT swallow — callers want visible failures.
            log.warn("[{}] scheduled delivery failed for sub={}: {}", profile, sub.id, e.toString());
        }
    }

    private WebhookResponse emit(Subscription sub) {
        TimerEvent event = new TimerEvent(
                sub.message,
                Instant.now().toString(),
                sub.tick.incrementAndGet(),
                sub.id,
                sub.periodSeconds);
        return agent.getWebhookApi().send(sub.webhookUrl, event,
                Map.of("X-Workato-Event-Id", UUID.randomUUID().toString()));
    }

    @Override
    public void destroy() {
        cancelAllSubscriptions();
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Cancels every active subscription without touching the scheduler lifecycle.
     * Used by tests to reset state between cases on a shared static harness.
     */
    void cancelAllSubscriptions() {
        active.values().forEach(s -> s.future.cancel(false));
        active.clear();
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
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
        final String id;
        final String webhookUrl;
        final String message;
        final int periodSeconds;
        final AtomicLong tick = new AtomicLong(0);
        volatile ScheduledFuture<?> future;

        Subscription(String id, String webhookUrl, String message, int periodSeconds) {
            this.id = id;
            this.webhookUrl = webhookUrl;
            this.message = message;
            this.periodSeconds = periodSeconds;
        }
    }

    private static final class BadRequestException extends RuntimeException {
        BadRequestException(String message) {
            super(message);
        }
    }

    private static final class NotFoundException extends RuntimeException {
        NotFoundException(String message) {
            super(message);
        }
    }
}
