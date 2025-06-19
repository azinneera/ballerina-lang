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

import io.ballerina.projects.DependencyGraph;
import io.ballerina.projects.PackageManifest;
import io.ballerina.projects.ProjectEnvironmentBuilder;
import io.ballerina.projects.ProjectException;
import io.ballerina.projects.environment.Environment;
import io.ballerina.projects.environment.EnvironmentBuilder;
import io.ballerina.projects.internal.WorkspaceDependencyGraphBuilder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.ballerina.projects.util.ProjectConstants.BALLERINA_TOML;
import static io.ballerina.projects.util.ProjectConstants.BAL_WORKSPACE_TOML;

/**
 * Represents a Ballerina workspace identified by a BalWorkspace.toml file.
 * A Workspace can consist of multiple BuildProjects.
 *
 * @since 2201.13.0
 */
public class Workspace {
    private final List<BuildProject> buildProjects;
    private DependencyGraph<BuildProject> projectDependencyGraph;
    private final Path workspaceRoot;

    /**
     * Private constructor to initialize a Workspace with multiple build projects.
     *
     * @param workspaceRoot The root directory of the workspace
     */
    private Workspace(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
        this.buildProjects = loadProjects(workspaceRoot);
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
        return new Workspace(workspacePath);
    }

    /**
     * Retrieves the list of BuildProject instances in this Workspace.
     *
     * @return A list of BuildProject instances
     */
    public List<BuildProject> projects() {
        return buildProjects;
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    /**
     * Scans the workspace directory and identifies Ballerina projects by checking for the presence
     * of Ballerina.toml files in subdirectories.
     *
     * @param workspaceDir The root workspace directory
     * @return A list of BuildProject instances discovered in the workspace
     */
    private List<BuildProject> loadProjects(Path workspaceDir) {
        List<BuildProject> projects = new ArrayList<>();
        File[] subdirectories = workspaceDir.toFile().listFiles(File::isDirectory);
        if (subdirectories == null) {
            throw new ProjectException("Could not list directories in workspace: " + workspaceDir.toAbsolutePath());
        }
        Environment environment = EnvironmentBuilder.getBuilder().setWorkspace(this).build();

        for (File subdirectory : subdirectories) {
            File ballerinaToml = new File(subdirectory, BALLERINA_TOML);
            if (ballerinaToml.exists()) {
                ProjectEnvironmentBuilder environmentBuilder = ProjectEnvironmentBuilder.getBuilder(environment);
                BuildProject project = BuildProject.load(environmentBuilder, subdirectory.toPath(), this);
                projects.add(project);
            }
        }
        return projects;
    }

    public DependencyGraph<BuildProject> dependencyGraph() {
        if (this.projectDependencyGraph == null) {
            this.projectDependencyGraph = generateDependencyGraphMap();
        }
        return this.projectDependencyGraph;
    }

    private DependencyGraph<BuildProject> generateDependencyGraphMap() {
        WorkspaceDependencyGraphBuilder graphBuilder = new WorkspaceDependencyGraphBuilder();
        Map<Path, BuildProject> projectMap = new HashMap<>();
        for (BuildProject bp : this.buildProjects) {
            projectMap.put(bp.sourceRoot(), bp);
        }
        for (BuildProject project : this.buildProjects) {
            graphBuilder.addProject(project);
            for (PackageManifest.Dependency dependency : project.currentPackage().manifest().dependencies()) {
                dependency.path().ifPresent(dependencyPath -> {
                    if (!dependencyPath.isAbsolute()) {
                        dependencyPath = project.sourceRoot().resolve(dependencyPath).toAbsolutePath().normalize();
                    }
                    if (projectMap.containsKey(dependencyPath)) {
                        graphBuilder.addDependency(project, projectMap.get(dependencyPath));
                    }
                });
            }
        }
        return graphBuilder.buildGraph();
    }
}
