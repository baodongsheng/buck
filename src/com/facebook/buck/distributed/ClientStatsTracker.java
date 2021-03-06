/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.distributed;

import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.*;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.annotation.concurrent.GuardedBy;

/** Tracks client side statistics. */
public class ClientStatsTracker {
  public enum DistBuildClientStat {
    LOCAL_PREPARATION, // Measures everything that happens before starting distributed build
    LOCAL_GRAPH_CONSTRUCTION,
    PERFORM_DISTRIBUTED_BUILD,
    PERFORM_LOCAL_BUILD,
    POST_DISTRIBUTED_BUILD_LOCAL_STEPS,
    PUBLISH_BUILD_SLAVE_FINISHED_STATS,
    POST_BUILD_ANALYSIS,
    CREATE_DISTRIBUTED_BUILD,
    UPLOAD_MISSING_FILES,
    UPLOAD_TARGET_GRAPH,
    UPLOAD_BUCK_DOT_FILES,
    SET_BUCK_VERSION,
    MATERIALIZE_SLAVE_LOGS,
  }

  private static final DistBuildClientStat[] REQUIRED_STATS = {
    LOCAL_PREPARATION,
    LOCAL_GRAPH_CONSTRUCTION,
    PERFORM_DISTRIBUTED_BUILD,
    POST_DISTRIBUTED_BUILD_LOCAL_STEPS,
    CREATE_DISTRIBUTED_BUILD,
    UPLOAD_MISSING_FILES,
    UPLOAD_TARGET_GRAPH,
    UPLOAD_BUCK_DOT_FILES,
    SET_BUCK_VERSION,
    // TODO(ruibm): Understand why these are commented out and fix it.
    // POST_BUILD_ANALYSIS only happens if remote build was successful
    // PUBLISH_BUILD_SLAVE_FINISHED_STATS is optional
    // MATERIALIZE_SLAVE_LOGS is optional
    // PERFORM_LOCAL_BUILD only happens if remote build was successful
  };

  @GuardedBy("this")
  private final Map<DistBuildClientStat, Stopwatch> stopwatchesByType = new HashMap<>();

  @GuardedBy("this")
  private final Map<DistBuildClientStat, Long> durationsMsByType = new HashMap<>();

  private volatile Optional<String> stampedeId = Optional.empty();

  private volatile Optional<Integer> distributedBuildExitCode = Optional.empty();

  private volatile Optional<Boolean> isLocalFallbackBuildEnabled = Optional.empty();

  private volatile boolean performedLocalBuild = false;

  private volatile boolean buckClientError = false;

  private volatile Optional<Integer> localBuildExitCode = Optional.empty();

  private volatile Optional<Long> missingFilesUploadedCount = Optional.empty();

  private volatile Optional<String> buckClientErrorMessage = Optional.empty();

  private final String buildLabel;

  public ClientStatsTracker(String buildLabel) {
    this.buildLabel = buildLabel;
  }

  @GuardedBy("this")
  private void generateStatsPreconditionChecksNoException() {
    // Unless there was an exception, we expect all the following fields to be present.
    Preconditions.checkArgument(
        distributedBuildExitCode.isPresent(), "distributedBuildExitCode not set");
    Preconditions.checkArgument(
        isLocalFallbackBuildEnabled.isPresent(), "isLocalFallbackBuildEnabled not set");
    Preconditions.checkArgument(
        missingFilesUploadedCount.isPresent(), "missingFilesUploadedCount not set");

    if (performedLocalBuild) {
      Preconditions.checkArgument(localBuildExitCode.isPresent());
      Preconditions.checkNotNull(
          durationsMsByType.get(PERFORM_LOCAL_BUILD),
          "No time was recorded for stat: " + PERFORM_LOCAL_BUILD);
      Preconditions.checkNotNull(
          durationsMsByType.get(POST_BUILD_ANALYSIS),
          "No time was recorded for stat: " + POST_BUILD_ANALYSIS);
    }

    for (DistBuildClientStat stat : REQUIRED_STATS) {
      Preconditions.checkNotNull(
          durationsMsByType.get(stat), "No time was recorded for stat: " + stat);
    }
  }

  @GuardedBy("this")
  private Optional<Long> getDurationOrEmpty(DistBuildClientStat stat) {
    if (!durationsMsByType.containsKey(stat)) {
      return Optional.empty();
    }

    return Optional.of(durationsMsByType.get(stat));
  }

