package com.vigil.adapters.sqlite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Produces a standalone, transactionally consistent SQLite file. */
public final class SqliteBackup {
    private final SqliteConnectionFactory source;

    public SqliteBackup(SqliteConnectionFactory source) {
        this.source = Objects.requireNonNull(source);
    }

    public void backupTo(Path target) {
        Objects.requireNonNull(target, "Destino não pode ser nulo");
        Path destination = target.toAbsolutePath().normalize();
        if (Files.exists(destination)) {
            throw new SqliteBackupException("Destino de backup já existe: " + destination + ". Escolha outro caminho.");
        }
        Path staging = null;
        try {
            Path parent = destination.getParent();
            if (!Files.isDirectory(parent) || !Files.isWritable(parent)) {
                throw new SqliteBackupException("Diretório de backup indisponível: " + parent + ". Ajuste o volume.");
            }
            long sourceSize = Files.size(source.databaseFile());
            long required = sourceSize + Math.max(sourceSize / 10, 1024 * 1024);
            if (Files.getFileStore(parent).getUsableSpace() < required) {
                throw new SqliteBackupException("Espaço insuficiente para backup em " + parent
                        + ": necessário " + required + " bytes. Libere espaço.");
            }
            staging = Files.createTempFile(parent, ".vigil-backup-", ".db");
            Files.delete(staging);
            try (Connection connection = source.open();
                 var statement = connection.prepareStatement("VACUUM INTO ?")) {
                statement.setString(1, staging.toString());
                statement.execute();
            }
            verify(staging);
            Files.move(staging, destination);
        } catch (IOException | SQLException | RuntimeException failure) {
            if (staging != null) {
                try {
                    Files.deleteIfExists(staging);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (failure instanceof SqliteBackupException known) {
                throw known;
            }
            throw new SqliteBackupException("Falha ao criar backup em " + destination
                    + ". Verifique espaço, permissões e integridade da origem.", failure);
        }
    }

    private void verify(Path destination) throws SQLException {
        try (var original = source.open();
             var backup = new SqliteConnectionFactory(destination).open();
             var statement = backup.createStatement();
             var result = statement.executeQuery("PRAGMA quick_check")) {
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                throw new SqliteBackupException("Backup gerado com integridade inválida em " + destination);
            }
            for (String table : new String[]{"analysis_case", "case_event"}) {
                if (count(original, table) != count(backup, table)) {
                    throw new SqliteBackupException("Contagem divergente no backup em " + destination + ": " + table);
                }
            }
        }
    }

    private static long count(Connection connection, String table) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            return result.next() ? result.getLong(1) : -1;
        }
    }
}
