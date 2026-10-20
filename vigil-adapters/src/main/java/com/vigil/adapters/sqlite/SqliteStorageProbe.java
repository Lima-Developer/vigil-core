package com.vigil.adapters.sqlite;

import com.vigil.application.port.out.interfaces.StorageFacts;
import com.vigil.application.port.out.interfaces.StorageProbe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;

public final class SqliteStorageProbe implements StorageProbe {
    private final SpaceProvider spaceProvider;

    public SqliteStorageProbe() {
        this(directory -> Files.getFileStore(directory).getUsableSpace());
    }

    SqliteStorageProbe(SpaceProvider spaceProvider) {
        this.spaceProvider = Objects.requireNonNull(spaceProvider);
    }

    @Override
    public StorageFacts inspect(Path databaseFile) {
        Objects.requireNonNull(databaseFile);
        Path directory = databaseFile.toAbsolutePath().getParent();
        if (!Files.isDirectory(directory)) {
            return new StorageFacts(false, false, !Files.exists(databaseFile), 0, null,
                    "Diretório do banco inexistente ou inválido: " + directory + ". Monte um volume persistente.");
        }
        boolean writable = Files.isWritable(directory);
        String failure = null;
        if (writable) {
            try {
                Path probe = Files.createTempFile(directory, ".vigil-probe-", ".tmp");
                Files.delete(probe);
            } catch (IOException exception) {
                writable = false;
                failure = "Não foi possível criar e remover sonda em " + directory + ". Ajuste permissões do volume.";
            }
        }
        boolean fileWritable = !Files.exists(databaseFile) || Files.isWritable(databaseFile);
        long usableBytes = 0;
        try {
            usableBytes = spaceProvider.usableBytes(directory);
        } catch (IOException exception) {
            failure = "Não foi possível medir espaço em " + directory + ". Verifique o volume.";
        }
        String integrity = null;
        if (writable && fileWritable && Files.exists(databaseFile)) {
            try (var connection = new SqliteConnectionFactory(databaseFile).open();
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("PRAGMA quick_check")) {
                integrity = result.next() ? result.getString(1) : "sem resultado";
            } catch (SQLException exception) {
                failure = "Não foi possível abrir ou verificar o banco em " + databaseFile
                        + ". Verifique o arquivo ou restaure um backup íntegro.";
            }
        }
        return new StorageFacts(true, writable, fileWritable, usableBytes, integrity, failure);
    }

    @FunctionalInterface
    interface SpaceProvider {
        long usableBytes(Path directory) throws IOException;
    }
}
