/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.cli.task;

import io.ballerina.cli.utils.BuildTime;
import io.ballerina.cli.utils.BuildUtils;
import io.ballerina.cli.utils.FileUtils;
import io.ballerina.cli.utils.GraalVMCompatibilityUtils;
import io.ballerina.projects.BuildOptions;
import io.ballerina.projects.DependencyGraph;
import io.ballerina.projects.EmitResult;
import io.ballerina.projects.JBallerinaBackend;
import io.ballerina.projects.JvmTarget;
import io.ballerina.projects.Package;
import io.ballerina.projects.PackageCompilation;
import io.ballerina.projects.Project;
import io.ballerina.projects.ProjectException;
import io.ballerina.projects.ProjectKind;
import io.ballerina.projects.ResolvedPackageDependency;
import io.ballerina.projects.Workspace;
import io.ballerina.projects.internal.model.Target;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.ballerina.cli.launcher.LauncherUtils.createLauncherException;
import static io.ballerina.cli.utils.FileUtils.getFileNameWithoutExtension;
import static io.ballerina.projects.util.ProjectConstants.BLANG_COMPILED_JAR_EXT;
import static io.ballerina.projects.util.ProjectConstants.USER_DIR;

/**
 * Task for creating the executable jar file.
 *
 * @since 2.0.0
 */
public class CreateExecutableTask implements Task {
    private final transient PrintStream out;
    private Path output;
    private Path currentDir;
    private Target target;
    private final boolean isHideTaskOutput;
    private final Path projectPath;

    public CreateExecutableTask(PrintStream outStream, String output, Object target, boolean isHideTaskOutput) {
        this(outStream, output, target instanceof Target ? (Target) target : null, isHideTaskOutput, null);
    }

    public CreateExecutableTask(PrintStream out, String output, Target target, boolean isHideTaskOutput, Path projectPath) {
        this.out = out;
        this.target = target;
        this.isHideTaskOutput = isHideTaskOutput;
        this.projectPath = projectPath;
        if (output != null) {
            this.output = Path.of(output);
        }
    }

    @Override
    public void execute(Workspace workspace) {
        DependencyGraph<ResolvedPackageDependency> dependencyGraph = workspace.dependencyGraph();
        List<ResolvedPackageDependency> topologicallySortedList = new ArrayList<>(
                dependencyGraph.toTopologicallySortedList());
        ResolvedPackageDependency rootPackage;
        if (this.projectPath != null) {
            rootPackage = topologicallySortedList.stream().filter(
                            dependency -> workspace.sourceRoot(dependency.packageInstance().descriptor())
                                    .equals(this.projectPath))
                    .findFirst().orElseThrow();
            topologicallySortedList.removeIf(pkg ->
                    !dependencyGraph.getAllDependencies(rootPackage).contains(pkg)
                            && !pkg.equals(rootPackage));
        } else {
            rootPackage = null;
        }
        for (ResolvedPackageDependency packageDependency : topologicallySortedList) {
            if(this.projectPath != null) {
                if(!packageDependency.equals(rootPackage)) {
                    continue;
                }
            } else if (!dependencyGraph.getAllDependents(packageDependency).isEmpty()) {
                continue;
            }
            execute(packageDependency.packageInstance(),
                    workspace.buildOptions(packageDependency.packageInstance().descriptor()),
                    workspace.sourceRoot(packageDependency.packageInstance().descriptor()),
                    workspace.target(packageDependency.packageInstance().descriptor()));
        }
    }

    @Override
    public void execute(Project project) {
        execute(project.currentPackage(), project.buildOptions(), project.sourceRoot(), project.targetDir());
    }

