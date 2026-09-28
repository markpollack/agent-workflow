package io.github.markpollack.workflow.batch;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import io.github.markpollack.workflow.flows.workflow.StepTransition;
import io.github.markpollack.workflow.flows.workflow.TraceRecorder;
import io.github.markpollack.workflow.patterns.graph.NodeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JDBC-backed {@link TraceRecorder} that persists {@link StepTransition} records to
 * a {@code step_transitions} table. Shares the same {@code DataSource} as the
 * durable runtime checkpoints.
 *
 * <p>
 * The table is auto-created on first use if it does not exist.
 */
public final class JdbcTraceRecorder implements TraceRecorder {

	private static final Logger log = LoggerFactory.getLogger(JdbcTraceRecorder.class);

	private static final String CREATE_TABLE = """
			CREATE TABLE IF NOT EXISTS step_transitions (
			    id BIGINT AUTO_INCREMENT PRIMARY KEY,
			    run_id VARCHAR(255) NOT NULL,
			    workflow_name VARCHAR(255) NOT NULL,
			    from_step VARCHAR(255),
			    to_step VARCHAR(255) NOT NULL,
			    timestamp TIMESTAMP NOT NULL,
			    duration_ms BIGINT NOT NULL,
			    tokens_used BIGINT NOT NULL,
			    cost_usd DOUBLE NOT NULL,
			    node_type VARCHAR(20) NOT NULL,
			    label VARCHAR(255),
			    trace_path VARCHAR(1024)
			)
			""";

	private static final String INSERT = """
			INSERT INTO step_transitions (run_id, workflow_name, from_step, to_step, timestamp,
			    duration_ms, tokens_used, cost_usd, node_type, label, trace_path)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""";

	private static final String SELECT_BY_RUN = """
			SELECT run_id, workflow_name, from_step, to_step, timestamp,
			    duration_ms, tokens_used, cost_usd, node_type, label, trace_path
			FROM step_transitions WHERE run_id = ? ORDER BY id
			""";

	private final JdbcTemplate jdbc;

	private volatile boolean tableCreated;

	public JdbcTraceRecorder(JdbcTemplate jdbcTemplate) {
		this.jdbc = jdbcTemplate;
	}

	public JdbcTraceRecorder(DataSource dataSource) {
		this(new JdbcTemplate(dataSource));
	}

	@Override
	public void record(StepTransition transition) {
		ensureTable();
		jdbc.update(INSERT,
				transition.workflowRunId(),
				transition.workflowName(),
				transition.fromStep(),
				transition.toStep(),
				Timestamp.from(transition.timestamp()),
				transition.stepDuration().toMillis(),
				transition.tokensUsed(),
				transition.costUsd(),
				transition.nodeType().name(),
				transition.label(),
				transition.tracePath());
	}

	@Override
	public List<StepTransition> getTrace(String workflowRunId) {
		ensureTable();
		return jdbc.query(SELECT_BY_RUN, (rs, rowNum) -> new StepTransition(
				rs.getString("run_id"),
				rs.getString("workflow_name"),
				rs.getString("from_step"),
				rs.getString("to_step"),
				rs.getTimestamp("timestamp").toInstant(),
				java.time.Duration.ofMillis(rs.getLong("duration_ms")),
				rs.getLong("tokens_used"),
				rs.getDouble("cost_usd"),
				NodeType.valueOf(rs.getString("node_type")),
				rs.getString("label"),
				rs.getString("trace_path")
		), workflowRunId);
	}

	private void ensureTable() {
		if (!tableCreated) {
			synchronized (this) {
				if (!tableCreated) {
					jdbc.execute(CREATE_TABLE);
					tableCreated = true;
				}
			}
		}
	}

}
