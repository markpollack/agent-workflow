package io.github.markpollack.workflow.batch.durable;

import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.*;

public final class StoreTestSupport {
    private static final AtomicLong CLOCK=new AtomicLong();
    public static long now() { return CLOCK.get(); }
    static void time(long now) { CLOCK.set(now); }
    static Connection connect(Path file) throws SQLException {
        return DriverManager.getConnection("jdbc:h2:file:"+file.toAbsolutePath()+";WRITE_DELAY=0;LOCK_TIMEOUT=10000;DB_CLOSE_ON_EXIT=FALSE","sa","");
    }
    static void controlledClock(Path file,long time) throws Exception {
        CLOCK.set(time);
        try(var c=connect(file);var s=c.createStatement()) {
            s.execute("DROP ALIAS AW_CLOCK");
            s.execute("CREATE ALIAS AW_CLOCK FOR 'io.github.markpollack.workflow.batch.durable.StoreTestSupport.now'");
        }
    }
    static String state(Path file,String run) throws Exception {
        try(var c=connect(file);var s=c.prepareStatement("SELECT state FROM aw_run WHERE id=?")) {
            s.setString(1,run);try(var rows=s.executeQuery()) { if(!rows.next()) return "{}";return rows.getString(1); }
        }
    }
    static void evidence(Path file,String run,String name) throws Exception {
        Path target=Path.of("target/durable-evidence/races",name+".json");Files.createDirectories(target.getParent());
        Files.writeString(target,state(file,run));
    }
    static void mutate(Path file,String run,Consumer<JsonNode> mutate) throws Exception {
        ObjectMapper mapper=new ObjectMapper();JsonNode state=mapper.readTree(state(file,run));mutate.accept(state);
        try(var c=connect(file);var s=c.prepareStatement("UPDATE aw_run SET state=? WHERE id=?")) {
            s.setString(1,mapper.writeValueAsString(state));s.setString(2,run);s.executeUpdate();
        }
    }
    static void await(Path file) throws Exception {
        long end=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
        while(!Files.exists(file)) { if(System.nanoTime()>end) throw new AssertionError("marker timeout: "+file);Thread.sleep(5); }
    }
}
