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

import java.io.IOException;

/** Executes stampede in remote build mode. */
public class RemoteBuildModeRunner implements DistBuildModeRunner {

  /** Sets the final BuildStatus of the BuildJob. */
  public interface FinalBuildStatusSetter {
    void setFinalBuildStatus(int exitCode) throws IOException;
  }

  private final LocalBuilder localBuilder;
  private final Iterable<String> topLevelTargetsToBuild;
  private final FinalBuildStatusSetter setter;

  public RemoteBuildModeRunner(
      LocalBuilder localBuilder,
      Iterable<String> topLevelTargetsToBuild,
      FinalBuildStatusSetter setter) {
    this.localBuilder = localBuilder;
    this.topLevelTargetsToBuild = topLevelTargetsToBuild;
    this.setter = setter;
  }

  @Override
  public int runAndReturnExitCode() throws IOException, InterruptedException {
    int buildExitCode = localBuilder.buildLocallyAndReturnExitCode(topLevelTargetsToBuild);
    setter.setFinalBuildStatus(buildExitCode);
    return buildExitCode;
  }
}
