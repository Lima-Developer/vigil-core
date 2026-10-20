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
            String failure = Files.exists(directory)
                    ? "Caminho pai do banco não é um diretório: " + directory
                    + ". Informe um diretório de volume válido."
                    : null;
            return new StorageFacts(false, false, !Files.exists(databaseFile), -1, null, failure);
        }
        boolean writable = Files.isWritable(directory);
        String failure = null;
        if (writable) {
            try {
                Path probe = Files.createTempFile(directory, ".vigil-probe-", ".tmp");
                Files.delete(probe);
            } catch (IOException exception) {
                writable = false;
                failure = append(failure, "Não foi possível criar e remover sonda em " + directory + ". Ajuste permissões do volume.");
            }
        }
        boolean fileWritable = !Files.exists(databaseFile) || Files.isWritable(databaseFile);
        long usableBytes = -1;
        try {
            usableBytes = spaceProvider.usableBytes(directory);
        } catch (IOException exception) {
            failure = append(failure, "Não foi possível medir espaço em " + directory + ". Verifique o volume.");
        }
        String integrity = null;
        if (writable && fileWritable && Files.exists(databaseFile)) {
            try (var connection = new SqliteConnectionFactory(databaseFile).open();
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("PRAGMA quick_check")) {
                integrity = result.next() ? result.getString(1) : "sem resultado";
            } catch (SQLException exception) {
                failure = append(failure, "Não foi possível abrir ou verificar o banco em " + databaseFile
                        + ". Verifique o arquivo ou restaure um backup íntegro.");
            }
        }
        return new StorageFacts(true, writable, fileWritable, usableBytes, integrity, failure);
    }

    private static String append(String previous, String next) {
        return previous == null ? next : previous + " " + next;
    }

    @FunctionalInterface
    interface SpaceProvider {
        long usableBytes(Path directory) throws IOException;
    }
}
