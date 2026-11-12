package com.vigil.adapters.postgres;

import com.vigil.application.port.out.interfaces.PostgresPreflightFacts;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PostgresDatabaseActivityAdapterTest {
    @Test void readsOnlyAggregateCountsAndRollsBack() throws Exception {
        boolean[] rollback={false},closed={false};
        var result=(ResultSet)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ResultSet.class},(p,m,a)->switch(m.getName()){case "next"->true;case "getLong"->switch((Integer)a[0]){case 1->3L;case 2->2L;default->1L;};default->primitive(m.getReturnType());});
        var statement=(Statement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Statement.class},(p,m,a)->m.getName().equals("executeQuery")?result:primitive(m.getReturnType()));
        var connection=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->switch(m.getName()){case "createStatement"->statement;case "rollback"->{rollback[0]=true;yield null;}case "close"->{closed[0]=true;yield null;}default->primitive(m.getReturnType());});
        var facts=new PostgresPreflightFacts(160000,"db",true,true,true,true,"on","off");
        var snapshot=new PostgresDatabaseActivityAdapter(()->connection,()->facts).read(Instant.EPOCH);
        assertEquals(3,snapshot.activeSessions());assertEquals(2,snapshot.waitingSessions());assertEquals(1,snapshot.blockedSessions());
        assertTrue(snapshot.fullVisibility());assertTrue(rollback[0]);assertTrue(closed[0]);
    }
    private static Object primitive(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
}
