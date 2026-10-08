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
package io.github.markpollack.workflow.flows;

/**
 * Legacy in-memory loop exception, retained for existing callers. Raised when a loop completes all iterations
 * without the {@code until} condition being satisfied. Durable workflow loops are not supported yet.
 */
public class MaxIterationsExceededException extends RuntimeException {

    private final int maxIterations;

    public MaxIterationsExceededException(int maxIterations) {
        super("Loop did not satisfy termination condition within " + maxIterations + " iteration(s)");
        this.maxIterations = maxIterations;
    }

    /**
     * Returns the maximum iterations limit that was exceeded.
     *
     * @return the max iterations value
     */
    public int maxIterations() {
        return maxIterations;
    }
}
