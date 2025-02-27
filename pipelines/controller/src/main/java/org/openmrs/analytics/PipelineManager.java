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
package org.openmrs.analytics;

import ca.uhn.fhir.context.FhirContext;
import com.cerner.bunsen.FhirContexts;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import io.micrometer.core.instrument.MeterRegistry;
import java.beans.PropertyVetoException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import lombok.Data;
import org.apache.beam.runners.flink.FlinkPipelineOptions;
import org.apache.beam.runners.flink.FlinkRunner;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.io.FileSystems;
import org.apache.beam.sdk.io.fs.ResolveOptions.StandardResolveOptions;
import org.apache.beam.sdk.io.fs.ResourceId;
import org.apache.beam.sdk.metrics.GaugeResult;
import org.apache.beam.sdk.metrics.MetricQueryResults;
import org.apache.beam.sdk.metrics.MetricResult;
import org.apache.beam.sdk.metrics.MetricResults;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.openmrs.analytics.metrics.PipelineMetrics;
import org.openmrs.analytics.metrics.PipelineMetricsFactory;
import org.openmrs.analytics.model.DatabaseConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * This is the class responsible for managing everything pipeline related. It is supposed to be a
 * singleton (and thread-safe), hence the private constructor and builder method. We rely on Spring
 * for the singleton constraint as it is the default (instead of a private constructor and a static
 * instance which does not play with Spring's dependency injection).
 */
@EnableScheduling
@Component
public class PipelineManager implements ApplicationListener<ApplicationReadyEvent> {

  private static final Logger logger = LoggerFactory.getLogger(PipelineManager.class.getName());

  @Autowired private DataProperties dataProperties;

  @Autowired private PipelineMetricsFactory pipelineMetricsFactory;

  @Autowired private DwhFilesManager dwhFilesManager;

  @Autowired private MeterRegistry meterRegistry;

  private PipelineThread currentPipeline;

  private DwhFiles currentDwh;

  private LocalDateTime lastRunEnd;

  private CronExpression cron;

  // TODO expose this in the web-UI
  private LastRunStatus lastRunStatus = LastRunStatus.NOT_RUN;

  private DwhRunDetails lastRunDetails;

  private static final String ERROR_FILE_NAME = "error.log";
  private static final String SUCCESS = "SUCCESS";
  private static final String FAILURE = "FAILURE";

  /**
   * This method publishes the beam pipeline metrics to the spring boot actuator. Previous metrics
   * are removed from the actuator before publishing the latest metrics
   *
   * @param metricResults The pipeline metrics that needs to be pushed
   */
  void publishPipelineMetrics(MetricResults metricResults) {
    MetricQueryResults metricQueryResults = EtlUtils.getMetrics(metricResults);
    for (MetricResult<Long> metricCounterResult : metricQueryResults.getCounters()) {
      meterRegistry.gauge(
          metricCounterResult.getName().getNamespace()
              + "_"
              + metricCounterResult.getName().getName(),
          metricCounterResult.getAttempted());
    }
    for (MetricResult<GaugeResult> metricGaugeResult : metricQueryResults.getGauges()) {
      meterRegistry.gauge(
          metricGaugeResult.getName().getNamespace() + "_" + metricGaugeResult.getName().getName(),
          metricGaugeResult.getAttempted().getValue());
    }
  }

  void removePipelineMetrics() {
    meterRegistry
        .getMeters()
        .forEach(
            meter -> {
              if (meter.getId().getName().startsWith(MetricsConstants.METRICS_NAMESPACE)) {
                meterRegistry.remove(meter);
              }
            });
  }

  public MetricQueryResults getMetricQueryResults() {
    // TODO Generate metrics and stats even for incremental run, incremental run has two pipelines
    //  running one after the other, come up with a strategy to aggregate the metrics and generate
    //  the stats
    if (isBatchRun() && isRunning()) {
      PipelineMetrics pipelineMetrics =
          pipelineMetricsFactory.getPipelineMetrics(
              currentPipeline.pipeline.getOptions().getRunner());
      return pipelineMetrics.getMetricQueryResults();
    }
    return null;
  }

  private void setLastRunStatus(LastRunStatus status) {
    lastRunStatus = status;
    if (status == LastRunStatus.SUCCESS) {
      lastRunEnd = LocalDateTime.now();
    }
  }

  @PostConstruct
  private void initDwhStatus() {

    PipelineConfig pipelineConfig = dataProperties.createBatchOptions();
    FileSystems.setDefaultPipelineOptions(pipelineConfig.getFhirEtlOptions());
    validateFhirSourceConfiguration(pipelineConfig.getFhirEtlOptions());

    cron = CronExpression.parse(dataProperties.getIncrementalSchedule());
    String rootPrefix = dataProperties.getDwhRootPrefix();
    Preconditions.checkState(rootPrefix != null && !rootPrefix.isEmpty());

    String lastCompletedDwh = "";
    String lastDwh = "";
    String baseDir = dwhFilesManager.getBaseDir(rootPrefix);
    try {
      String prefix = dwhFilesManager.getPrefix(rootPrefix);
      List<ResourceId> paths =
          dwhFilesManager.getAllChildDirectories(baseDir).stream()
              .filter(dir -> dir.getFilename().startsWith(prefix))
              .collect(Collectors.toList());

      Preconditions.checkState(paths != null, "Make sure DWH prefix is a valid path!");

      for (ResourceId path : paths) {
        if (!path.getFilename().startsWith(prefix + DataProperties.TIMESTAMP_PREFIX)) {
          // This is not necessarily an error; the user may want to bootstrap from an already
          // created DWH outside the control-panel framework, e.g., by running the batch pipeline
          // directly.
          logger.warn(
              "DWH directory {} does not start with {}",
              paths,
              prefix + DataProperties.TIMESTAMP_PREFIX);
        }
        if (lastDwh.isEmpty() || lastDwh.compareTo(path.getFilename()) < 0) {
          logger.debug("Found a more recent DWH {}", path.getFilename());
          lastDwh = path.getFilename();
        }
        // Do not consider if the DWH is not completely created earlier.
        if (!dwhFilesManager.isDwhComplete(path)) {
          continue;
        }
        if (lastCompletedDwh.isEmpty() || lastCompletedDwh.compareTo(path.getFilename()) < 0) {
          logger.debug("Found a more recent completed DWH {}", path.getFilename());
          lastCompletedDwh = path.getFilename();
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    if (lastCompletedDwh.isEmpty()) {
      logger.info("No DWH found; it should be created by running a full pipeline");
      currentDwh = null;
      lastRunEnd = null;
    } else {
      logger.info("Initializing with most recent DWH {}", lastCompletedDwh);
      ResourceId resourceId =
          FileSystems.matchNewResource(baseDir, true)
              .resolve(lastCompletedDwh, StandardResolveOptions.RESOLVE_DIRECTORY);
      currentDwh = DwhFiles.forRoot(resourceId.toString());
      // There exists a DWH from before, so we set the scheduler to continue updating the DWH.
      lastRunEnd = LocalDateTime.now();
    }
    if (!lastDwh.isEmpty()) {
      initialiseLastRunDetails(baseDir, lastDwh);
    }
  }

  /**
   * This method initialises the lastRunDetails based on the dwh snapshot created recently. In case
   * an incremental run exists in the snapshot, the status is set based on the incremental run.
   */
  private void initialiseLastRunDetails(String baseDir, String dwhDirectory) {
    try {
      ResourceId dwhDirectoryPath =
          FileSystems.matchNewResource(baseDir, true)
              .resolve(dwhDirectory, StandardResolveOptions.RESOLVE_DIRECTORY);
      DwhFiles dwhFiles = DwhFiles.forRoot(dwhDirectoryPath.toString());
      if (dwhFiles.hasIncrementalDir()) {
        updateLastRunDetails(dwhFiles.getIncrementalRunPath());
        return;
      }
      // In case of no incremental run, the status is set based on the dwhDirectory snapshot.
      updateLastRunDetails(dwhDirectoryPath);
    } catch (IOException e) {
      logger.error("Error while initialising last run details", e);
      throw new RuntimeException(e);
    }
  }

  private void updateLastRunDetails(ResourceId dwhDirectoryPath) throws IOException {
    if (dwhFilesManager.isDwhComplete(dwhDirectoryPath)) {
      setLastRunDetails(dwhDirectoryPath.toString(), SUCCESS);
    } else {
      setLastRunDetails(dwhDirectoryPath.toString(), FAILURE);
    }
  }

  /**
   * Validate the FHIR source configuration parameters during the launch of the application. This is
   * to detect any mis-configurations earlier enough and avoid failures during pipeline runs.
   */
  void validateFhirSourceConfiguration(FhirEtlOptions options) {
    if (options.isJdbcModeHapi()) {
      validateDbConfigParameters(options.getFhirDatabaseConfigPath());
    } else if (!Strings.isNullOrEmpty(options.getFhirServerUrl())) {
      validateFhirSearchParameters(options);
    }
  }

  private void validateDbConfigParameters(String dbConfigPath) {
    try {
      DatabaseConfiguration dbConfiguration =
          DatabaseConfiguration.createConfigFromFile(dbConfigPath);
      boolean isValid =
          JdbcConnectionUtil.validateJdbcDetails(
              dbConfiguration.getJdbcDriverClass(),
              dbConfiguration.makeJdbsUrlFromConfig(),
              dbConfiguration.getDatabaseUser(),
              dbConfiguration.getDatabasePassword());
      Preconditions.checkArgument(isValid);
    } catch (IOException | SQLException | ClassNotFoundException e) {
      logger.error("Error occurred while validating jdbc details", e);
      throw new RuntimeException(e);
    }
  }

  private void validateFhirSearchParameters(FhirEtlOptions options) {
    FhirSearchUtil fhirSearchUtil =
        new FhirSearchUtil(
            new OpenmrsUtil(
                options.getFhirServerUrl(),
                options.getFhirServerUserName(),
                options.getFhirServerPassword(),
                FhirContext.forR4()));
    fhirSearchUtil.testFhirConnection();
  }

  synchronized boolean isBatchRun() {
    return currentPipeline != null && currentPipeline.isBatchRun;
  }

  synchronized boolean isRunning() {
    return currentPipeline != null && currentPipeline.isAlive();
  }

  synchronized String getCurrentDwhRoot() {
    if (currentDwh == null) {
      return "";
    }
    return currentDwh.getRoot();
  }

  /**
   * @return the next scheduled time to run the incremental pipeline or null iff a pipeline is
   *     currently running or no previous DWH exist.
   */
  LocalDateTime getNextIncrementalTime() {
    if (isRunning() || lastRunEnd == null) {
      return null;
    }
    return cron.next(lastRunEnd);
  }

  DwhRunDetails getLastRunDetails() {
    return lastRunDetails;
  }

  // Every 30 seconds, check for pipeline status and incremental pipeline schedule.
  @Scheduled(fixedDelay = 30000)
  private void checkSchedule() throws IOException, PropertyVetoException, SQLException {
    LocalDateTime next = getNextIncrementalTime();
    if (next == null) {
      return;
    }
    logger.info("Last run was at {} next run is at {}", lastRunEnd, next);
    if (next.compareTo(LocalDateTime.now()) < 0) {
      logger.info("Incremental run triggered at {}", LocalDateTime.now());
      runIncrementalPipeline();
    }
  }

  synchronized void runBatchPipeline() throws IOException, PropertyVetoException, SQLException {
    Preconditions.checkState(!isRunning(), "cannot start a pipeline while another one is running");
    PipelineConfig pipelineConfig = dataProperties.createBatchOptions();
    FhirEtlOptions options = pipelineConfig.getFhirEtlOptions();
    Pipeline pipeline = FhirEtl.buildPipeline(options);
    if (pipeline == null) {
      logger.warn("No resources found to be fetched!");
      return;
    } else {
      currentPipeline = new PipelineThread(pipeline, this, dataProperties, pipelineConfig, true);
    }
    logger.info("Running full pipeline for DWH {}", options.getOutputParquetPath());
    // We will only have one thread for running pipelines hence no need for a thread pool.
    currentPipeline.start();
  }

  synchronized void runIncrementalPipeline()
      throws IOException, PropertyVetoException, SQLException {
    // TODO do the same as above but read/set --since
    Preconditions.checkState(!isRunning(), "cannot start a pipeline while another one is running");
    Preconditions.checkState(
        currentDwh != null,
        "cannot start the incremental pipeline while there are no DWHs; run full pipeline");
    PipelineConfig pipelineConfig = dataProperties.createBatchOptions();
    FhirEtlOptions options = pipelineConfig.getFhirEtlOptions();
    String finalDwhRoot = options.getOutputParquetPath();
    // TODO move old incremental_run dir if there is one
    String incrementalDwhRoot = currentDwh.newIncrementalRunPath().toString();
    options.setOutputParquetPath(incrementalDwhRoot);
    String since = currentDwh.readTimestampFile(DwhFiles.TIMESTAMP_FILE_START).toString();
    options.setSince(since);
    Pipeline pipeline = FhirEtl.buildPipeline(options);

    // The merger pipeline merges the original full DWH with the new incremental one.
    ParquetMergerOptions mergerOptions = PipelineOptionsFactory.as(ParquetMergerOptions.class);
    mergerOptions.setDwh1(currentDwh.getRoot());
    mergerOptions.setDwh2(incrementalDwhRoot);
    mergerOptions.setMergedDwh(finalDwhRoot);
    mergerOptions.setRunner(FlinkRunner.class);
    mergerOptions.setNumShards(dataProperties.getMaxWorkers());
    FlinkPipelineOptions flinkOptions = mergerOptions.as(FlinkPipelineOptions.class);
    flinkOptions.setFasterCopy(true);
    flinkOptions.setMaxParallelism(dataProperties.getMaxWorkers());
    if (dataProperties.getNumThreads() > 0) {
      flinkOptions.setParallelism(dataProperties.getNumThreads());
    }

    if (pipeline == null) {
      // TODO communicate this to the UI
      logger.info("No new resources to be fetched!");
      setLastRunStatus(LastRunStatus.SUCCESS);
    } else {
      // Creating a thread for running both pipelines, one after the other.
      currentPipeline =
          new PipelineThread(pipeline, mergerOptions, this, dataProperties, pipelineConfig, false);
      logger.info("Running incremental pipeline for DWH {} since {}", currentDwh.getRoot(), since);
      currentPipeline.start();
    }
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent applicationReadyEvent) {
    createResourceTables();
  }

  /**
   * This method checks upon Controller start checks on Thrift sever to create resource tables if
   * they don't exist. There is a @PostConstruct method present in this class which is initDwhStatus
   * and the reason below code has not been added because dataProperties.getThriftserverHiveConfig()
   * turns out to be null when used by
   * DatabaseConfiguration.createConfigFromFile(dataProperties.getThriftserverHiveConfig()).
   */
  public void createResourceTables() {
    if (!dataProperties.isCreateHiveResourceTables()) {
      return;
    }

    DatabaseConfiguration dbConfig;
    try {
      dbConfig =
          DatabaseConfiguration.createConfigFromFile(dataProperties.getThriftserverHiveConfig());
    } catch (IOException e) {
      logger.error("Exception while reading thrift hive config.");
      throw new RuntimeException(e);
    }
    HiveTableManager hiveTableManager =
        new HiveTableManager(
            dbConfig.makeJdbsUrlFromConfig(),
            dbConfig.getDatabaseUser(),
            dbConfig.getDatabasePassword());

    String rootPrefix = dataProperties.getDwhRootPrefix();
    Preconditions.checkState(rootPrefix != null && !rootPrefix.isEmpty());

    String prefix = dwhFilesManager.getPrefix(rootPrefix);
    String baseDir = dwhFilesManager.getBaseDir(rootPrefix);

    try {
      List<ResourceId> paths =
          dwhFilesManager.getAllChildDirectories(baseDir).stream()
              .filter(dir -> dir.getFilename().startsWith(prefix + DataProperties.TIMESTAMP_PREFIX))
              .collect(Collectors.toList());

      Preconditions.checkState(paths != null, "Make sure DWH prefix is a valid path!");

      // Sort snapshots directories.
      Collections.sort(paths, Comparator.comparing(ResourceId::toString));

      int snapshotCount = 0;
      for (ResourceId path : paths) {
        snapshotCount++;
        Set<ResourceId> childPaths =
            dwhFilesManager.getAllChildDirectories(baseDir + "/" + path.getFilename());
        for (ResourceId resourceId : childPaths) {
          String resource = resourceId.getFilename();
          // Ignore if you encounter folders like incremental_run.
          if (dataProperties.getResourceList().indexOf(resourceId.getFilename()) == -1) {
            continue;
          }
          String[] tokens = path.getFilename().split(prefix + DataProperties.TIMESTAMP_PREFIX);
          if (tokens.length > 1) {
            String timestamp = tokens[1];
            String thriftServerParquetPath =
                baseDir + "/" + path.getFilename() + "/" + resourceId.getFilename();
            logger.debug("thriftServerParquetPath: ", thriftServerParquetPath);
            hiveTableManager.createResourceTable(resource, timestamp, thriftServerParquetPath);
            // Create Canonical table for the latest snapshot.
            if (snapshotCount == paths.size()) {
              hiveTableManager.createResourceCanonicalTable(resource, thriftServerParquetPath);
            }
          }
        }
      }
    } catch (IOException e) {
      logger.error("Exception while reading thriftserver parquet output directory: ", e);
      throw new RuntimeException(e);
    } catch (SQLException e) {
      logger.error("Exception while creating resource tables on thriftserver: ", e);
      throw new RuntimeException(e);
    }
  }

  private synchronized void updateDwh(String newRoot) {
    currentDwh = DwhFiles.forRoot(newRoot);
  }

  private static class PipelineThread extends Thread {

    private final Pipeline pipeline;
    private final PipelineManager manager;
    // This is used in the incremental mode only.
    private final ParquetMergerOptions mergerOptions;

    private final DataProperties dataProperties;

    private final PipelineConfig pipelineConfig;

    private final boolean isBatchRun;

    PipelineThread(
        Pipeline pipeline,
        PipelineManager manager,
        DataProperties dataProperties,
        PipelineConfig pipelineConfig,
        boolean isBatchRun) {
      Preconditions.checkArgument(pipeline.getOptions().as(FhirEtlOptions.class) != null);
      this.pipeline = pipeline;
      this.manager = manager;
      this.dataProperties = dataProperties;
      this.pipelineConfig = pipelineConfig;
      this.isBatchRun = isBatchRun;
      this.mergerOptions = null;
    }

    PipelineThread(
        Pipeline pipeline,
        ParquetMergerOptions mergerOptions,
        PipelineManager manager,
        DataProperties dataProperties,
        PipelineConfig pipelineConfig,
        boolean isBatchRun) {
      Preconditions.checkArgument(pipeline.getOptions().as(FhirEtlOptions.class) != null);
      this.pipeline = pipeline;
      this.manager = manager;
      this.mergerOptions = mergerOptions;
      this.dataProperties = dataProperties;
      this.pipelineConfig = pipelineConfig;
      this.isBatchRun = isBatchRun;
    }

    @Override
    public void run() {
      String currentDwhRoot = null;
      try {
        FhirEtlOptions options = pipeline.getOptions().as(FhirEtlOptions.class);
        currentDwhRoot = options.getOutputParquetPath();
        PipelineResult pipelineResult = EtlUtils.runPipelineWithTimestamp(pipeline, options);
        // Remove the metrics of the previous pipeline and register the new metrics
        manager.removePipelineMetrics();
        manager.publishPipelineMetrics(pipelineResult.metrics());
        if (mergerOptions == null) { // Do not update DWH yet if this was an incremental run.
          manager.updateDwh(currentDwhRoot);
        } else {
          currentDwhRoot = mergerOptions.getMergedDwh();
          FhirContext fhirContext = FhirContexts.forR4();
          Pipeline mergerPipeline = ParquetMerger.createMergerPipeline(mergerOptions, fhirContext);
          logger.info("Merger options are {}", mergerOptions);
          PipelineResult mergerPipelineResult =
              EtlUtils.runMergerPipelineWithTimestamp(mergerPipeline, mergerOptions);
          manager.publishPipelineMetrics(mergerPipelineResult.metrics());
          manager.updateDwh(currentDwhRoot);
        }
        if (dataProperties.isCreateHiveResourceTables()) {
          createHiveResourceTables(
              options.getResourceList(),
              pipelineConfig.getTimestampSuffix(),
              pipelineConfig.getThriftServerParquetPath());
        }
        manager.setLastRunStatus(LastRunStatus.SUCCESS);
        manager.setLastRunDetails(currentDwhRoot, SUCCESS);
      } catch (Exception e) {
        logger.error("exception while running pipeline: ", e);
        manager.captureError(currentDwhRoot, e);
        manager.setLastRunDetails(currentDwhRoot, FAILURE);
        manager.setLastRunStatus(LastRunStatus.FAILURE);
      }
    }

    private void createHiveResourceTables(
        String resourceList, String timestampSuffix, String thriftServerParquetPath)
        throws IOException, SQLException {

      logger.info("Establishing connection to Thrift server Hive");
      DatabaseConfiguration dbConfig =
          DatabaseConfiguration.createConfigFromFile(dataProperties.getThriftserverHiveConfig());

      logger.info("Creating resources on Thrift server Hive");
      HiveTableManager hiveTableManager =
          new HiveTableManager(
              dbConfig.makeJdbsUrlFromConfig(),
              dbConfig.getDatabaseUser(),
              dbConfig.getDatabasePassword());
      hiveTableManager.createResourceTables(resourceList, timestampSuffix, thriftServerParquetPath);
      logger.info("Created resources on Thrift server Hive");
    }
  }

  /** This method captures the given exception into a file rooted at the dwhRoot location. */
  void captureError(String dwhRoot, Exception e) {
    try {
      if (!Strings.isNullOrEmpty(dwhRoot)) {
        String stackTrace = ExceptionUtils.getStackTrace(e);
        DwhFiles.forRoot(dwhRoot)
            .writeToFile(ERROR_FILE_NAME, stackTrace.getBytes(StandardCharsets.UTF_8));
      }
    } catch (IOException ex) {
      logger.error("Error while capturing error to a file", ex);
    }
  }

  /** Sets the details of the last pipeline run with the given dwhRoot as the snapshot location. */
  void setLastRunDetails(String dwhRoot, String status) {
    DwhRunDetails dwhRunDetails = new DwhRunDetails();
    try {
      DwhFiles dwhFiles = DwhFiles.forRoot(dwhRoot);
      String startTime = dwhFiles.readTimestampFile(DwhFiles.TIMESTAMP_FILE_START).toString();
      dwhRunDetails.setStartTime(startTime);
      if (!Strings.isNullOrEmpty(status) && status.equalsIgnoreCase(SUCCESS)) {
        String endTime = dwhFiles.readTimestampFile(DwhFiles.TIMESTAMP_FILE_END).toString();
        dwhRunDetails.setEndTime(endTime);
      } else {
        dwhRoot = dwhRoot.endsWith("/") ? dwhRoot : dwhRoot + "/";
        dwhRunDetails.setLogFilePath(dwhRoot + ERROR_FILE_NAME);
      }
      dwhRunDetails.setStatus(status);
      this.lastRunDetails = dwhRunDetails;
    } catch (IOException e) {
      logger.error("Error in reading timestamp files", e);
      throw new RuntimeException(e);
    }
  }

  public enum LastRunStatus {
    NOT_RUN,
    SUCCESS,
    FAILURE
  }

  @Data
  public class DwhRunDetails {

    private String startTime;
    private String endTime;
    private String status;
    private String logFilePath;
  }
}
