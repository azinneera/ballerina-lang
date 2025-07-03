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
package io.ballerina.projects;

import com.google.gson.JsonSyntaxException;
import io.ballerina.projects.buildtools.ToolContext;
import io.ballerina.projects.directory.BuildProject;
import io.ballerina.projects.environment.Environment;
import io.ballerina.projects.environment.EnvironmentBuilder;
import io.ballerina.projects.environment.ProjectEnvironment;
import io.ballerina.projects.environment.ResolutionOptions;
import io.ballerina.projects.internal.WorkspaceDependencyGraphBuilder;
import io.ballerina.projects.internal.WorkspaceManifestBuilder;
import io.ballerina.projects.internal.model.BuildJson;
import io.ballerina.projects.util.FileUtils;
import io.ballerina.projects.util.ProjectUtils;
import org.wso2.ballerinalang.util.RepoUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.ballerina.projects.util.ProjectConstants.BALLERINA_TOML;
import static io.ballerina.projects.util.ProjectConstants.BUILD_FILE;
import static io.ballerina.projects.util.ProjectUtils.createBuildFile;
import static io.ballerina.projects.util.ProjectUtils.readBuildJson;
import static io.ballerina.projects.util.ProjectUtils.writeDependencies;

/**
 * Represents a Ballerina workspace identified by a BalWorkspace.toml file.
 * A Workspace can consist of multiple BuildProjects.
 *
 * @since 2201.13.0
 */
