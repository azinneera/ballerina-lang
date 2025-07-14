/*
 *  Copyright (c) 2022, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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

package io.ballerina.cli.cmd;

import io.ballerina.cli.BLauncherCmd;
import io.ballerina.cli.TaskExecutor;
import io.ballerina.cli.task.CleanTargetDirTask;
import io.ballerina.cli.task.CreateDependencyGraphTask;
import io.ballerina.cli.task.ResolveMavenDependenciesTask;
import io.ballerina.cli.task.RunBuildToolsTask;
import io.ballerina.cli.utils.FileUtils;
import io.ballerina.projects.BuildOptions;
import io.ballerina.projects.ProjectException;
import io.ballerina.projects.ProjectKind;
import io.ballerina.projects.Workspace;
import io.ballerina.projects.internal.model.Target;
import io.ballerina.projects.util.ProjectConstants;
import io.ballerina.projects.util.ProjectPaths;
import org.wso2.ballerinalang.util.RepoUtils;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static io.ballerina.cli.cmd.Constants.GRAPH_COMMAND;
import static io.ballerina.cli.launcher.LauncherUtils.createLauncherException;

/**
 * This class represents the "bal graph" command.
 *
 * @since 2201.2.0
 */
@CommandLine.Command(name = GRAPH_COMMAND, description = "Print the dependency graph in the console")
public class GraphCommand implements BLauncherCmd {
    private final PrintStream outStream;
    private final PrintStream errStream;
    private final boolean exitWhenFinish;

    @CommandLine.Parameters(arity = "0..1")
    private final Path projectPath;

    @CommandLine.Option(names = "--dump-raw-graphs", description = "Print all intermediate graphs created in the " +
            "dependency resolution process.", defaultValue = "false")
    private boolean dumpRawGraphs;

    @CommandLine.Option(names = {"--help", "-h"}, hidden = true, defaultValue = "false")
    private boolean helpFlag;

    @CommandLine.Option(names = {"--offline"}, description = "Print the dependency graph offline without downloading " +
            "dependencies.")
    private boolean offline;

    @CommandLine.Option(names = "--sticky", description = "stick to exact versions locked (if exists)",
            defaultValue = "false")
    private boolean sticky;

    public GraphCommand() {
        this.projectPath = Path.of(System.getProperty(ProjectConstants.USER_DIR));
        this.outStream = System.out;
        this.errStream = System.err;
        this.exitWhenFinish = true;
    }

    GraphCommand(Path projectPath, PrintStream outStream, PrintStream errStream, boolean exitWhenFinish) {
        this.projectPath = projectPath;
        this.outStream = outStream;
        this.errStream = errStream;
        this.exitWhenFinish = exitWhenFinish;
        this.offline = true;
    }

    @Override
    public void execute() {
        if (this.helpFlag) {
            printHelpCommandInfo();
            return;
        }

        // load project
        if (ProjectPaths.isWorkspaceRoot(this.projectPath)) {
            CommandUtil.printError(this.errStream,
                    "the specified path is a workspace, please specify a package or a source file to run",
                    null, true);
            CommandUtil.exitError(this.exitWhenFinish);
            return;
        }

        Optional<Path> workspaceRoot = ProjectPaths.findWorkspaceRoot(this.projectPath);
        BuildOptions buildOptions = constructBuildOptions();

        Workspace workspace;
        try {
            workspace = workspaceRoot.map(path -> Workspace.load(path, buildOptions)).orElseGet(()
                    -> Workspace.load(this.projectPath, buildOptions));
        } catch (ProjectException e) {
            CommandUtil.printError(this.errStream, "failed to load the workspace: " + e.getMessage(), null, false);
            CommandUtil.exitError(this.exitWhenFinish);
            return;
        }

        Target target;
        try {
            if (workspace.kind().equals(ProjectKind.SINGLE_FILE_PROJECT)) {
                target = new Target(Files.createTempDirectory("ballerina-cache" + System.nanoTime()));
                target.setOutputPath(target.getBinPath());
            }
        } catch (IOException e) {
            throw createLauncherException("unable to resolve the target path:" + e.getMessage());
        } catch (ProjectException e) {
            throw createLauncherException("unable to create the executable:" + e.getMessage());
        }

        validateSettingsToml();

        Path absProjectPath = this.projectPath.toAbsolutePath().normalize();
        TaskExecutor taskExecutor = new TaskExecutor.TaskBuilder()
                .addTask(new CleanTargetDirTask(absProjectPath), isSingleFileProject())
                .addTask(new RunBuildToolsTask(outStream, absProjectPath), isSingleFileProject())
                .addTask(new ResolveMavenDependenciesTask(outStream, absProjectPath), isSingleFileProject())
                .addTask(new CreateDependencyGraphTask(outStream, errStream, absProjectPath))
                .build();
        taskExecutor.executeTasks(workspace);
        exitIfRequired();
    }

    private void printHelpCommandInfo() {
        String commandUsageInfo = BLauncherCmd.getCommandUsageInfo(GRAPH_COMMAND);
        this.outStream.println(commandUsageInfo);
    }

    private void validateSettingsToml() {
        RepoUtils.readSettings();
    }

    private void exitIfRequired() {
        if (this.exitWhenFinish) {
            Runtime.getRuntime().exit(0);
        }
    }

    private BuildOptions constructBuildOptions() {
        // if all dependency graphs are printed it includes the final graph.
        // Therefore, final graph is not needed to print separately.
        boolean dumpGraph = !dumpRawGraphs;
        BuildOptions.BuildOptionsBuilder buildOptionsBuilder = BuildOptions.builder();

        buildOptionsBuilder
                .setDumpGraph(dumpGraph)
                .setDumpRawGraphs(this.dumpRawGraphs)
                .setOffline(this.offline)
                .setSticky(this.sticky);

        return buildOptionsBuilder.build();
    }

    private boolean isSingleFileProject() {
        return FileUtils.hasExtension(this.projectPath);
    }

    @Override
    public String getName() {
        return GRAPH_COMMAND;
    }

    @Override
    public void printLongDesc(StringBuilder out) {
        out.append(BLauncherCmd.getCommandUsageInfo(GRAPH_COMMAND));
    }

    @Override
    public void printUsage(StringBuilder out) {
        out.append("  bal graph [--dump-raw-graph] [--offline] [--sticky] \\n\" +\n" +
                "            \"                    [<ballerina-file | package-path>]");
    }

    @Override
    public void setParentCmdParser(CommandLine parentCmdParser) {
    }
}