    public void execute(Package pkg, BuildOptions buildOptions, Path sourceRoot, Path targetPath) {
        if (!isHideTaskOutput) {
            this.out.println();
            if (!buildOptions.nativeImage()) {
                this.out.println("Generating executable");
            }
        }

        this.currentDir = Path.of(System.getProperty(USER_DIR));
        if (target == null) {
            target = getTarget(pkg, sourceRoot, targetPath);
        }
        Path executablePath = getExecutablePath(pkg, target);
        try {
            PackageCompilation pkgCompilation = pkg.getCompilation();
            JBallerinaBackend jBallerinaBackend = JBallerinaBackend.from(pkgCompilation, JvmTarget.JAVA_21);
            long start = 0;
            if (buildOptions.dumpBuildTime()) {
                start = System.currentTimeMillis();
            }
            EmitResult emitResult;
            if (buildOptions.nativeImage() && buildOptions.cloud().isEmpty()) {
                String warnings = GraalVMCompatibilityUtils.getAllWarnings(
                        pkg, jBallerinaBackend.targetPlatform().code(), false);
                if (!warnings.isEmpty()) {
                    out.println(warnings);
                }
                emitResult = jBallerinaBackend.emit(JBallerinaBackend.OutputType.GRAAL_EXEC, executablePath);
            } else {
                emitResult = jBallerinaBackend.emit(JBallerinaBackend.OutputType.EXEC, executablePath);
            }

            if (buildOptions.dumpBuildTime()) {
                BuildTime.getInstance().emitArtifactDuration = System.currentTimeMillis() - start;
                BuildTime.getInstance().compile = false;
            }

            // Print warnings for conflicted jars
            if (!jBallerinaBackend.conflictedJars().isEmpty()) {
                out.println("\twarning: Detected conflicting jar files:");
                for (JBallerinaBackend.JarConflict conflict : jBallerinaBackend.conflictedJars()) {
                    out.println(conflict.getWarning(buildOptions.listConflictedClasses()));
                }
            }

            // Print diagnostics found during emit executable
            if (!emitResult.diagnostics().diagnostics().isEmpty() && !isHideTaskOutput) {
                emitResult.diagnostics().diagnostics().forEach(d -> out.println("\n" + d.toString()));
            }

        } catch (ProjectException e) {
            throw createLauncherException(e.getMessage());
        }

        if (!buildOptions.nativeImage() && !isHideTaskOutput) {
            Path relativePathToExecutable = currentDir.relativize(executablePath);

            if (buildOptions.getTargetPath() != null) {
                this.out.println("\t" + relativePathToExecutable);
            } else {
                if (relativePathToExecutable.toString().contains("..") ||
                        relativePathToExecutable.toString().contains("." + File.separator)) {
                    this.out.println("\t" + executablePath);
                } else {
                    this.out.println("\t" + relativePathToExecutable);
                }
            }
        }

        // notify plugin
        // todo following call has to be refactored after introducing new plugin architecture
        BuildUtils.notifyPlugins(pkg, target);
    }

    private Target getTarget(Package pkg, Path sourceRoot, Path targetPath) {
        Target target;
        try {
            if (pkg.workspace().kind().equals(ProjectKind.SINGLE_FILE_PROJECT)) {
                target = new Target(Files.createTempDirectory("ballerina-cache" + System.nanoTime()));
                target.setOutputPath(getExecutablePath(sourceRoot));
            } else {
                target = new Target(targetPath);
            }
        } catch (IOException e) {
            throw createLauncherException("unable to resolve target path:" + e.getMessage());
        } catch (ProjectException e) {
            throw createLauncherException("unable to create executable:" + e.getMessage());
        }
        return target;
    }
    private Path getExecutablePath(Package pkg, Target target) {
        try {
            return target.getExecutablePath(pkg).toAbsolutePath().normalize();
        } catch (IOException e) {
            throw createLauncherException(e.getMessage());
        }
    }

    private Path getExecutablePath(Path sourceRoot) {

        Path fileName = sourceRoot.getFileName();

        // If the --output flag is not set, create the executable in the current directory
        if (this.output == null) {
            return this.currentDir.resolve(getFileNameWithoutExtension(fileName) + BLANG_COMPILED_JAR_EXT);
        }

        if (!this.output.isAbsolute()) {
            this.output = currentDir.resolve(this.output);
        }

        // If the --output is a directory create the executable in the given directory
        if (Files.isDirectory(this.output)) {
            return output.resolve(getFileNameWithoutExtension(fileName) + BLANG_COMPILED_JAR_EXT);
        }

        // If the --output does not have an extension, append the .jar extension
        if (!FileUtils.hasExtension(this.output)) {
            return Path.of(this.output.toString() + BLANG_COMPILED_JAR_EXT);
        }

        return this.output;
    }
}
