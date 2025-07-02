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

import io.ballerina.projects.BuildOptions;
import io.ballerina.projects.Package;
import io.ballerina.projects.ProjectEnvironmentBuilder;
import io.ballerina.projects.ProjectException;
import io.ballerina.projects.TomlDocument;
import io.ballerina.projects.WorkspaceBallerinaToml;
import io.ballerina.projects.WorkspaceManifest;
import io.ballerina.projects.environment.Environment;
import io.ballerina.projects.environment.EnvironmentBuilder;
import io.ballerina.projects.internal.WorkspaceManifestBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.ballerina.projects.util.ProjectConstants.BALLERINA_TOML;

/**
 * Represents a Ballerina workspace identified by a BalWorkspace.toml file.
 * A Workspace can consist of multiple BuildProjects.
 *
 * @since 2201.13.0
 */
public class Workspace {
    private final List<Package> buildProjects;
    private final Path workspaceRoot;
    private final WorkspaceBallerinaToml workspaceBallerinaToml;
    private final BuildOptions buildOptions;
    private WorkspaceManifest workspaceManifest;

    /**
     * Private constructor to initialize a Workspace with multiple build projects.
     *
     * @param workspaceRoot The root directory of the workspace
     */
    private Workspace(Path workspaceRoot, TomlDocument tomlDocument, BuildOptions buildOptions) {
        this.workspaceRoot = workspaceRoot;
        this.workspaceBallerinaToml = WorkspaceBallerinaToml.from(tomlDocument, this);
        this.buildOptions = buildOptions;
        this.workspaceManifest = WorkspaceManifestBuilder.from(tomlDocument, workspaceRoot).manifest();
        this.buildProjects = loadProjects();
    }

    /**
     * Private constructor to initialize a Workspace with multiple build projects.
     *
     * @param workspaceRoot The root directory of the workspace
     */
    private Workspace(Path workspaceRoot, WorkspaceBallerinaToml workspaceBallerinaToml, List<Package> buildProjects,
                      BuildOptions buildOptions) {
        this.workspaceRoot = workspaceRoot;
        this.workspaceBallerinaToml = workspaceBallerinaToml;
        this.buildOptions = buildOptions;
        this.buildProjects = buildProjects;
    }

    /**
     * Creates a workspace from the given directory.
     *
     * @param workspacePath The root directory of the workspace
     * @return A Workspace created from the given directory
     */
    public static Workspace load(Path workspacePath) {
        return load(workspacePath, BuildOptions.builder().build());
    }

    /**
     * Creates a workspace from the given directory.
     *
     * @param workspacePath The root directory of the workspace
     * @return A Workspace created from the given directory
     */
    public static Workspace load(Path workspacePath, BuildOptions buildOptions) {
        // Validate the presence of BalWorkspace.toml
        Path workspaceConfig = workspacePath.resolve(BALLERINA_TOML);
        if (Files.notExists(workspaceConfig)) {
            throw new ProjectException("No " + BALLERINA_TOML + " found in the specified workspace directory: "
                    + workspacePath.toAbsolutePath());
        }
        try {
            TomlDocument tomlDocument = TomlDocument.from(BALLERINA_TOML,
                    Files.readString(workspacePath.resolve(BALLERINA_TOML)));
            return new Workspace(workspacePath, tomlDocument, buildOptions);
        } catch (IOException e) {
            throw new ProjectException("Error reading " + BALLERINA_TOML + " file in workspace: "
                    + workspacePath.toAbsolutePath(), e);
        }
    }

    /**
     * Retrieves the list of BuildProject instances in this Workspace.
     *
     * @return A list of BuildProject instances
     */
    public List<Package> packages() {
        return buildProjects;
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    public WorkspaceBallerinaToml ballerinaToml() {
        return workspaceBallerinaToml;
    }

    public WorkspaceManifest workspaceManifest() {
        return workspaceManifest;
    }

    private List<Package> loadProjects() {
        List<Package> projects = new ArrayList<>();
        Environment environment = EnvironmentBuilder.getBuilder().setWorkspace(this).build();
        for (Path packagePath : this.workspaceManifest.packages()) {
            Path ballerinaTomlPath = packagePath.resolve(BALLERINA_TOML);
            if (Files.exists(ballerinaTomlPath)) {
                ProjectEnvironmentBuilder environmentBuilder = ProjectEnvironmentBuilder.getBuilder(environment);
                BuildProject project = BuildProject.load(environmentBuilder, packagePath, this.buildOptions, this);
                projects.add(project.currentPackage());
            }
        }
        return projects;
    }

    public static class Modifier {
        private final Workspace oldWorkspace;
        private WorkspaceBallerinaToml workspaceBallerinaToml;
        private final List<Package> buildProjects;

        private Modifier(Workspace oldWorkspace) {
            this.oldWorkspace = oldWorkspace;
            this.workspaceBallerinaToml = oldWorkspace.ballerinaToml();
            this.buildProjects = oldWorkspace.buildProjects;
        }

        public Modifier updateBalWorkspaceToml(WorkspaceBallerinaToml workspaceBallerinaToml) {
            this.workspaceBallerinaToml = workspaceBallerinaToml;
            return this;
        }

        public Modifier addProject(Package buildProject) {
            if (!this.buildProjects.contains(buildProject)) {
                this.buildProjects.add(buildProject);
            }
            return this;
        }

        public Modifier removeProject(BuildProject buildProject) {
            this.buildProjects.remove(buildProject);
            return this;
        }

        public Workspace apply() {
            if (this.workspaceBallerinaToml != this.oldWorkspace.workspaceBallerinaToml) {
                return new Workspace(oldWorkspace.workspaceRoot, workspaceBallerinaToml.tomlDocument(),
                        oldWorkspace.buildOptions);
            }
            return new Workspace(oldWorkspace.workspaceRoot, this.workspaceBallerinaToml, buildProjects, oldWorkspace.buildOptions);
        }
    }
}
