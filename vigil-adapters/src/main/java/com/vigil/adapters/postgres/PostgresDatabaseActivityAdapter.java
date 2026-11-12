package com.vigil.adapters.postgres;

import com.vigil.application.exception.TelemetryUnavailableException;
import com.vigil.application.port.out.interfaces.DatabaseActivityReader;
import com.vigil.application.port.out.interfaces.DatabaseActivitySnapshot;
import com.vigil.application.port.out.interfaces.PostgresCapabilityProbe;
import com.vigil.application.preflight.CapabilityCode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/** Reads aggregate activity only; query text, users, addresses and application names never leave PostgreSQL. */
public final class PostgresDatabaseActivityAdapter implements DatabaseActivityReader {
    static final String SQL="SELECT count(*) FILTER (WHERE pid<>pg_backend_pid() AND state='active'),"
            + " count(*) FILTER (WHERE pid<>pg_backend_pid() AND wait_event IS NOT NULL),"
            + " count(*) FILTER (WHERE pid<>pg_backend_pid() AND cardinality(pg_blocking_pids(pid))>0)"
            + " FROM pg_stat_activity";
    private final Supplier<Connection> connection; private final PostgresCapabilityProbe probe;
    public PostgresDatabaseActivityAdapter(Supplier<Connection> connection,PostgresCapabilityProbe probe){this.connection=Objects.requireNonNull(connection);this.probe=Objects.requireNonNull(probe);}
    @Override public DatabaseActivitySnapshot read(Instant observedAt){
        Objects.requireNonNull(observedAt); boolean full=probe.inspect().statsReadable();
        try(Connection c=connection.get()){
            c.setReadOnly(true);c.setAutoCommit(false);
            try(var s=c.createStatement()) {s.setQueryTimeout(5);try(var r=s.executeQuery(SQL)){
                if(!r.next())throw new SQLException("Activity aggregate returned no row");
                var snapshot=new DatabaseActivitySnapshot(observedAt,r.getLong(1),r.getLong(2),r.getLong(3),full);
                c.rollback();return snapshot;
            }}
        }catch(SQLException failure){throw new TelemetryUnavailableException("42501".equals(failure.getSQLState())?CapabilityCode.STATS_READABLE:CapabilityCode.CONNECTION,failure);}
    }
}
