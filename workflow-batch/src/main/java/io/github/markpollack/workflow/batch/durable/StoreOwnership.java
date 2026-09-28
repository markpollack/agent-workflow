package io.github.markpollack.workflow.batch.durable;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;

/**
 * Exclusive process-held file ownership of one canonical H2 database path. Acquired
 * before JdbcRunStore opens and held until the store closes after caller drainage. This
 * is not per-run state, a renewable lease or a workflow scheduler. Process death releases
 * the OS lock; the sidecar file's continued existence does not imply ownership.
 */
final class StoreOwnership implements AutoCloseable {

	private final Path database;

	private final FileChannel channel;

	private final FileLock lock;

	/**
	 * Resolve parent/database symlinks and claim the sidecar OS lock without blocking for
	 * another owner. Validate the final path against JDBC option injection so the guard
	 * and store identify the same database. Creates parent directories/sidecar as needed.
	 * @param supplied base database path without .mv.db suffix
	 * @return ownership token exposing the canonical path for JdbcRunStore
	 * @throws WorkflowRefusal with OWNER_ACTIVE if any other handle/process holds the
	 * lock
	 * @throws IllegalArgumentException for unsupported or JDBC-ambiguous paths
	 */
	static StoreOwnership acquire(Path supplied) {
		FileChannel channel = null;
		try {
			Path absolute = supplied.toAbsolutePath().normalize();
			if (absolute.toString().contains(";"))
				throw new IllegalArgumentException("database path cannot contain semicolon");
			Files.createDirectories(absolute.getParent());
			Path canonical = absolute.getParent().toRealPath().resolve(absolute.getFileName());
			Path data = Path.of(canonical + ".mv.db");
			if (Files.exists(data)) {
				Path realData = data.toRealPath();
				String name = realData.getFileName().toString();
				if (!name.endsWith(".mv.db"))
					throw new IllegalArgumentException("database symlink must target an H2 .mv.db file");
				canonical = realData.resolveSibling(name.substring(0, name.length() - 6));
			}
			if (canonical.toString().contains(";"))
				throw new IllegalArgumentException("canonical database path cannot contain semicolon");
			channel = FileChannel.open(Path.of(canonical + ".workflow-owner.lock"), StandardOpenOption.CREATE,
					StandardOpenOption.WRITE);
			FileLock lock = channel.tryLock();
			if (lock == null)
				throw new WorkflowRefusal("OWNER_ACTIVE", "another runtime owns this database");
			return new StoreOwnership(canonical, channel, lock);
		}
		catch (Exception ex) {
			if (channel != null)
				try {
					channel.close();
				}
				catch (IOException close) {
					ex.addSuppressed(close);
				}
			if (ex instanceof OverlappingFileLockException)
				throw new WorkflowRefusal("OWNER_ACTIVE", "another runtime owns this database", ex);
			if (ex instanceof RuntimeException runtime)
				throw runtime;
			throw new WorkflowRefusal("OWNER_UNAVAILABLE", "cannot acquire local database ownership", ex);
		}
	}

	private StoreOwnership(Path database, FileChannel channel, FileLock lock) {
		this.database = database;
		this.channel = channel;
		this.lock = lock;
	}

	Path database() {
		return database;
	}

	@Override
	public void close() {
		try {
			if (lock.isValid())
				lock.release();
			channel.close();
		}
		catch (IOException ex) {
			throw new WorkflowRefusal("OWNER_CLOSE", "cannot release database ownership", ex);
		}
	}

}
