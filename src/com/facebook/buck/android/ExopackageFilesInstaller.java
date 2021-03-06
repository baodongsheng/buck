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

package com.facebook.buck.android;

import com.facebook.buck.android.exopackage.ExopackageInfo;
import com.facebook.buck.android.exopackage.ExopackageInstaller;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.InstallTrigger;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.SortedSet;
import javax.annotation.Nullable;

/**
 * Installs exopackage files to the device/devices. This will install all the missing .so/.dex/.apk
 * exo files.
 */
public class ExopackageFilesInstaller extends AbstractBuildRule {
  @AddToRuleKey private final InstallTrigger trigger;
  @AddToRuleKey private final SourcePath manifestPath;
  @AddToRuleKey private final SourcePath deviceExoContents;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> exoSourcePaths;

  private final Supplier<ImmutableSortedSet<BuildRule>> depsSupplier;
  private final ExopackageInfo exopackageInfo;

  public ExopackageFilesInstaller(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder sourcePathRuleFinder,
      SourcePath deviceExoContents,
      SourcePath manifestPath,
      ExopackageInfo exopackageInfo) {
    super(buildTarget, projectFilesystem);
    this.trigger = new InstallTrigger(projectFilesystem);
    this.deviceExoContents = deviceExoContents;
    this.manifestPath = manifestPath;
    this.exopackageInfo = exopackageInfo;
    this.exoSourcePaths = getExopackageSourcePaths(exopackageInfo);

    this.depsSupplier =
        Suppliers.memoize(
            () ->
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(sourcePathRuleFinder.filterBuildRuleInputs(exoSourcePaths))
                    .addAll(
                        sourcePathRuleFinder.filterBuildRuleInputs(
                            Arrays.asList(manifestPath, deviceExoContents)))
                    .build());
  }

  private static ImmutableSortedSet<SourcePath> getExopackageSourcePaths(ExopackageInfo exoInfo) {
    return exoInfo.getRequiredPaths().collect(MoreCollectors.toImmutableSortedSet());
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return depsSupplier.get();
  }

  @Override
  public ImmutableList<? extends Step> getBuildSteps(
      BuildContext buildContext, BuildableContext buildableContext) {
    return ImmutableList.of(
        new AbstractExecutionStep("installing_exo_files") {
          @Override
          public StepExecutionResult execute(ExecutionContext context)
              throws IOException, InterruptedException {
            trigger.verify(context);
            String packageName =
                AdbHelper.tryToExtractPackageNameFromManifest(
                    buildContext.getSourcePathResolver().getAbsolutePath(manifestPath));
            ImmutableSortedMap<String, ImmutableSortedSet<Path>> contents =
                ExopackageDeviceDirectoryLister.deserializeDirectoryContentsForPackage(
                    getProjectFilesystem(),
                    buildContext.getSourcePathResolver().getRelativePath(deviceExoContents),
                    packageName);
            context
                .getAndroidDevicesHelper()
                .get()
                .adbCall(
                    "installing_exo_files",
                    device -> {
                      ImmutableSortedSet<Path> presentFiles =
                          Preconditions.checkNotNull(contents.get(device.getSerialNumber()));
                      new ExopackageInstaller(
                              buildContext.getSourcePathResolver(),
                              context,
                              getProjectFilesystem(),
                              packageName,
                              device)
                          .installMissingExopackageFiles(presentFiles, exopackageInfo);
                      return true;
                    },
                    true);
            return StepExecutionResult.SUCCESS;
          }
        });
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return null;
  }

  @Override
  public boolean isCacheable() {
    return false;
  }
}
