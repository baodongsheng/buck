/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.file;

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class WriteFile extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  @AddToRuleKey private final byte[] fileContents;

  @AddToRuleKey(stringify = true)
  private final Path output;

  @AddToRuleKey private final boolean executable;

  public WriteFile(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      String fileContents,
      Path output,
      boolean executable) {
    this(
        buildTarget,
        projectFilesystem,
        buildRuleParams,
        fileContents.getBytes(StandardCharsets.UTF_8),
        output,
        executable);
  }

  public WriteFile(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      byte[] fileContents,
      Path output,
      boolean executable) {
    super(buildTarget, projectFilesystem, buildRuleParams);

    Preconditions.checkArgument(!output.isAbsolute(), "'%s' must not be absolute.", output);

    this.fileContents = fileContents;
    this.output = output;
    this.executable = executable;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);
    ProjectFilesystem projectFilesystem = getProjectFilesystem();
    return ImmutableList.of(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())),
        new WriteFileStep(projectFilesystem, ByteSource.wrap(fileContents), output, executable));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }

  public byte[] getFileContents() {
    return fileContents.clone();
  }
}
