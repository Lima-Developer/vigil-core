package health;

import com.vigil.application.configuration.VigilConfiguration;
import com.vigil.application.configuration.StorageConfiguration;
import com.vigil.application.port.out.interfaces.StorageFacts;
import com.vigil.application.health.CheckVigilHealth;
import com.vigil.application.health.HealthStatus;
import com.vigil.application.port.out.interfaces.Clock;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class CheckVigilHealthTest {

    @Test
    void shouldReturnHealthyStatusForValidConfiguration() {
        // Given
        Instant fixedInstant = Instant.parse("2026-08-26T12:00:00Z");
        Clock fixedClock = () -> fixedInstant;

        CheckVigilHealth healthCheck =
                new CheckVigilHealth(fixedClock, "0.1.0-SNAPSHOT",
                        path -> new StorageFacts(true, true, true, 256, "ok", null),
                        new StorageConfiguration(Path.of(System.getProperty("java.io.tmpdir"), "vigil.db").toAbsolutePath(), 256));

        VigilConfiguration configuration =
                new VigilConfiguration("local-postgres", "development");

        // When
        HealthStatus status = healthCheck.check(configuration);

        // Then
        assertAll(
                () -> assertTrue(status.healthy()),
                () -> assertEquals("0.1.0-SNAPSHOT", status.applicationVersion()),
                () -> assertEquals("local-postgres", status.targetName()),
                () -> assertEquals("development", status.environment()),
                () -> assertEquals(fixedInstant, status.checkedAt())
        );
    }
}
