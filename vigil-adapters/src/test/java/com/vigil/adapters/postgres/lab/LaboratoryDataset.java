package com.vigil.adapters.postgres.lab;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Consumer;

/**
 * Deterministic synthetic dataset for the PostgreSQL laboratory. Every textual value comes from a closed vocabulary
 * and every date is derived from a fixed epoch, so the same seed always yields the same content.
 */
record LaboratoryDataset(long seed, int customerCount, int orderCount) {

    static final int CUSTOMER_COUNT = 20_000;
    static final int ORDER_COUNT = 300_000;
    static final int BATCH_SIZE = 5_000;
    static final long DEFAULT_SEED = 42L;

    static final String STATUS_PENDING = "PENDING";
    static final int HEALTHY_CUSTOMER_ID = 1;
    static final int HEALTHY_CUSTOMER_ORDERS = 15;

    static final List<String> REGIONS = List.of("NORTH", "SOUTH", "EAST", "WEST", "CENTRAL");
    static final List<String> SEGMENTS = List.of("RETAIL", "WHOLESALE", "ENTERPRISE", "STARTUP");
    static final List<String> NON_PENDING_STATUSES = List.of("COMPLETED", "SHIPPED", "CANCELLED");

    private static final Instant EPOCH = Instant.parse("2024-01-01T00:00:00Z");
    private static final long CUSTOMER_AGE_SECONDS = ChronoUnit.DAYS.getDuration().toSeconds() * 730;
    private static final long ORDER_WINDOW_SECONDS = ChronoUnit.DAYS.getDuration().toSeconds() * 365;
    private static final long ORDER_SEED_SALT = 0x9E3779B97F4A7C15L;
    private static final int PENDING_PER_THOUSAND = 5;
    private static final int MIN_TOTAL_CENTS = 500;
    private static final int TOTAL_CENTS_SPREAD = 50_000;

    record CustomerRow(int id, String region, String segment, Instant createdAt) {
    }

    record OrderRow(int id, int customerId, String status, int totalCents, Instant placedAt) {
    }

    LaboratoryDataset {
        if (customerCount < 2) {
            throw new IllegalArgumentException("customerCount must be at least 2");
        }
        if (orderCount < HEALTHY_CUSTOMER_ORDERS) {
            throw new IllegalArgumentException("orderCount must be at least " + HEALTHY_CUSTOMER_ORDERS);
        }
    }

    static LaboratoryDataset standard() {
        return new LaboratoryDataset(DEFAULT_SEED, CUSTOMER_COUNT, ORDER_COUNT);
    }

    void forEachCustomer(Consumer<CustomerRow> consumer) {
        SplittableRandom random = new SplittableRandom(seed);
        for (int id = 1; id <= customerCount; id++) {
            String region = REGIONS.get(random.nextInt(REGIONS.size()));
            String segment = SEGMENTS.get(random.nextInt(SEGMENTS.size()));
            Instant createdAt = EPOCH.minusSeconds(random.nextLong(CUSTOMER_AGE_SECONDS));
            consumer.accept(new CustomerRow(id, region, segment, createdAt));
        }
    }

    void forEachOrder(Consumer<OrderRow> consumer) {
        SplittableRandom random = new SplittableRandom(seed ^ ORDER_SEED_SALT);
        for (int id = 1; id <= orderCount; id++) {
            int customerId = id <= HEALTHY_CUSTOMER_ORDERS
                    ? HEALTHY_CUSTOMER_ID
                    : HEALTHY_CUSTOMER_ID + 1 + random.nextInt(customerCount - 1);
            String status = random.nextInt(1000) < PENDING_PER_THOUSAND
                    ? STATUS_PENDING
                    : NON_PENDING_STATUSES.get(random.nextInt(NON_PENDING_STATUSES.size()));
            int totalCents = MIN_TOTAL_CENTS + random.nextInt(TOTAL_CENTS_SPREAD);
            Instant placedAt = EPOCH.plusSeconds(random.nextLong(ORDER_WINDOW_SECONDS));
            consumer.accept(new OrderRow(id, customerId, status, totalCents, placedAt));
        }
    }

    void forEachCustomerBatch(Consumer<List<CustomerRow>> consumer) {
        Batcher<CustomerRow> batcher = new Batcher<>(consumer);
        forEachCustomer(batcher::add);
        batcher.flush();
    }

    void forEachOrderBatch(Consumer<List<OrderRow>> consumer) {
        Batcher<OrderRow> batcher = new Batcher<>(consumer);
        forEachOrder(batcher::add);
        batcher.flush();
    }

    long sumTotalCents() {
        long[] sum = {0};
        forEachOrder(order -> sum[0] += order.totalCents());
        return sum[0];
    }

    String sha256() {
        MessageDigest digest = newDigest();
        forEachCustomer(row -> update(digest,
                "C|" + row.id() + "|" + row.region() + "|" + row.segment() + "|" + row.createdAt()));
        forEachOrder(row -> update(digest,
                "O|" + row.id() + "|" + row.customerId() + "|" + row.status() + "|" + row.totalCents()
                        + "|" + row.placedAt()));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String line) {
        digest.update((line + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    private static final class Batcher<T> {
        private final Consumer<List<T>> sink;
        private List<T> buffer = new ArrayList<>(BATCH_SIZE);

        Batcher(Consumer<List<T>> sink) {
            this.sink = sink;
        }

        void add(T row) {
            buffer.add(row);
            if (buffer.size() == BATCH_SIZE) {
                flush();
            }
        }

        void flush() {
            if (!buffer.isEmpty()) {
                sink.accept(buffer);
                buffer = new ArrayList<>(BATCH_SIZE);
            }
        }
    }
}
