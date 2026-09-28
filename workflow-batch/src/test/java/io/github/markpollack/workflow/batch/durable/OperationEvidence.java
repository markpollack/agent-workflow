package io.github.markpollack.workflow.batch.durable;

import java.nio.file.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import io.github.markpollack.workflow.flows.StepContext;

public final class OperationEvidence {

	private OperationEvidence() {
	}

	public static void count(Map<String, String> configuration, String operation, StepContext context, String input) {
		try {
			String directory = configuration.get("evidence");
			if (directory != null) {
				Path dir = Path.of(directory);
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
