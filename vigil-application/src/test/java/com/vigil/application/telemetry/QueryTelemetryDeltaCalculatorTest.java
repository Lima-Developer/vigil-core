package com.vigil.application.telemetry;

import com.vigil.application.port.out.interfaces.CumulativeQueryCounters;
import com.vigil.application.port.out.interfaces.TelemetryCollection;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueryTelemetryDeltaCalculatorTest {
    private static final Instant A=Instant.parse("2026-11-12T10:00:00Z"), B=A.plusSeconds(30), RESET=A.minusSeconds(60);
    private final QueryTelemetryDeltaCalculator calculator=new QueryTelemetryDeltaCalculator();
    private CumulativeQueryCounters counter(long id,long calls,String ms,long rows) {
        return new CumulativeQueryCounters(1,2,id,true,calls,new BigDecimal(ms),rows,calls*2,calls,calls/2,calls/3);
    }
    private TelemetryCollection at(Instant at,long dealloc,boolean full,int skipped,CumulativeQueryCounters... counters) {
        return new TelemetryCollection(at,Optional.of(RESET),dealloc,full,List.of(counters),skipped);
    }

    @Test void firstSampleWarmsUpAndNeverUsesCumulativeValuesAsDeltas() {
        assertEquals("FIRST_SAMPLE",calculator.calculate(null,at(A,0,true,0,counter(1,99,"1000",20))).reason());
    }
    @Test void aggregatesOnlyMonotonicDifferencesAndWeightsMeanByCalls() {
        var result=calculator.calculate(at(A,0,true,0,counter(1,10,"100",40),counter(2,20,"1000",50)),
                at(B,0,true,0,counter(1,14,"140",48),counter(2,21,"1100",52)));
        assertEquals("available",result.state()); assertEquals(5,result.delta().calls());
        assertEquals(new BigDecimal("140"),result.delta().executionMillis());
        assertEquals(new BigDecimal("28.000000"),result.delta().meanExecutionMillis());
        assertEquals("measured",result.delta().quality());
    }
    @Test void zeroCallsHaveNoMeanAndEntryChurnIsPartial() {
        var result=calculator.calculate(at(A,0,true,0,counter(1,10,"100",40)),
                at(B,0,true,0,counter(1,10,"100",40),counter(2,1,"2",1)));
        assertNull(result.delta().meanExecutionMillis()); assertEquals("partial",result.delta().quality());
    }
    @Test void rejectsResetEvictionRegressionAndNonMonotonicTime() {
        var baseline=at(A,0,true,0,counter(1,10,"100",40));
        var reset=new TelemetryCollection(B,Optional.of(RESET.plusSeconds(1)),0,true,List.of(counter(1,11,"101",41)),0);
        assertEquals("STATS_RESET_CHANGED",calculator.calculate(baseline,reset).reason());
        assertEquals("ENTRIES_DEALLOCATED",calculator.calculate(baseline,at(B,1,true,0,counter(1,11,"101",41))).reason());
        assertEquals("COUNTER_REGRESSION",calculator.calculate(baseline,at(B,0,true,0,counter(1,9,"90",39))).reason());
        assertEquals("NON_MONOTONIC_TIME",calculator.calculate(baseline,at(A,0,true,0,counter(1,11,"101",41))).reason());
    }
}
