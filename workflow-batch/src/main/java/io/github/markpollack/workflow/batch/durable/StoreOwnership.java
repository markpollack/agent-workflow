package io.github.markpollack.workflow.batch.durable;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;

/** Process-held ownership of one canonical local database, independent of run progress. */
final class StoreOwnership implements AutoCloseable {
    private final Path database;
    private final FileChannel channel;
    private final FileLock lock;

    static StoreOwnership acquire(Path supplied) {
        FileChannel channel = null;
        try {
            Path absolute = supplied.toAbsolutePath().normalize();
            if (absolute.toString().contains(";")) throw new IllegalArgumentException("database path cannot contain semicolon");
            Files.createDirectories(absolute.getParent());
            Path canonical = absolute.getParent().toRealPath().resolve(absolute.getFileName());
            Path data = Path.of(canonical + ".mv.db");
            if (Files.exists(data)) {
                Path realData = data.toRealPath();
                String name = realData.getFileName().toString();
                if (!name.endsWith(".mv.db")) throw new IllegalArgumentException("database symlink must target an H2 .mv.db file");
                canonical = realData.resolveSibling(name.substring(0, name.length() - 6));
            }
            if (canonical.toString().contains(";")) throw new IllegalArgumentException("canonical database path cannot contain semicolon");
            channel = FileChannel.open(Path.of(canonical + ".workflow-owner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) throw new WorkflowRefusal("OWNER_ACTIVE", "another runtime owns this database");
            return new StoreOwnership(canonical, channel, lock);
        } catch (Exception ex) {
            if (channel != null) try { channel.close(); } catch (IOException close) { ex.addSuppressed(close); }
            if (ex instanceof OverlappingFileLockException) throw new WorkflowRefusal("OWNER_ACTIVE", "another runtime owns this database", ex);
            if (ex instanceof RuntimeException runtime) throw runtime;
            throw new WorkflowRefusal("OWNER_UNAVAILABLE", "cannot acquire local database ownership", ex);
        }
    }
    private StoreOwnership(Path database, FileChannel channel, FileLock lock) {
        this.database = database; this.channel = channel; this.lock = lock;
    }
    Path database() { return database; }
    @Override public void close() {
        try { if (lock.isValid()) lock.release(); channel.close(); }
        catch (IOException ex) { throw new WorkflowRefusal("OWNER_CLOSE", "cannot release database ownership", ex); }
    }
}
