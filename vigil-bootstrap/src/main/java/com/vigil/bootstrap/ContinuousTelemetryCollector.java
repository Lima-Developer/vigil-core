package com.vigil.bootstrap;

import com.vigil.application.port.out.interfaces.Clock;
import com.vigil.application.port.out.interfaces.DatabaseActivityReader;
import com.vigil.application.port.out.interfaces.QueryTelemetryPort;
import com.vigil.application.port.out.interfaces.TelemetryCollection;
import com.vigil.application.port.out.interfaces.WorkspaceTelemetryHistory;
import com.vigil.application.port.out.interfaces.WorkspaceTelemetrySample;
import com.vigil.application.telemetry.QueryTelemetryDeltaCalculator;
import java.time.Duration;
import java.util.Objects;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.SmartLifecycle;

/** Serial collector with bounded retry delay. One failed source never produces a synthetic zero. */
final class ContinuousTelemetryCollector implements SmartLifecycle, AutoCloseable {
    private final QueryTelemetryPort counters;
    private final DatabaseActivityReader activity;
    private final WorkspaceTelemetryHistory history;
    private final Clock clock;
    private final String target;
    private final Duration interval;
    private final Duration retention;
    private final int maximumSamples;
    private final QueryTelemetryDeltaCalculator calculator = new QueryTelemetryDeltaCalculator();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("vigil-telemetry-").factory());
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean collecting = new AtomicBoolean();
    private final AtomicBoolean paused = new AtomicBoolean();
    private volatile ScheduledFuture<?> scheduled;
    private volatile TelemetryCollection previous;
    private volatile int failures;
    private volatile String lastFailure;
    private volatile Instant lastSuccess;

    ContinuousTelemetryCollector(QueryTelemetryPort counters, DatabaseActivityReader activity,
            WorkspaceTelemetryHistory history, Clock clock, String target, Duration interval, Duration retention,
            int maximumSamples) {
        this.counters = Objects.requireNonNull(counters);
        this.activity = Objects.requireNonNull(activity);
        this.history = Objects.requireNonNull(history);
        this.clock = Objects.requireNonNull(clock);
        this.target = Objects.requireNonNull(target);
        this.interval = require(interval, "interval");
        this.retention = require(retention, "retention");
        if (maximumSamples < 2 || maximumSamples > 100_000) throw new IllegalArgumentException("maximumSamples");
        this.maximumSamples = maximumSamples;
    }

    private static Duration require(Duration duration, String label) {
        if (duration == null || duration.isNegative() || duration.isZero()) throw new IllegalArgumentException(label);
        return duration;
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) schedule(Duration.ZERO);
    }

    @Override
    public void stop() {
        running.set(false);
        var future = scheduled;
        if (future != null) future.cancel(false);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    @Override
    public void close() {
        stop();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    void pause() {
        paused.set(true);
    }

    void resume() {
        if (paused.compareAndSet(true, false) && running.get()) schedule(Duration.ZERO);
    }

    Status status() {
        return new Status(running.get(), paused.get(), collecting.get(), lastSuccess, lastFailure, failures);
    }

    void collectNow() {
        collect();
    }

    Duration retryDelay() {
        long multiplier = 1L << Math.min(failures, 5);
        long millis = Math.min(interval.toMillis() * multiplier, Duration.ofMinutes(15).toMillis());
        return Duration.ofMillis(millis);
    }

    private void schedule(Duration delay) {
        var old = scheduled;
        if (old != null && !old.isDone()) old.cancel(false);
        scheduled = executor.schedule(this::cycle, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void cycle() {
        if (!running.get()) return;
        if (paused.get()) {
            schedule(interval);
            return;
        }
        collect();
        if (running.get() && !paused.get()) schedule(retryDelay());
    }

    private void collect() {
        if (!collecting.compareAndSet(false, true)) return;
        var now = clock.now();
        try {
            var current = counters.collect(now);
            var delta = calculator.calculate(previous, current);
            var gauge = activity.read(now);
            WorkspaceTelemetrySample sample;
            if (delta.delta() != null) {
                var d = delta.delta();
                String quality = !gauge.fullVisibility() || d.quality().equals("partial") ? "partial" : "measured";
                String reason = d.reason();
                if (!gauge.fullVisibility()) {
                    reason = reason == null
                            ? "PARTIAL_ACTIVITY_VISIBILITY"
                            : reason + ";PARTIAL_ACTIVITY_VISIBILITY";
                }
                sample = new WorkspaceTelemetrySample(now, d.start(), target, "available", quality, d.calls(),
                        d.executionMillis(), d.meanExecutionMillis(), d.rows(), d.sharedHits(), d.sharedReads(),
                        d.tempReads(), d.tempWrites(), gauge.activeSessions(), gauge.waitingSessions(),
                        gauge.blockedSessions(), reason);
            } else {
                String quality = gauge.fullVisibility() ? "measured" : "partial";
                String reason = gauge.fullVisibility()
                        ? delta.reason()
                        : delta.reason() + ";PARTIAL_ACTIVITY_VISIBILITY";
                sample = new WorkspaceTelemetrySample(now, null, target, "warming_up", quality, null, null, null,
                        null, null, null, null, null, gauge.activeSessions(), gauge.waitingSessions(),
                        gauge.blockedSessions(), reason);
            }
            history.append(sample, now.minus(retention), maximumSamples);
            previous = current;
            failures = 0;
            lastFailure = null;
            lastSuccess = now;
        } catch (RuntimeException failure) {
            failures++;
            lastFailure = failure.getClass().getSimpleName();
            appendFailure(now);
        } finally {
            collecting.set(false);
        }
    }

    private void appendFailure(Instant now) {
        try {
            history.append(new WorkspaceTelemetrySample(now, null, target, "error", "unknown", null, null, null,
                    null, null, null, null, null, null, null, null, "COLLECTION_FAILED"),
                    now.minus(retention), maximumSamples);
        } catch (RuntimeException storageFailure) {
            lastFailure = storageFailure.getClass().getSimpleName();
        }
    }

    record Status(boolean running, boolean paused, boolean collecting, Instant lastSuccess, String lastFailure,
                  int consecutiveFailures) { }
}
