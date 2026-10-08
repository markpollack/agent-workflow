/*
 * Copyright 2024-2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://mariadb.com/bsl11/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.markpollack.workflow.flows.workflow;

/**
 * Legacy exception carrying an abort result.
 *
 * <p>
 * Unlike {@link WorkflowTerminatedException} which returns {@code null},
 * {@code WorkflowAbortException} carries a result object that becomes the workflow
 * output. Use this when a step detects an unrecoverable condition but can produce a
 * meaningful failure result (e.g., a typed error response rather than an exception).
 *
 * <p>
 * Durable Steps return typed business values instead. Throwing this exception from
 * a durable Step is an execution failure; its carried result is not accepted.
 *
 * @see WorkflowTerminatedException
 */
public class WorkflowAbortException extends WorkflowTerminatedException {

	private final Object result;

	/**
	 * Creates a new abort exception with the given result.
	 * @param result the workflow output on abort
	 */
	public WorkflowAbortException(Object result) {
		super(WorkflowStatus.FAILED, "Workflow aborted with result");
		this.result = result;
	}

	/**
	 * Creates a new abort exception with the given result and cause.
	 * @param result the workflow output on abort
	 * @param cause the underlying cause
	 */
	public WorkflowAbortException(Object result, Throwable cause) {
		super(WorkflowStatus.FAILED, "Workflow aborted with result: " + cause.getMessage());
		this.result = result;
		initCause(cause);
	}

	/**
	 * Returns the abort result, cast to the expected type.
	 * @param <T> the expected result type
	 * @return the result
	 */
	@SuppressWarnings("unchecked")
	public <T> T getResult() {
		return (T) result;
	}

}
