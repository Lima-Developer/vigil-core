package com.vigil.adapters.postgres.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LaboratoryDatasetTest {

    private static final Pattern CPF_LIKE = Pattern.compile("\\d{11}");

    private static LaboratoryDataset small(long seed) {
        return new LaboratoryDataset(seed, 200, 3_000);
    }

    @Test
    void sameSeedProducesSameHash() {
        assertEquals(small(42).sha256(), small(42).sha256());
    }

    @Test
    void differentSeedProducesDifferentHash() {
        assertNotEquals(small(42).sha256(), small(43).sha256());
    }

    @Test
    void standardDatasetUsesDocumentedVolume() {
        LaboratoryDataset standard = LaboratoryDataset.standard();

        assertEquals(42L, standard.seed());
        assertEquals(20_000, standard.customerCount());
        assertEquals(300_000, standard.orderCount());
        assertEquals(5_000, LaboratoryDataset.BATCH_SIZE);
    }

    @Test
    void generatesExactlyTheConfiguredRows() {
        LaboratoryDataset dataset = small(42);
        List<LaboratoryDataset.CustomerRow> customers = new ArrayList<>();
        List<LaboratoryDataset.OrderRow> orders = new ArrayList<>();

        dataset.forEachCustomer(customers::add);
        dataset.forEachOrder(orders::add);

        assertEquals(200, customers.size());
        assertEquals(1, customers.getFirst().id());
        assertEquals(200, customers.getLast().id());
        assertEquals(3_000, orders.size());
        assertEquals(1, orders.getFirst().id());
        assertEquals(3_000, orders.getLast().id());
        assertTrue(orders.stream().allMatch(order -> order.customerId() >= 1 && order.customerId() <= 200));
    }

    @Test
    void sumTotalCentsMatchesGeneratedOrders() {
        LaboratoryDataset dataset = small(42);
        long[] expected = {0};
        dataset.forEachOrder(order -> expected[0] += order.totalCents());

        assertEquals(expected[0], dataset.sumTotalCents());
        assertTrue(expected[0] > 0);
    }

    @ParameterizedTest
    @CsvSource({"5000,5000", "5001,5000;1", "12345,5000;5000;2345", "1000,1000"})
    void splitsOrdersIntoBatchesWithPartialLastBatch(int orderCount, String expectedSizes) {
        LaboratoryDataset dataset = new LaboratoryDataset(42, 100, orderCount);
        List<Integer> sizes = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();

        dataset.forEachOrderBatch(batch -> {
            sizes.add(batch.size());
            batch.forEach(order -> ids.add(order.id()));
        });

        assertEquals(Arrays.stream(expectedSizes.split(";")).map(Integer::valueOf).toList(), sizes);
        assertEquals(orderCount, ids.size());
        assertEquals(orderCount, ids.stream().distinct().count());
    }

    @Test
    void splitsCustomersIntoBatchesWithPartialLastBatch() {
        LaboratoryDataset dataset = new LaboratoryDataset(42, 5_003, 100);
        List<Integer> sizes = new ArrayList<>();

        dataset.forEachCustomerBatch(batch -> sizes.add(batch.size()));

        assertEquals(List.of(5_000, 3), sizes);
    }

    @Test
    void textualValuesComeFromClosedVocabularyWithoutPii() {
        LaboratoryDataset dataset = small(42);
        List<String> values = new ArrayList<>();
        dataset.forEachCustomer(row -> {
            assertTrue(LaboratoryDataset.REGIONS.contains(row.region()));
            assertTrue(LaboratoryDataset.SEGMENTS.contains(row.segment()));
            values.add(row.region());
            values.add(row.segment());
        });
        dataset.forEachOrder(row -> {
            assertTrue(allStatuses().contains(row.status()));
            values.add(row.status());
        });

        assertFalse(values.isEmpty());
        assertPiiFree(values);
    }

    @Test
    void vocabulariesAreFreeOfPiiPatterns() {
        List<String> vocabulary = new ArrayList<>(LaboratoryDataset.REGIONS);
        vocabulary.addAll(LaboratoryDataset.SEGMENTS);
        vocabulary.addAll(allStatuses());

        assertPiiFree(vocabulary);
    }

    @Test
    void pendingRatioIsAboutHalfAPercent() {
        LaboratoryDataset dataset = new LaboratoryDataset(42, 2_000, 100_000);
        int[] pending = {0};
        dataset.forEachOrder(order -> {
            if (order.status().equals(LaboratoryDataset.STATUS_PENDING)) {
                pending[0]++;
            }
        });

        assertTrue(pending[0] >= 200 && pending[0] <= 800, "pending=" + pending[0]);
    }

    @Test
    void healthyCustomerHasExactlyFifteenOrders() {
        LaboratoryDataset dataset = small(42);
        int[] healthy = {0};
        dataset.forEachOrder(order -> {
            if (order.customerId() == LaboratoryDataset.HEALTHY_CUSTOMER_ID) {
                healthy[0]++;
            }
        });

        assertEquals(LaboratoryDataset.HEALTHY_CUSTOMER_ORDERS, healthy[0]);
    }

    @Test
    void rejectsVolumesTooSmallForTheWorkload() {
        assertThrows(IllegalArgumentException.class, () -> new LaboratoryDataset(42, 1, 100));
        assertThrows(IllegalArgumentException.class, () -> new LaboratoryDataset(42, 100, 14));
    }

    private static void assertPiiFree(List<String> values) {
        for (String value : values) {
            assertFalse(value.contains("@"), value);
            assertFalse(CPF_LIKE.matcher(value).find(), value);
        }
    }

    private static List<String> allStatuses() {
        List<String> statuses = new ArrayList<>(LaboratoryDataset.NON_PENDING_STATUSES);
        statuses.add(LaboratoryDataset.STATUS_PENDING);
        return statuses;
    }
}
