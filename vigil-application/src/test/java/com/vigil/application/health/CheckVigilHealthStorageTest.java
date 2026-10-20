package com.vigil.application.health;

import com.vigil.application.configuration.StorageConfiguration;
import com.vigil.application.configuration.VigilConfiguration;
import com.vigil.application.port.out.interfaces.StorageFacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CheckVigilHealthStorageTest {
    private static final Path FILE = Path.of("/volume/vigil.db");
    private static final StorageConfiguration CONFIG = new StorageConfiguration(FILE, 256);

    static Stream<Arguments> unhealthyFacts() {
        return Stream.of(
                Arguments.of(new StorageFacts(false, true, true, 256, null, null), "inexistente"),
                Arguments.of(new StorageFacts(true, false, true, 256, null, null), "Diretório sem permissão"),
                Arguments.of(new StorageFacts(true, true, false, 256, null, null), "Arquivo do banco"),
                Arguments.of(new StorageFacts(true, true, true, 255, null, null), "Espaço insuficiente"),
                Arguments.of(new StorageFacts(true, true, true, 0, null, null), "Espaço insuficiente"),
                Arguments.of(new StorageFacts(true, true, true, 256, "corrupt", null), "Integridade"),
                Arguments.of(new StorageFacts(true, true, true, 256, null, "falha de abertura"), "falha de abertura")
        );
    }

    @ParameterizedTest
    @MethodSource("unhealthyFacts")
    void classifiesEachStorageFailure(StorageFacts facts, String message) {
        var status = check(facts);
        assertFalse(status.healthy());
        assertFalse(status.storage().ok());
        assertTrue(status.storage().problems().stream().anyMatch(problem -> problem.contains(message)));
    }

    @ParameterizedTest
    @MethodSource("healthySpaces")
    void acceptsSpaceAtOrAboveMinimum(long usableBytes) {
        assertTrue(check(new StorageFacts(true, true, true, usableBytes, "ok", null)).healthy());
    }

    static Stream<Long> healthySpaces() {
        return Stream.of(256L, 257L);
    }

    @Test
    void convertsProbeExceptionToUnhealthyWithoutWriting() {
        var calls = new AtomicInteger();
        var checker = new CheckVigilHealth(() -> Instant.EPOCH, "test", path -> {
            assertEquals(FILE, path);
            calls.incrementAndGet();
            throw new IllegalStateException("volume indisponível");
        }, CONFIG);
        var status = checker.check(new VigilConfiguration("target", "test"));
        assertFalse(status.healthy());
        assertEquals(1, calls.get());
        assertTrue(status.storage().problems().getFirst().contains("Falha ao verificar armazenamento"));
        assertFalse(status.storage().problems().getFirst().contains("volume indisponível"));
    }

    @Test
    void reportsOnlyMissingDirectoryWhenVolumeIsAbsent() {
        var status = check(new StorageFacts(false, false, false, -1, null, null));
        assertEquals(1, status.storage().problems().size());
        assertTrue(status.storage().problems().getFirst().contains("inexistente"));
    }

    @Test
    void reportsUnknownSpaceWithoutInsufficientSpaceMessage() {
        var status = check(new StorageFacts(true, true, true, -1, null, "Falha ao medir espaço"));
        assertEquals(1, status.storage().problems().size());
    }

    @Test
    void rejectsInvalidStorageConfiguration() {
        assertThrows(NullPointerException.class, () -> new StorageConfiguration(null, 1));
        assertThrows(IllegalArgumentException.class, () -> new StorageConfiguration(Path.of("relative.db"), 1));
        assertThrows(IllegalArgumentException.class, () -> new StorageConfiguration(FILE, 0));
    }

    private static HealthStatus check(StorageFacts facts) {
        return new CheckVigilHealth(() -> Instant.EPOCH, "test", path -> facts, CONFIG)
                .check(new VigilConfiguration("target", "test"));
    }
}
