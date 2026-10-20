package com.vigil.application.health;

import com.vigil.application.configuration.VigilConfiguration;
import com.vigil.application.configuration.StorageConfiguration;
import com.vigil.application.port.out.interfaces.Clock;
import com.vigil.application.port.out.interfaces.StorageFacts;
import com.vigil.application.port.out.interfaces.StorageProbe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;

public final class CheckVigilHealth {

    private final Clock clock;
    private final String applicationVersion;
    private final StorageProbe storageProbe;
    private final StorageConfiguration storageConfiguration;

    public CheckVigilHealth(Clock clock, String applicationVersion, StorageProbe storageProbe,
                            StorageConfiguration storageConfiguration) {
        this.clock = Objects.requireNonNull(clock, "Clock não pode ser nulo");
        this.storageProbe = Objects.requireNonNull(storageProbe, "StorageProbe não pode ser nulo");
        this.storageConfiguration = Objects.requireNonNull(storageConfiguration, "StorageConfiguration não pode ser nula");

        if (applicationVersion == null || applicationVersion.isBlank()) {
            throw new IllegalArgumentException("applicationVersion não pode ser nula ou vazia");
        }

        this.applicationVersion = applicationVersion;
    }

    public HealthStatus check(VigilConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration não pode ser nula");

        Instant checkedAt = Objects.requireNonNull(
                clock.now(),
                "Clock não pode retornar um instante nulo"
        );

        StorageHealth storage = inspectStorage();
        return new HealthStatus(
                applicationVersion,
                configuration.targetName(),
                configuration.environment(),
                checkedAt,
                storage.ok(),
                storage
        );
    }

    private StorageHealth inspectStorage() {
        var problems = new ArrayList<String>();
        var path = storageConfiguration.databaseFile();
        try {
            StorageFacts facts = Objects.requireNonNull(storageProbe.inspect(path), "StorageProbe retornou nulo");
            if (!facts.directoryExists()) {
                problems.add("Diretório do banco inexistente: " + path.getParent() + ". Monte um volume persistente.");
                return new StorageHealth(false, problems);
            }
            if (!facts.directoryWritable()) {
                problems.add("Diretório sem permissão de escrita: " + path.getParent() + ". Ajuste dono e permissões do volume.");
            }
            if (!facts.fileWritable()) {
                problems.add("Arquivo do banco sem permissão de escrita: " + path + ". Ajuste dono e permissões.");
            }
            if (facts.usableBytes() >= 0 && facts.usableBytes() < storageConfiguration.minimumFreeBytes()) {
                problems.add("Espaço insuficiente em " + path.getParent() + ": mínimo "
                        + storageConfiguration.minimumFreeBytes() + " bytes; disponíveis " + facts.usableBytes() + ". Libere espaço.");
            }
            if (facts.integrityResult() != null && !"ok".equalsIgnoreCase(facts.integrityResult())) {
                problems.add("Integridade SQLite inválida em " + path + ". Restaure um backup íntegro.");
            }
            if (facts.failure() != null) {
                problems.add(facts.failure());
            }
        } catch (Exception failure) {
            problems.add("Falha ao verificar armazenamento em " + path
                    + ". Verifique o volume e a integridade do banco.");
        }
        return new StorageHealth(problems.isEmpty(), problems);
    }
}
