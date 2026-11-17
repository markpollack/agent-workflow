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
package io.github.markpollack.workflow.agents;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The subagents that {@link AgentLoop} registers include SmartWebFetchTool, which turns a
 * fetched page into Markdown with a default {@link FlexmarkHtmlConverter}. Flexmark parses
 * the HTML with jsoup. This checks, offline, that the conversion still works on the jsoup
 * version this module manages, which is newer than the one flexmark was built against.
 */
class WebFetchHtmlConversionTest {

	private static final String PAGE = """
			<!DOCTYPE html>
			<html lang="en">
			<head>
			  <meta charset="utf-8">
			  <title>Release notes</title>
			  <style>body { font-family: sans-serif; }</style>
			  <script>console.log("not content");</script>
			</head>
			<body>
			  <nav><a href="/">Home</a></nav>
			  <main>
			    <article>
			      <h1>Agent Workflow</h1>
			      <p>A <strong>typed</strong> workflow library with <em>durable</em> recovery &amp; bounded fan-out.</p>
			      <h2>Install</h2>
			      <pre><code>./mvnw clean verify</code></pre>
			      <ul>
			        <li>Spring Boot</li>
			        <li>Quarkus</li>
			        <li>Micronaut <a href="https://micronaut.io/">site</a></li>
			      </ul>
			      <ol><li>first</li><li>second</li></ol>
			      <table>
			        <thead><tr><th>Module</th><th>Role</th></tr></thead>
			        <tbody><tr><td>workflow-batch</td><td>runtime</td></tr></tbody>
			      </table>
			      <blockquote><p>Quoted text</p></blockquote>
			      <div><div><div><p>Nested paragraph</p></div></div></div>
			    </article>
			  </main>
			</body>
			</html>
			""";

	/**
	 * The Markdown recorded with jsoup 1.15.4, the version flexmark-html2md-converter
	 * 0.64.8 brings. The patched jsoup must produce exactly the same output.
	 */
	private static final String MARKDOWN = """
			[Home](/)

			Agent Workflow
			==============

			A **typed** workflow library with *durable* recovery \\& bounded fan-out.

			Install
			-------

			    ./mvnw clean verify

			* Spring Boot
			* Quarkus
			* Micronaut [site](https://micronaut.io/)

			1. first
			2. second

			|     Module     |  Role   |
			|----------------|---------|
			| workflow-batch | runtime |

			Quoted text\s\s
			Nested paragraph
			""";

	@Test
	void convertsARepresentativePageExactlyAsBefore() {
		String markdown = FlexmarkHtmlConverter.builder().build().convert(PAGE);

		assertThat(markdown).isEqualTo(MARKDOWN).doesNotContain("console.log").doesNotContain("font-family");
	}

	@Test
	void runsOnThePatchedJsoup() throws IOException {
		Properties pom = new Properties();
		try (InputStream in = org.jsoup.Jsoup.class
			.getResourceAsStream("/META-INF/maven/org.jsoup/jsoup/pom.properties")) {
			assertThat(in).as("jsoup pom.properties").isNotNull();
			pom.load(in);
		}
		String[] version = pom.getProperty("version").split("\\.");
		int number = Integer.parseInt(version[0]) * 10000 + Integer.parseInt(version[1]) * 100
				+ Integer.parseInt(version[2]);
		// 1.23.2 fixes CVE-2026-75140; flexmark-html2md-converter 0.64.8 would bring 1.15.4.
		assertThat(number).isGreaterThanOrEqualTo(12302);
	}

}