public class Workspace {
    private final List<Project> projectList;
    private final Path workspaceRoot;
    private final WorkspaceBallerinaToml workspaceBallerinaToml;
    private final BuildOptions buildOptions;
    private WorkspaceManifest workspaceManifest;
    private final Map<PackageId, Map<PackageManifest.Tool.Field, ToolContext>> toolContextMap;
    private Environment environment;

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
        this.projectList = new ArrayList<>();
        this.toolContextMap = new HashMap<>();
        loadProjects();
    }

    /**
     * Private constructor to initialize a Workspace with multiple build projects.
     *
     * @param workspaceRoot The root directory of the workspace
     */
    private Workspace(Path workspaceRoot, WorkspaceBallerinaToml workspaceBallerinaToml,
                      List<Project> projects, BuildOptions buildOptions,
                      Map<PackageId, Map<PackageManifest.Tool.Field, ToolContext>> toolContextMap) {
        this.workspaceRoot = workspaceRoot;
        this.workspaceBallerinaToml = workspaceBallerinaToml;
        this.buildOptions = buildOptions;
        this.projectList = projects;
        this.toolContextMap = toolContextMap;
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
        return projectList.stream().map(Project::currentPackage).collect(Collectors.toList());
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

    public Path target(PackageId packageId) {
        Package aPackage = this.projectList.stream().filter(project ->
                        project.currentPackage().packageId().equals(packageId)).findFirst()
                .orElseThrow(() -> new ProjectException("Package with ID '" + packageId + "' not found in workspace"))
                .currentPackage();
        return aPackage.project().targetDir();
    }

    public BuildOptions buildOptions(PackageId packageId) {
        return this.projectList.stream()
                .filter(project -> project.currentPackage().packageId().equals(packageId))
                .findFirst()
                .orElseThrow(() -> new ProjectException("Package with ID '" + packageId + "' not found in workspace"))
                .currentPackage()
                .project()
                .buildOptions();
    }

    public void setToolContextMap(PackageId packageId, Map<PackageManifest.Tool.Field, ToolContext> toolContextMap) {
        this.toolContextMap.put(packageId, toolContextMap);
    }

    public Map<PackageManifest.Tool.Field, ToolContext> toolContextMap(PackageId packageId) {
        return this.toolContextMap.getOrDefault(packageId, new HashMap<>());
    }

    private void loadProjects() {
        for (Path packagePath : this.workspaceManifest.packages()) {
            Path ballerinaTomlPath = packagePath.resolve(BALLERINA_TOML);
            if (Files.exists(ballerinaTomlPath)) {
                BuildProject project = loadProject(packagePath, environment(), this.buildOptions);
                this.projectList.add(project);
            }
        }
    }

    private BuildProject loadProject(Path packagePath, Environment environment,
                                     BuildOptions buildOptions) {
        ProjectEnvironmentBuilder environmentBuilder = ProjectEnvironmentBuilder.getBuilder(environment);
        BuildProject project = BuildProject.load(environmentBuilder, packagePath, buildOptions, this);
        return project;
    }

    public Path sourceRoot(PackageId packageId) {
        return this.projectList.stream()
                .filter(project -> project.currentPackage().packageId().equals(packageId))
                .findFirst()
                .orElseThrow(() -> new ProjectException("Package with ID '" + packageId + "' not found in workspace"))
                .currentPackage()
                .project()
                .sourceRoot();
    }

    public DependencyGraph<ResolvedPackageDependency> dependencyGraph() {
        WorkspaceDependencyGraphBuilder graphBuilder = new WorkspaceDependencyGraphBuilder();
        for (Project project : this.projectList) {
            Package pkg = project.currentPackage();
            Collection<ResolvedPackageDependency> directDependencies = pkg
                    .getResolution(ResolutionOptions.builder().setOffline(true).build())
                    .dependencyGraph()
                    .getDirectDependencies(new ResolvedPackageDependency(pkg, PackageDependencyScope.DEFAULT));
            addDependencies(pkg, directDependencies, graphBuilder);
        }
        return graphBuilder.buildGraph();
    }

    private static void addDependencies(Package pkg, Collection<ResolvedPackageDependency> directDependencies,
                                        WorkspaceDependencyGraphBuilder graphBuilder) {
        for (ResolvedPackageDependency directDependency : directDependencies) {
            if (directDependency.packageInstance().project().kind() == ProjectKind.BUILD_PROJECT) {
                graphBuilder.addPackage(pkg);
                graphBuilder.addDependency(pkg, directDependency.packageInstance());
                addDependencies(directDependency.packageInstance(), directDependency.packageInstance().getResolution()
                        .dependencyGraph().getDirectDependencies(directDependency), graphBuilder);
            }
        }
    }

    public Kind kind() {
        return Kind.MULTI_PACKAGE;
    }

    public Environment environment() {
        if (environment == null) {
            environment = EnvironmentBuilder.getBuilder().setWorkspace(this).build();
        }
        return environment;
    }

    public Modifier modify() {
        return new Modifier(this);
    }

    public void save() {
        if (this.kind().equals(Kind.SINGLE_PACKAGE) || this.kind().equals(Kind.MULTI_PACKAGE)) {
            for (Project project : this.projectList) {
                Package pkg = project.currentPackage();
                Path buildFilePath = target(pkg.packageId()).resolve(BUILD_FILE);
                boolean shouldUpdate = pkg.getResolution().autoUpdate();

                // if build file does not exists
                if (!buildFilePath.toFile().exists()) {
                    createBuildFile(buildFilePath);
                    writeBuildFile(pkg, buildFilePath);
                    writeDependencies(pkg);
                } else {
                    BuildJson buildJson = null;
                    try {
                        buildJson = readBuildJson(buildFilePath);
                    } catch (JsonSyntaxException | IOException e) {
                        // ignore
                    }
                    // need to update Dependencies toml
                    writeDependencies(pkg);

                    // check whether buildJson is null and last updated time has expired
                    if (buildJson != null && !shouldUpdate) {
                        buildJson.setLastBuildTime(System.currentTimeMillis());
                        Map<String, Long> lastModifiedTime = new HashMap<>();
                        lastModifiedTime.put(pkg.packageName().value(),
                                FileUtils.lastModifiedTimeOfBalProject(pkg.project().sourceRoot));
                        buildJson.setLastModifiedTime(lastModifiedTime);

                        ProjectUtils.writeBuildFile(buildFilePath, buildJson);
                    } else {
                        writeBuildFile(pkg, buildFilePath);
                    }
                }
            }

        }
    }

    private void writeBuildFile(Package pkg, Path buildFilePath) {
        Map<String, Long> lastModifiedTime = new HashMap<>();
        lastModifiedTime.put(pkg.packageName().value(),
                FileUtils.lastModifiedTimeOfBalProject(pkg.project().sourceRoot));

        BuildJson buildJson = new BuildJson(System.currentTimeMillis(), System.currentTimeMillis(),
                RepoUtils.getBallerinaShortVersion(), lastModifiedTime);
        ProjectUtils.writeBuildFile(buildFilePath, buildJson);
    }

    public Package getPackage(PackageId packageId) {
        return this.projectList.stream()
                .map(Project::currentPackage)
                .filter(pkg -> pkg.packageId().equals(packageId))
                .findFirst()
                .orElseThrow(() -> new ProjectException("Package with ID '" + packageId + "' not found in workspace"));
    }

    public ProjectEnvironment packageEnvironmentContext(PackageId packageId) {
        return this.projectList.stream()
                .filter(project -> project.currentPackage().packageId().equals(packageId))
                .findFirst()
                .orElseThrow(() -> new ProjectException("Package with ID '" + packageId + "' not found in workspace"))
                .projectEnvironmentContext();
    }

    public static class Modifier {
        private final Workspace oldWorkspace;
        private WorkspaceBallerinaToml workspaceBallerinaToml;
        private final List<Project> projectsList;

        private Modifier(Workspace oldWorkspace) {
            this.oldWorkspace = oldWorkspace;
            this.workspaceBallerinaToml = oldWorkspace.ballerinaToml();
            this.projectsList = oldWorkspace.projectList;
        }

        public Modifier updateBalWorkspaceToml(WorkspaceBallerinaToml workspaceBallerinaToml) {
            this.workspaceBallerinaToml = workspaceBallerinaToml;
            return this;
        }

        public Modifier addPackage(PackageConfig packageConfig) {
            Project project = oldWorkspace.loadProject(packageConfig.packagePath(), oldWorkspace.environment(),
                    oldWorkspace.buildOptions);

            if (this.projectsList.stream().anyMatch(prj -> prj.currentPackage().packageId()
                    .equals(packageConfig.packageId()))) {
                throw new ProjectException("Package with ID '" + packageConfig.packageId()
                        + "' already exists in the workspace");
            }
            this.projectsList.add(project);
            return this;
        }

        public Modifier removePackage(PackageId packageId) {
            Project project = this.projectsList.stream().filter(prj -> prj.currentPackage().packageId()
                    .equals(packageId)).findFirst().orElseThrow(() -> new ProjectException(
                    "Package with ID '" + packageId + "' not found in workspace"));

            this.projectsList.remove(project);
            io.ballerina.projects.environment.PackageCache environmentPackageCache =
                    oldWorkspace.environment().getService(io.ballerina.projects.environment.PackageCache.class);
            environmentPackageCache.removePackage(project.currentPackage().packageId());
            return this;
        }

        public Workspace apply() {
            if (this.workspaceBallerinaToml != this.oldWorkspace.workspaceBallerinaToml) {
                return new Workspace(oldWorkspace.workspaceRoot, workspaceBallerinaToml.tomlDocument(),
                        oldWorkspace.buildOptions);
            }
            return new Workspace(oldWorkspace.workspaceRoot, this.workspaceBallerinaToml, projectsList,
                    oldWorkspace.buildOptions, oldWorkspace.toolContextMap);
        }
    }

    public enum Kind {
        SINGLE_PACKAGE,
        MULTI_PACKAGE,
        BALA,
        SINGLE_FILE
    }
}
