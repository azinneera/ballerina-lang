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
import io.ballerina.cli.utils.GraalVMCompatibilityUtils;
import io.ballerina.projects.BuildOptions;
import io.ballerina.projects.DependencyGraph;
import io.ballerina.projects.EmitResult;
import io.ballerina.projects.JBallerinaBackend;
import io.ballerina.projects.JvmTarget;
import io.ballerina.projects.Package;
import io.ballerina.projects.PackageCompilation;
import io.ballerina.projects.PackageDescriptor;
import io.ballerina.projects.Project;
import io.ballerina.projects.ProjectException;
import io.ballerina.projects.Workspace;
import io.ballerina.projects.internal.model.Target;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.ballerina.cli.launcher.LauncherUtils.createLauncherException;

/**
 * Task for creating bala file. Bala file writer is meant for modules only and not for single files.
 *
 * @since 2.0.0
 */
public class CreateBalaTask implements Task {
    private final transient PrintStream out;
    private final Package pkg;
    private final Path projectPath;

    public CreateBalaTask(PrintStream out, Package pkg, Path projectPath) {
        this.out = out;
        this.pkg = pkg;
        this.projectPath = projectPath;
    }

    @Override
    public void execute(Workspace workspace) {
        this.out.println();
        this.out.println("Creating bala");
        Target target;
        try {
            target = new Target(workspace.targetDir(pkg.descriptor()));
        } catch (IOException | ProjectException e) {
            throw createLauncherException(e.getMessage());
        }
        execute(target, workspace.buildOptions(pkg.descriptor()));
    }

    public void execute(Target target, BuildOptions buildOptions) {
        JBallerinaBackend jBallerinaBackend;
        EmitResult emitResult;

        try {
            PackageCompilation packageCompilation = pkg.getCompilation();
            jBallerinaBackend = JBallerinaBackend.from(packageCompilation, JvmTarget.JAVA_21);
            long start = 0;
            if (buildOptions.dumpBuildTime()) {
                start = System.currentTimeMillis();
            }
            String warning = GraalVMCompatibilityUtils.getWarningForPackage(
                    pkg, jBallerinaBackend.targetPlatform().code());
            if (warning != null) {
                out.println("\n" + warning);
            }
            emitResult = jBallerinaBackend.emit(JBallerinaBackend.OutputType.BALA, target.getBalaPath());
            if (buildOptions.dumpBuildTime()) {
                BuildTime.getInstance().emitArtifactDuration = System.currentTimeMillis() - start;
                BuildTime.getInstance().compile = true;
            }
        } catch (ProjectException e) {
            throw createLauncherException("BALA creation failed:" + e.getMessage());
        } catch (IOException e) {
            throw createLauncherException("unable to retrieve the target directory: " + e.getMessage());
        }

        Path relativePathToExecutable;

        try {
            relativePathToExecutable = projectPath.relativize(emitResult.generatedArtifactPath());
        } catch (IllegalArgumentException e) {
            // For cases where a custom path is given
            relativePathToExecutable = projectPath.resolve(emitResult.generatedArtifactPath());
        }

        // Print the path of the BALA file
        if (relativePathToExecutable.toString().contains("..") ||
                relativePathToExecutable.toString().contains("." + File.separator)) {
            this.out.println("\t" + emitResult.generatedArtifactPath().toString());
        } else {
            this.out.println("\t" + relativePathToExecutable);
        }
    }

}
