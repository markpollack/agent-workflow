package io.github.markpollack.workflow.batch.durable;

import java.nio.file.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import io.github.markpollack.workflow.flows.StepContext;

public final class OperationEvidence {

	private final Path directory;

	public OperationEvidence(Path directory) {
		this.directory = directory;
	}

	public void count(String operation, StepContext context, String input) {
		try {
			if (directory != null) {
				Path dir = directory;
				Files.createDirectories(dir);
				Files.writeString(
						dir.resolve(operation + ".calls"), context.invocationId() + " " + context.attemptId() + " "
								+ context.attemptNumber() + " " + input + "\n",
						StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}
		}
		catch (IOException failure) {
			throw new UncheckedIOException("Cannot record operation effect", failure);
		}
	}

}
