/*
 * Copyright 2020-2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openmrs.analytics.metrics;

import java.io.IOException;
import okhttp3.mockwebserver.MockWebServer;
import org.apache.beam.runners.direct.DirectRunner;
import org.apache.beam.runners.flink.FlinkRunner;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openmrs.analytics.MockUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@AutoConfigureObservability
@TestPropertySource("classpath:application-test.properties")
public class PipelineMetricsFactoryTest {

  @Autowired private PipelineMetricsFactory pipelineMetricsFactory;

  private static MockWebServer mockFhirServer;

  @BeforeAll
  public static void setUp() throws IOException {
    mockFhirServer = new MockWebServer();
    mockFhirServer.start(9091);
    mockFhirServer.enqueue(MockUtil.getMockResponse("data/fhir-metadata-sample.json"));
    mockFhirServer.enqueue(MockUtil.getMockResponse("data/patient-count-sample.json"));
  }

  @Test
  public void testFlinkRunner() {
    PipelineMetrics pipelineMetrics = pipelineMetricsFactory.getPipelineMetrics(FlinkRunner.class);
    MatcherAssert.assertThat(pipelineMetrics, Matchers.instanceOf(FlinkPipelineMetrics.class));
  }

  @Test
  public void testDirectRunner() {
    PipelineMetrics pipelineMetrics = pipelineMetricsFactory.getPipelineMetrics(DirectRunner.class);
    MatcherAssert.assertThat(pipelineMetrics, Matchers.nullValue());
  }

  @AfterAll
  public static void tearDown() throws IOException {
    mockFhirServer.shutdown();
  }
}
