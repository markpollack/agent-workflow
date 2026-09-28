package io.github.markpollack.workflow.batch.durable;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Persistence boundary for one exclusively owned local H2 store. DurableWorkflows
 * supplies domain transitions; this class serializes them, assigns an eligibility time
 * and commits or rolls back their run/value/attempt/event changes together. Application
 * Step.execute must never run inside a Work callback. Typed value decoding/record
 * assembly may do so.
 * <p>
 * StoreOwnership must already be held. Existing formats are checked read-only before
 * writable initialization. The keeper connection retains store lifetime; each transaction
 * uses its own connection. RuntimeLifecycle drains callers before close().
 */
final class JdbcRunStore implements AutoCloseable {

	private static final int FORMAT = 4;

	private final String url;

	private final Connection keeper;

	private final ObjectMapper mapper = new ObjectMapper();

	JdbcRunStore(Path canonicalFile) {
		String base = "jdbc:h2:file:" + canonicalFile;
		url = base + ";WRITE_DELAY=0;LOCK_TIMEOUT=10000;DB_CLOSE_ON_EXIT=FALSE";
		boolean existing = Files.exists(Path.of(canonicalFile + ".mv.db"));
		if (existing) {
			// Refuse incompatible stores before any writable connection or schema
			// bootstrap.
			try (Connection read = DriverManager
				.getConnection(base + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;DB_CLOSE_ON_EXIT=FALSE", "sa", "")) {
				verifyFormat(read);
			}
			catch (WorkflowRefusal ex) {
				throw ex;
			}
			catch (Exception ex) {
				throw new WorkflowRefusal("STORE_UNAVAILABLE", "cannot inspect existing database", ex);
			}
		}
		keeper = initialize(existing);
	}

	private Connection initialize(boolean existing) {
		Connection opened = null;
		try {
			opened = DriverManager.getConnection(url, "sa", "");
			if (existing)
				verifyFormat(opened);
			else
				try (Statement s = opened.createStatement()) {
					s.execute("CREATE TABLE aw_transition_lock (id INT PRIMARY KEY, tick BIGINT NOT NULL)");
					s.execute("INSERT INTO aw_transition_lock VALUES(1,0)");
					s.execute("CREATE ALIAS AW_CLOCK FOR 'java.lang.System.currentTimeMillis'");
					s.execute("CREATE TABLE aw_run (id VARCHAR PRIMARY KEY, idempotency VARCHAR NOT NULL UNIQUE, "
							+ "revision BIGINT NOT NULL, status VARCHAR NOT NULL, deadline BIGINT NOT NULL, state CLOB NOT NULL)");
					s.execute("CREATE TABLE aw_store_format (id INT PRIMARY KEY, version INT NOT NULL)");
					s.execute("INSERT INTO aw_store_format VALUES(1," + FORMAT + ")");
				}
			return opened;
		}
		catch (Exception ex) {
			if (opened != null)
				try {
					opened.close();
				}
				catch (SQLException close) {
					ex.addSuppressed(close);
				}
			if (ex instanceof WorkflowRefusal refusal)
				throw refusal;
			throw new WorkflowRefusal("STORE_UNAVAILABLE", "cannot open durable database", ex);
		}
	}

	private com.fasterxml.jackson.databind.JsonNode checkedState(String json) throws Exception {
		var state = mapper.readTree(json);
		var format = state == null ? null : state.get("format");
		if (format == null || !format.isIntegralNumber() || !format.canConvertToInt() || format.intValue() != FORMAT)
			throw new WorkflowRefusal("STORE_FORMAT", "unsupported durable run format; migration is not available");
		return state;
	}

	private void verifyFormat(Connection connection) throws Exception {
		for (String table : List.of("AW_STORE_FORMAT", "AW_RUN", "AW_TRANSITION_LOCK")) {
			try (ResultSet tables = connection.getMetaData()
				.getTables(null, "PUBLIC", table, new String[] { "TABLE" })) {
				if (!tables.next())
					throw new WorkflowRefusal("STORE_FORMAT", "unsupported or incomplete store schema");
			}
		}
		try (Statement s = connection.createStatement();
				ResultSet rows = s.executeQuery("SELECT id,version FROM aw_store_format")) {
			if (!rows.next() || rows.getInt(1) != 1 || rows.getInt(2) != FORMAT || rows.next())
				throw new WorkflowRefusal("STORE_FORMAT", "unsupported store format");
		}
		try (Statement s = connection.createStatement(); ResultSet rows = s.executeQuery("SELECT state FROM aw_run")) {
			while (rows.next())
				checkedState(rows.getString(1));
		}
	}

	interface Work<T> {

		T run(Tx tx) throws Exception;

	}

	/**
	 * Run one atomic transition on the caller's Java thread. Lock the shared transition
	 * row, then sample the database-side clock clamped to its persisted high-water mark.
	 * All Work writes commit together; any exception/Error rolls back and the original
	 * unchecked failure propagates. Checked failures become cause-preserving
	 * STORE_TRANSACTION refusals. The sampled time is eligibility/ordering time, not the
	 * later physical fsync instant.
	 * @param work store-domain operation; never invoke application Steps or retain Tx
	 * from it
	 * @return the callback result only after successful commit, normally an immutable
	 * snapshot or detached dispatch rather than a mutable RunState
	 * @throws WorkflowRefusal for connection/transaction failures and callback refusals
	 */
	<T> T transaction(Work<T> work) {
		try (Connection c = DriverManager.getConnection(url, "sa", "")) {
			c.setAutoCommit(false);
			try {
				long previous;
				try (Statement s = c.createStatement();
						ResultSet rows = s.executeQuery("SELECT tick FROM aw_transition_lock WHERE id=1 FOR UPDATE")) {
					if (!rows.next())
						throw new WorkflowRefusal("STORE_CORRUPT", "transition clock missing");
					previous = rows.getLong(1);
				}
				long now;
				try (Statement s = c.createStatement(); ResultSet rows = s.executeQuery("CALL AW_CLOCK()")) {
					rows.next();
					now = Math.max(previous, rows.getLong(1));
				}
				try (PreparedStatement s = c.prepareStatement("UPDATE aw_transition_lock SET tick=? WHERE id=1")) {
					s.setLong(1, now);
					s.executeUpdate();
				}
				T result = work.run(new Tx(c, now));
				c.commit();
				return result;
			}
			catch (Throwable ex) {
				try {
					c.rollback();
				}
				catch (SQLException rollback) {
					ex.addSuppressed(rollback);
				}
				if (ex instanceof Error error)
					throw error;
				if (ex instanceof RuntimeException runtime)
					throw runtime;
				throw new WorkflowRefusal("STORE_TRANSACTION", "durable transition failed", ex);
			}
		}
		catch (SQLException ex) {
			throw new WorkflowRefusal("STORE_UNAVAILABLE", "durable transaction unavailable", ex);
		}
	}

	/**
	 * One transaction's connection, eligibility time and identity map. get/byKey/all
	 * reuse one mutable RunState per run and track its loaded revision. Objects must
	 * remain within this callback; return defensive snapshots/bytes or a detached
	 * dispatch to callers.
	 */
	final class Tx {

		final Connection c;

		final long now;

		private final Map<String, Long> loaded = new HashMap<>();

		private final Map<String, RunState> states = new HashMap<>();

		Tx(Connection c, long now) {
			this.c = c;
			this.now = now;
		}

		/**
		 * Read one run once within this transaction; reject unknown IDs and inconsistent
		 * saved headers.
		 */
		RunState get(String id) throws Exception {
			if (states.containsKey(id))
				return states.get(id);
			RunState state = find("id", id);
			if (state == null)
				throw new WorkflowRefusal("RUN_NOT_FOUND", "unknown durable run: " + id);
			return state;
		}

		RunState byKey(String key) throws Exception {
			return find("idempotency", key);
		}

		private RunState find(String column, String value) throws Exception {
			try (PreparedStatement s = c.prepareStatement("SELECT * FROM aw_run WHERE " + column + "=?")) {
				s.setString(1, value);
				try (ResultSet rows = s.executeQuery()) {
					return rows.next() ? read(rows) : null;
				}
			}
		}

		List<RunState> all() throws Exception {
			List<RunState> result = new ArrayList<>();
			try (Statement s = c.createStatement();
					ResultSet rows = s.executeQuery("SELECT * FROM aw_run ORDER BY id")) {
				while (rows.next())
					result.add(read(rows));
			}
			return result;
		}

		private RunState read(ResultSet rows) throws Exception {
			String id = rows.getString("id");
			if (states.containsKey(id))
				return states.get(id);
			RunState state = mapper.treeToValue(checkedState(rows.getString("state")), RunState.class);
			if (!state.id.equals(id) || !state.key.equals(rows.getString("idempotency"))
					|| state.revision != rows.getLong("revision") || !state.status.equals(rows.getString("status"))
					|| state.deadline != rows.getLong("deadline"))
				throw new WorkflowRefusal("STORE_CORRUPT", "state and transition columns disagree");
			loaded.put(id, state.revision);
			states.put(id, state);
			return state;
		}

		/**
		 * Insert a newly admitted aggregate and remember its revision. The outer
		 * transaction owns commit.
		 */
		void insert(RunState state) throws Exception {
			try (PreparedStatement s = c.prepareStatement(
					"INSERT INTO aw_run(id,idempotency,revision,status,deadline,state) VALUES(?,?,?,?,?,?)")) {
				s.setString(1, state.id);
				s.setString(2, state.key);
				s.setLong(3, state.revision);
				s.setString(4, state.status);
				s.setLong(5, state.deadline);
				s.setString(6, mapper.writeValueAsString(state));
				s.executeUpdate();
			}
			states.put(state.id, state);
			loaded.put(state.id, state.revision);
		}

		/**
		 * Materialize deadline expiry at this transaction's fixed time; merely inspecting
		 * may save a terminal outcome.
		 */
		void observe(RunState run) throws Exception {
			if (run.active() && now >= run.deadline)
				terminal(run, "FAILED", "DEADLINE_EXCEEDED", "absolute deadline reached", "store");
		}

		/**
		 * Record the first terminal outcome and dispose unresolved attempts together. A
		 * terminal run is never reopened.
		 */
		void terminal(RunState run, String status, String code, String message, String actor) throws Exception {
			if (!run.active())
				return;
			run.terminal(status, code, message, actor, now);
			save(run);
		}

		/**
		 * Write a previously read aggregate with an optimistic revision check in addition
		 * to the transition lock. Update the transaction's expected revision for another
		 * save in this same transition. Visibility/durability begins only when the outer
		 * callback commits.
		 */
		void save(RunState state) throws Exception {
			long previous = Objects.requireNonNull(loaded.get(state.id), "read required before update");
			state.revision = Math.incrementExact(previous);
			try (PreparedStatement s = c.prepareStatement(
					"UPDATE aw_run SET revision=?,status=?,deadline=?,state=? WHERE id=? AND revision=?")) {
				s.setLong(1, state.revision);
				s.setString(2, state.status);
				s.setLong(3, state.deadline);
				s.setString(4, mapper.writeValueAsString(state));
				s.setString(5, state.id);
				s.setLong(6, previous);
				if (s.executeUpdate() != 1)
					throw new WorkflowRefusal("STATE_CHANGED", "concurrent state transition refused");
			}
			loaded.put(state.id, state.revision);
		}

	}

	@Override
	public void close() {
		try {
			keeper.close();
		}
		catch (SQLException ex) {
			throw new WorkflowRefusal("STORE_CLOSE", "database close failed", ex);
		}
	}

}
