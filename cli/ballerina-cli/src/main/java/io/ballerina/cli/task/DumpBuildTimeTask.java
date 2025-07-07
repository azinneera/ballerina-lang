/*
 *  Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package io.ballerina.cli.task;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.ballerina.cli.utils.BuildTime;
import io.ballerina.projects.BuildOptions;
import io.ballerina.projects.DependencyGraph;
import io.ballerina.projects.Package;
import io.ballerina.projects.Project;
import io.ballerina.projects.ProjectKind;
import io.ballerina.projects.ResolvedPackageDependency;
import io.ballerina.projects.Workspace;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.ballerina.cli.launcher.LauncherUtils.createLauncherException;

/**
 * Task for generation build time file.
 *
 * @since 2.0.0
 */
public class DumpBuildTimeTask implements Task {
    private static final String BUILD_TIME_JSON = "build-time.json";
    private final transient PrintStream out;
    private final Path projectPath;
    private final Path currentDir = Path.of(System.getProperty("user.dir"));

    public DumpBuildTimeTask(PrintStream out) {
        this(out, null);
    }

    public DumpBuildTimeTask(PrintStream outStream, Path projectPath) {
        this.out = outStream;
        this.projectPath = projectPath;
    }

    @Override
    public void execute(Workspace workspace) {
        DependencyGraph<ResolvedPackageDependency> dependencyGraph = workspace.dependencyGraph();
        List<ResolvedPackageDependency> topologicallySortedList = new ArrayList<>(
                dependencyGraph.toTopologicallySortedList());
        if (this.projectPath != null) {
            ResolvedPackageDependency packageDependency = topologicallySortedList.stream().filter(
                            dependency -> workspace.sourceRoot(dependency.packageInstance().descriptor())
                                    .equals(this.projectPath))
                    .findFirst().orElseThrow();
            topologicallySortedList.removeIf(pkg ->
                    !dependencyGraph.getAllDependencies(packageDependency).contains(pkg)
                            && !pkg.equals(packageDependency));
        }
        for (ResolvedPackageDependency packageDependency : topologicallySortedList) {
            execute(workspace.getPackage(packageDependency.packageInstance().descriptor()),
                    workspace.buildOptions(packageDependency.packageInstance().descriptor()));
        }
    }

    @Override
    public void execute(Project project) {
        execute(project.currentPackage(), project.buildOptions());
    }

    private void execute(Package pkg, BuildOptions buildOptions) {
        if (!buildOptions.dumpBuildTime()) {
            return;
        }
        BuildTime.getInstance().totalDuration = System.currentTimeMillis() - BuildTime.getInstance().timestamp;
        BuildTime.getInstance().offline = buildOptions.offlineBuild();
        Path buildTimeFile = getBuildTimeFilePath(pkg);
        Path buildTimeFileRelativePath = Path.of(System.getProperty("user.dir")).relativize(buildTimeFile);
        this.out.println("\nDumping build time information\n\t" + buildTimeFileRelativePath);
        persistBuildTimeToFile(buildTimeFile);
    }

    private void persistBuildTimeToFile(Path filepath) {
        File jsonFile = new File(filepath.toString());
        try (FileOutputStream fileOutputStream = new FileOutputStream(jsonFile)) {
            try (Writer writer = new OutputStreamWriter(fileOutputStream, StandardCharsets.UTF_8)) {
                Gson gson = new Gson();
                BuildTime buildTime = BuildTime.getInstance();
                String json = gson.toJson(buildTime);
                writer.write(new String(json.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
                printBuildTime(buildTime);
            } catch (IOException e) {
                throw createLauncherException("couldn't write build time to file : " + e.getMessage());
            }
        } catch (IOException e) {
            throw createLauncherException("couldn't write build time to file : " + e.getMessage());
        }
    }

    private Path getBuildTimeFilePath(Package pkg) {
        if (pkg.workspace().kind().equals(ProjectKind.SINGLE_FILE_PROJECT)) {
            return currentDir.resolve(BUILD_TIME_JSON).toAbsolutePath();
        }
        return pkg.workspace().target(pkg.descriptor()).resolve(BUILD_TIME_JSON).toAbsolutePath();
    }

    private void printBuildTime(BuildTime buildTime) {
        Gson gson = new Gson();
        JsonObject buildTimeJson = (JsonObject) gson.toJsonTree(buildTime);
        buildTimeJson.entrySet()
                .forEach(entry -> this.out.println("\t" + entry.getKey() + " : " + entry.getValue().toString()));
    }
}
