/*
 * Copyright (c) 2025, WSO2 LLC. (https://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.ballerina.projects.directory;

import io.ballerina.projects.ProjectException;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.ballerina.projects.util.ProjectConstants.BALLERINA_TOML;
import static io.ballerina.projects.util.ProjectConstants.BAL_WORKSPACE_TOML;

/**
 * Represents a Ballerina workspace identified by a BalWorkspace.toml file.
 * A Workspace can consist of multiple BuildProjects.
 */
public class Workspace {
    private final List<BuildProject> buildProjects;

    /**
     * Private constructor to initialize a Workspace with multiple build projects.
     *
     * @param buildProjects List of build projects
     */
    private Workspace(List<BuildProject> buildProjects) {
        this.buildProjects = buildProjects;
    }

    /**
     * Creates a workspace from the given directory.
     *
     * @param workspacePath The root directory of the workspace
     * @return A Workspace created from the given directory
     */
    public static Workspace from(Path workspacePath) {
        // Validate the presence of BalWorkspace.toml
        Path workspaceConfig = workspacePath.resolve(BAL_WORKSPACE_TOML);
        if (Files.notExists(workspaceConfig)) {
            throw new ProjectException("No " + BAL_WORKSPACE_TOML + " found in the specified workspace directory: "
                    + workspacePath.toAbsolutePath());
        }

        List<BuildProject> buildProjects = loadProjects(workspacePath);
        return new Workspace(buildProjects);
    }

    /**
     * Retrieves the list of BuildProject instances in this Workspace.
     *
     * @return A list of BuildProject instances
     */
    public List<BuildProject> buildProjects() {
        return buildProjects;
    }

    /**
     * Scans the workspace directory and identifies Ballerina projects by checking for the presence
     * of Ballerina.toml files in subdirectories.
     *
     * @param workspaceDir The root workspace directory
     * @return A list of BuildProject instances discovered in the workspace
     */
    private static List<BuildProject> loadProjects(Path workspaceDir) {
        List<BuildProject> projects = new ArrayList<>();
        File[] subdirectories = workspaceDir.toFile().listFiles(File::isDirectory);

        if (subdirectories == null) {
            throw new ProjectException("Could not list directories in workspace: " + workspaceDir.toAbsolutePath());
        }

        for (File subdirectory : subdirectories) {
            File ballerinaToml = new File(subdirectory, BALLERINA_TOML);
            if (ballerinaToml.exists()) {
                BuildProject project = BuildProject.load(subdirectory.toPath());
                projects.add(project);
            }
        }
        return projects;
    }
}