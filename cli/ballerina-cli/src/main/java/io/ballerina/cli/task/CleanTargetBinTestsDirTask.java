// Copyright (c) 2024 WSO2 LLC. (http://www.wso2.com).
//
// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package io.ballerina.cli.task;

import io.ballerina.projects.DependencyGraph;
import io.ballerina.projects.PackageDescriptor;
import io.ballerina.projects.ProjectException;
import io.ballerina.projects.Workspace;
import io.ballerina.projects.internal.model.Target;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.ballerina.cli.launcher.LauncherUtils.createLauncherException;

/**
 * Cleans up the target bin's tests directory.
 *
 * @since 2201.9.0
 */
public class CleanTargetBinTestsDirTask implements Task {
    private final Path absProjectPath;

    public CleanTargetBinTestsDirTask() {
        absProjectPath = null;
    }

    public CleanTargetBinTestsDirTask(Path projectPath) {
        absProjectPath = projectPath;
    }

    @Override
    public void execute(Workspace workspace) {
        DependencyGraph<PackageDescriptor> dependencyGraph = workspace.dependencyGraph();
        List<PackageDescriptor> topologicallySortedList = new ArrayList<>(
                dependencyGraph.toTopologicallySortedList());
        if (this.absProjectPath != null) {
            PackageDescriptor descriptor = topologicallySortedList.stream().filter(
                            dependency -> workspace.sourceRoot(dependency)
                                    .equals(this.absProjectPath))
                    .findFirst().orElseThrow();
            topologicallySortedList.removeIf(pkg ->
                    !dependencyGraph.getAllDependencies(descriptor).contains(pkg)
                            && !pkg.equals(descriptor));
        }

        for (PackageDescriptor descriptor : topologicallySortedList) {
            boolean isTestingDelegated = workspace.buildOptions(descriptor).cloud().equals("docker");
            if (isTestingDelegated) {
                continue;
            }
            try {
                Target target = new Target(workspace.targetDir(descriptor));
                target.cleanBinTests();
            } catch (IOException | ProjectException e) {
                throw createLauncherException("unable to clean the target bin's tests directory: " + e.getMessage());
            }
        }
    }
}
