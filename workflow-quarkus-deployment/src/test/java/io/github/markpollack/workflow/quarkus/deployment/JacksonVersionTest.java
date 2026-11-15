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

import com.fasterxml.jackson.core.Version;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Quarkus BOM manages Jackson 2.21.6, below the 2.22.3 floor. The extension imports
 * the Jackson BOMs first, so its build and tests resolve the 2.22.3 and 3.2.3 floors or
 * later.
 */
class JacksonVersionTest {

	@Test
	void jackson2IsAtLeastTheFloor() {
		assertThat(number(com.fasterxml.jackson.core.json.PackageVersion.VERSION)).isGreaterThanOrEqualTo(22203);
		assertThat(number(com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION)).isGreaterThanOrEqualTo(22203);
	}

	@Test
	void jackson3IsAtLeastTheFloor() {
		assertThat(number3(tools.jackson.core.json.PackageVersion.VERSION)).isGreaterThanOrEqualTo(30203);
		assertThat(number3(tools.jackson.databind.cfg.PackageVersion.VERSION)).isGreaterThanOrEqualTo(30203);
	}

	/** 2.22.3 as 22203: the numbers only, since a version comparison also compares ids. */
	private static int number(Version version) {
		return version.getMajorVersion() * 10000 + version.getMinorVersion() * 100 + version.getPatchLevel();
	}

	private static int number3(tools.jackson.core.Version version) {
		return version.getMajorVersion() * 10000 + version.getMinorVersion() * 100 + version.getPatchLevel();
	}

}
