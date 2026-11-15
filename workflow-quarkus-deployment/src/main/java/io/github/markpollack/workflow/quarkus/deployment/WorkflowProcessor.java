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
package io.github.markpollack.workflow.quarkus.deployment;

import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.quarkus.WorkflowProducer;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import org.jboss.jandex.DotName;

/**
 * Registers the workflow producer, and keeps every {@code Step} bean, since the runtime
 * looks steps up by name rather than injecting them.
 */
class WorkflowProcessor {

	private static final String FEATURE = "agent-workflow";

	@BuildStep
	FeatureBuildItem feature() {
		return new FeatureBuildItem(FEATURE);
	}

	@BuildStep
	void beans(BuildProducer<AdditionalBeanBuildItem> beans, BuildProducer<UnremovableBeanBuildItem> unremovable) {
		beans.produce(AdditionalBeanBuildItem.unremovableOf(WorkflowProducer.class));
		unremovable.produce(UnremovableBeanBuildItem.beanTypes(DotName.createSimple(Step.class.getName())));
	}

}
