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

import io.ballerina.projects.BalWorkspaceToml;
import io.ballerina.projects.BuildOptions;
import io.ballerina.projects.DependencyGraph;
import io.ballerina.projects.PackageManifest;
import io.ballerina.projects.ProjectEnvironmentBuilder;
import io.ballerina.projects.ProjectException;
import io.ballerina.projects.TomlDocument;
import io.ballerina.projects.environment.Environment;
import io.ballerina.projects.environment.EnvironmentBuilder;
import io.ballerina.projects.internal.WorkspaceDependencyGraphBuilder;
import io.ballerina.toml.semantic.TomlType;
import io.ballerina.toml.semantic.ast.TomlTableNode;
import io.ballerina.toml.semantic.ast.TopLevelNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.ballerina.projects.util.ProjectConstants.BALLERINA_TOML;
import static io.ballerina.projects.util.ProjectConstants.BAL_WORKSPACE_TOML;
import static io.ballerina.projects.util.TomlUtil.getStringArrayFromTableNode;

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
    private final BalWorkspaceToml balWorkspaceToml;
    private final BuildOptions buildOptions;

    /**
     * Private constructor to initialize a Workspace with multiple build projects.
     *
     * @param workspaceRoot The root directory of the workspace
     */
    private Workspace(Path workspaceRoot, TomlDocument tomlDocument, BuildOptions buildOptions) {
        this.workspaceRoot = workspaceRoot;
        this.balWorkspaceToml = BalWorkspaceToml.from(tomlDocument, this);
        this.buildOptions = buildOptions;
        this.buildProjects = loadProjects(workspaceRoot, balWorkspaceToml);
    }

    /**
     * Private constructor to initialize a Workspace with multiple build projects.
     *
     * @param workspaceRoot The root directory of the workspace
     */
    private Workspace(Path workspaceRoot, BalWorkspaceToml balWorkspaceToml, List<BuildProject> buildProjects,
                      BuildOptions buildOptions) {
        this.workspaceRoot = workspaceRoot;
        this.balWorkspaceToml = balWorkspaceToml;
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
        Path workspaceConfig = workspacePath.resolve(BAL_WORKSPACE_TOML);
        if (Files.notExists(workspaceConfig)) {
            throw new ProjectException("No " + BAL_WORKSPACE_TOML + " found in the specified workspace directory: "
                    + workspacePath.toAbsolutePath());
        }
        try {
            TomlDocument tomlDocument = TomlDocument.from(BAL_WORKSPACE_TOML,
                    Files.readString(workspacePath.resolve(BAL_WORKSPACE_TOML)));
            return new Workspace(workspacePath, tomlDocument, buildOptions);
        } catch (IOException e) {
            throw new ProjectException("Error reading " + BAL_WORKSPACE_TOML + " file in workspace: "
                    + workspacePath.toAbsolutePath(), e);
        }
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

    public BalWorkspaceToml balWorkspaceToml() {
        return balWorkspaceToml;
    }

    private List<BuildProject> loadProjects(Path workspaceDir, BalWorkspaceToml balWorkspaceToml) {
        List<BuildProject> projects = new ArrayList<>();
        List<String> projectPaths = getProjectPaths(balWorkspaceToml);
        Environment environment = EnvironmentBuilder.getBuilder().setWorkspace(this).build();
        for (String projectPath : projectPaths) {
            Path projectRoot = workspaceDir.resolve(projectPath);
            Path ballerinaTomlPath = projectRoot.resolve(BALLERINA_TOML);
            if (Files.exists(ballerinaTomlPath)) {
                ProjectEnvironmentBuilder environmentBuilder = ProjectEnvironmentBuilder.getBuilder(environment);
                BuildProject project = BuildProject.load(environmentBuilder, projectRoot, this.buildOptions, this);
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

    private List<String> getProjectPaths(BalWorkspaceToml balWorkspaceToml) {
        TomlTableNode tomlAstNode = balWorkspaceToml.tomlAstNode();
        if (!tomlAstNode.entries().isEmpty()) {
            TopLevelNode topLevelPkgNode = tomlAstNode.entries().get("workspace");
            if (topLevelPkgNode != null && topLevelPkgNode.kind() == TomlType.TABLE) {
                TomlTableNode pkgNode = (TomlTableNode) topLevelPkgNode;
                return getStringArrayFromTableNode(pkgNode, "packages");
            }
        }

        return new ArrayList<>();
    }

    public static class Modifier {
        private final Workspace oldWorkspace;
        private BalWorkspaceToml balWorkspaceToml;
        private final List<BuildProject> buildProjects;

        private Modifier(Workspace oldWorkspace) {
            this.oldWorkspace = oldWorkspace;
            this.balWorkspaceToml = oldWorkspace.balWorkspaceToml();
            this.buildProjects = oldWorkspace.buildProjects;
        }

        public Modifier updateBalWorkspaceToml(BalWorkspaceToml balWorkspaceToml) {
            this.balWorkspaceToml = balWorkspaceToml;
            return this;
        }

        public Modifier addProject(BuildProject buildProject) {
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
            if (this.balWorkspaceToml != this.oldWorkspace.balWorkspaceToml) {
                return new Workspace(oldWorkspace.workspaceRoot, balWorkspaceToml.tomlDocument(),
                        oldWorkspace.buildOptions);
            }
            return new Workspace(oldWorkspace.workspaceRoot, this.balWorkspaceToml, buildProjects, oldWorkspace.buildOptions);
        }
    }
}
