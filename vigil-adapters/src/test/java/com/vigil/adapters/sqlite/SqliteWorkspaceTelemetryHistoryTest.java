package com.vigil.adapters.sqlite;

import com.vigil.adapters.sqlite.migration.SqlMigrationRunner;
import com.vigil.application.port.out.interfaces.WorkspaceTelemetrySample;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SqliteWorkspaceTelemetryHistoryTest {
    @TempDir Path dir;
    private WorkspaceTelemetrySample sample(Instant at,long calls){return new WorkspaceTelemetrySample(at,at.minusSeconds(30),"lab","available","measured",calls,new BigDecimal("12.3"),new BigDecimal("1.23"),4L,5L,6L,0L,0L,2L,1L,0L,null);}
    @Test void persistsAndBoundsByAgeAndCount(){
        var factory=new SqliteConnectionFactory(dir.resolve("vigil.db"));try(var c=open(factory)){new SqlMigrationRunner(()->Instant.EPOCH).migrate(c);}catch(Exception e){throw new AssertionError(e);}
        var history=new SqliteWorkspaceTelemetryHistory(factory);var now=Instant.parse("2026-11-12T10:00:00Z");
        history.append(sample(now.minusSeconds(90),1),now.minusSeconds(100),10);
        history.append(sample(now.minusSeconds(60),2),now.minusSeconds(100),10);
        history.append(sample(now,3),now.minusSeconds(70),2);
        var rows=history.recent(10);assertEquals(2,rows.size());assertEquals(3,rows.getFirst().calls());assertEquals(2,rows.get(1).calls());
    }
    private static java.sql.Connection open(SqliteConnectionFactory f)throws Exception{return f.open();}
}
