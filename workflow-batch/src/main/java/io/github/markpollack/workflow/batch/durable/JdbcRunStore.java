package io.github.markpollack.workflow.batch.durable;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** H2 file store with a serialized transition clock, immutable values and explicit fencing columns. */
final class JdbcRunStore implements AutoCloseable {
    private final String url;
    private final Connection keeper;
    private final ObjectMapper mapper=new ObjectMapper();

    JdbcRunStore(Path file) {
        String path=file.toAbsolutePath().normalize().toString();
        if(path.contains(";")) throw new IllegalArgumentException("database path cannot contain semicolon");
        url="jdbc:h2:file:"+path+";WRITE_DELAY=0;LOCK_TIMEOUT=10000;DB_CLOSE_ON_EXIT=FALSE";
        synchronized(JdbcRunStore.class) { keeper=initialize(); }
    }
    private Connection initialize() {
        Connection opened=null;
        try {
            opened=DriverManager.getConnection(url,"sa","");
            verifyFormats(opened);
            try(Statement s=opened.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS aw_transition_lock (id INT PRIMARY KEY, tick BIGINT NOT NULL)");
                s.execute("INSERT INTO aw_transition_lock SELECT 1,0 WHERE NOT EXISTS(SELECT 1 FROM aw_transition_lock WHERE id=1)");
                s.execute("CREATE ALIAS IF NOT EXISTS AW_CLOCK FOR 'java.lang.System.currentTimeMillis'");
                s.execute("CREATE TABLE IF NOT EXISTS aw_run (id VARCHAR PRIMARY KEY, idempotency VARCHAR NOT NULL UNIQUE, "
                        +"generation BIGINT NOT NULL, owner VARCHAR NOT NULL, status VARCHAR NOT NULL, deadline BIGINT NOT NULL, "
                        +"lease_until BIGINT NOT NULL, state CLOB NOT NULL)");
            }
            return opened;
        } catch(Exception ex) {
            if(opened!=null) try {opened.close();}catch(SQLException close){ex.addSuppressed(close);}
            if(ex instanceof WorkflowRefusal refusal) throw refusal;
            throw new WorkflowRefusal("STORE_UNAVAILABLE","cannot open durable database",ex);
        }
    }
    // Check the explicit header before schema bootstrap or binding defaults to a new state object.
    private com.fasterxml.jackson.databind.JsonNode checkedState(String json) throws Exception {
        var state=mapper.readTree(json);
        var format=state==null?null:state.get("format");
        if(format==null||!format.isIntegralNumber()||!format.canConvertToInt()||format.intValue()!=2)
            throw new WorkflowRefusal("STORE_FORMAT","unsupported durable run format; migration is not available");
        return state;
    }
    private void verifyFormats(Connection connection) throws Exception {
        try(ResultSet tables=connection.getMetaData().getTables(null,"PUBLIC","AW_RUN",new String[]{"TABLE"})) {
            if(!tables.next()) return;
        }
        try(Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT state FROM aw_run")) {
            while(rows.next()) checkedState(rows.getString(1));
        }
    }
    interface Work<T> { T run(Tx tx) throws Exception; }
    <T> T transaction(Work<T> work) {
        try(Connection c=DriverManager.getConnection(url,"sa","")) {
            c.setAutoCommit(false);
            try {
                long previous;
                try(Statement s=c.createStatement();ResultSet rows=s.executeQuery("SELECT tick FROM aw_transition_lock WHERE id=1 FOR UPDATE")) {
                    rows.next();previous=rows.getLong(1);
                }
                long now;
                try(Statement s=c.createStatement();ResultSet rows=s.executeQuery("CALL AW_CLOCK()")) { rows.next();now=Math.max(previous,rows.getLong(1)); }
                try(PreparedStatement s=c.prepareStatement("UPDATE aw_transition_lock SET tick=? WHERE id=1")) { s.setLong(1,now);s.executeUpdate(); }
                T result=work.run(new Tx(c,now));
                c.commit(); return result;
            } catch(Throwable ex) {
                try { c.rollback(); } catch(SQLException rollback) { ex.addSuppressed(rollback); }
                if(ex instanceof Error error) throw error;
                if(ex instanceof RuntimeException runtime) throw runtime;
                throw new WorkflowRefusal("STORE_TRANSACTION","durable transition failed",ex);
            }
        } catch(SQLException ex) { throw new WorkflowRefusal("STORE_UNAVAILABLE","durable transaction unavailable",ex); }
    }
    final class Tx {
        final Connection c;
        final long now;
        private final Map<String,Fence> loaded=new HashMap<>();
        Tx(Connection c,long now) { this.c=c;this.now=now; }
        RunState get(String id) throws Exception {
            RunState state=find("id",id);
            if(state==null) throw new WorkflowRefusal("RUN_NOT_FOUND","unknown durable run: "+id);
            return state;
        }
        RunState byKey(String key) throws Exception { return find("idempotency",key); }
        private RunState find(String column,String value) throws Exception {
            try(PreparedStatement s=c.prepareStatement("SELECT * FROM aw_run WHERE "+column+"=?")) {
                s.setString(1,value);
                try(ResultSet rows=s.executeQuery()) { return rows.next()?read(rows):null; }
            }
        }
        List<RunState> all() throws Exception {
            List<RunState> result=new ArrayList<>();
            try(Statement s=c.createStatement();ResultSet rows=s.executeQuery("SELECT * FROM aw_run ORDER BY id")) {
                while(rows.next()) result.add(read(rows));
            }
            return result;
        }
        private RunState read(ResultSet rows) throws Exception {
            RunState state=mapper.treeToValue(checkedState(rows.getString("state")),RunState.class);
            if(!state.id.equals(rows.getString("id"))||!state.key.equals(rows.getString("idempotency"))
                    ||state.generation!=rows.getLong("generation")||!state.owner.equals(rows.getString("owner"))
                    ||!state.status.equals(rows.getString("status"))||state.deadline!=rows.getLong("deadline")
                    ||state.leaseUntil!=rows.getLong("lease_until")) throw new WorkflowRefusal("STORE_CORRUPT","state and fencing columns disagree");
            loaded.put(state.id,new Fence(state.generation,state.owner,state.status,state.deadline,state.leaseUntil));
            return state;
        }
        void insert(RunState state) throws Exception {
            try(PreparedStatement s=c.prepareStatement("INSERT INTO aw_run(id,idempotency,generation,owner,status,deadline,lease_until,state) VALUES(?,?,?,?,?,?,?,?)")) {
                s.setString(1,state.id);s.setString(2,state.key);s.setLong(3,state.generation);s.setString(4,state.owner);
                s.setString(5,state.status);s.setLong(6,state.deadline);s.setLong(7,state.leaseUntil);s.setString(8,mapper.writeValueAsString(state));s.executeUpdate();
            }
        }
        void save(RunState state) throws Exception {
            Fence prior=Objects.requireNonNull(loaded.get(state.id),"read required before update");
            try(PreparedStatement s=c.prepareStatement("UPDATE aw_run SET generation=?,owner=?,status=?,deadline=?,lease_until=?,state=? "
                    +"WHERE id=? AND generation=? AND owner=? AND status=? AND deadline=? AND lease_until=?")) {
                s.setLong(1,state.generation);s.setString(2,state.owner);s.setString(3,state.status);s.setLong(4,state.deadline);
                s.setLong(5,state.leaseUntil);s.setString(6,mapper.writeValueAsString(state));s.setString(7,state.id);
                s.setLong(8,prior.generation);s.setString(9,prior.owner);s.setString(10,prior.status);s.setLong(11,prior.deadline);s.setLong(12,prior.lease);
                if(s.executeUpdate()!=1) throw new WorkflowRefusal("FENCED","storage ownership comparison failed");
            }
            loaded.put(state.id,new Fence(state.generation,state.owner,state.status,state.deadline,state.leaseUntil));
        }
    }
    private record Fence(long generation,String owner,String status,long deadline,long lease) {}
    @Override public void close() {
        try { keeper.close(); } catch(SQLException ex) { throw new WorkflowRefusal("STORE_CLOSE","database close failed",ex); }
    }
}