  public synchronized DistBuildClientStats generateStats() {
    // Without a Stampede ID there is nothing useful to record.
    Preconditions.checkArgument(stampedeId.isPresent());

    if (!buckClientError) {
      generateStatsPreconditionChecksNoException();
    } else {
      // Buck client threw an exception, so we will log on a best effort basis.
      Preconditions.checkArgument(buckClientErrorMessage.isPresent());
    }

    DistBuildClientStats.Builder builder =
        DistBuildClientStats.builder()
            .setStampedeId(stampedeId.get())
            .setPerformedLocalBuild(performedLocalBuild)
            .setBuckClientError(buckClientError)
            .setBuildLabel(buildLabel);

    builder.setDistributedBuildExitCode(distributedBuildExitCode);
    builder.setLocalFallbackBuildEnabled(isLocalFallbackBuildEnabled);
    builder.setBuckClientErrorMessage(buckClientErrorMessage);

    if (performedLocalBuild) {
      builder.setLocalBuildExitCode(localBuildExitCode);
      builder.setLocalBuildDurationMs(getDurationOrEmpty(PERFORM_LOCAL_BUILD));
      builder.setPostBuildAnalysisDurationMs(getDurationOrEmpty(POST_BUILD_ANALYSIS));
    }

    builder.setLocalPreparationDurationMs(getDurationOrEmpty(LOCAL_PREPARATION));
    builder.setLocalGraphConstructionDurationMs(getDurationOrEmpty(LOCAL_GRAPH_CONSTRUCTION));
    builder.setPostDistBuildLocalStepsDurationMs(
        getDurationOrEmpty(POST_DISTRIBUTED_BUILD_LOCAL_STEPS));
    builder.setPerformDistributedBuildDurationMs(getDurationOrEmpty(PERFORM_DISTRIBUTED_BUILD));
    builder.setCreateDistributedBuildDurationMs(getDurationOrEmpty(CREATE_DISTRIBUTED_BUILD));
    builder.setUploadMissingFilesDurationMs(getDurationOrEmpty(UPLOAD_MISSING_FILES));
    builder.setUploadTargetGraphDurationMs(getDurationOrEmpty(UPLOAD_TARGET_GRAPH));
    builder.setUploadBuckDotFilesDurationMs(getDurationOrEmpty(UPLOAD_BUCK_DOT_FILES));
    builder.setSetBuckVersionDurationMs(getDurationOrEmpty(SET_BUCK_VERSION));
    builder.setMaterializeSlaveLogsDurationMs(getDurationOrEmpty(MATERIALIZE_SLAVE_LOGS));
    builder.setPublishBuildSlaveFinishedStatsDurationMs(
        getDurationOrEmpty(PUBLISH_BUILD_SLAVE_FINISHED_STATS));

    builder.setMissingFilesUploadedCount(missingFilesUploadedCount);

    return builder.build();
  }

  public void setMissingFilesUploadedCount(long missingFilesUploadedCount) {
    this.missingFilesUploadedCount = Optional.of(missingFilesUploadedCount);
  }

  public void setPerformedLocalBuild(boolean performedLocalBuild) {
    this.performedLocalBuild = performedLocalBuild;
  }

  public void setLocalBuildExitCode(int localBuildExitCode) {
    this.localBuildExitCode = Optional.of(localBuildExitCode);
  }

  public void setStampedeId(String stampedeId) {
    this.stampedeId = Optional.of(stampedeId);
  }

  public void setDistributedBuildExitCode(int distributedBuildExitCode) {
    this.distributedBuildExitCode = Optional.of(distributedBuildExitCode);
  }

  public void setIsLocalFallbackBuildEnabled(boolean isLocalFallbackBuildEnabled) {
    this.isLocalFallbackBuildEnabled = Optional.of(isLocalFallbackBuildEnabled);
  }

  public boolean hasStampedeId() {
    return stampedeId.isPresent();
  }

  public void setBuckClientError(boolean buckClientError) {
    this.buckClientError = buckClientError;
  }

  public void setBuckClientErrorMessage(String buckClientErrorMessage) {
    this.buckClientErrorMessage = Optional.of(buckClientErrorMessage);
  }

  @VisibleForTesting
  protected synchronized void setDurationMs(DistBuildClientStat stat, long duration) {
    durationsMsByType.put(stat, duration);
  }

  public synchronized void startTimer(DistBuildClientStat stat) {
    Stopwatch stopwatch = Stopwatch.createStarted();
    stopwatchesByType.put(stat, stopwatch);
  }

  public synchronized void stopTimer(DistBuildClientStat stat) {
    Preconditions.checkNotNull(
        stopwatchesByType.get(stat),
        "Cannot stop timer for stat: [" + stat + "] as it was not started.");

    Stopwatch stopwatch = stopwatchesByType.get(stat);
    stopwatch.stop();
    long elapsed = stopwatch.elapsed(TimeUnit.MILLISECONDS);
    durationsMsByType.put(stat, elapsed);
  }
}
