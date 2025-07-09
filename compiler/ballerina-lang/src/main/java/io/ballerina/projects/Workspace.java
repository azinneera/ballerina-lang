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
import io.ballerina.projects.directory.SingleFileProject;
import io.ballerina.projects.environment.Environment;
import io.ballerina.projects.environment.EnvironmentBuilder;
import io.ballerina.projects.environment.ProjectEnvironment;
import io.ballerina.projects.environment.ResolutionOptions;
import io.ballerina.projects.internal.WorkspaceDependencyGraphBuilder;
import io.ballerina.projects.internal.WorkspaceManifestBuilder;
import io.ballerina.projects.internal.model.BuildJson;
import io.ballerina.projects.util.FileUtils;
import io.ballerina.projects.util.ProjectPaths;
import io.ballerina.projects.util.ProjectUtils;
import org.wso2.ballerinalang.util.RepoUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final Set<Project> projectSet;
    private final Path workspaceRoot;
    private final WorkspaceBallerinaToml workspaceBallerinaToml;
    private final BuildOptions buildOptions;
    private WorkspaceManifest workspaceManifest;
    private Map<PackageDescriptor, Map<PackageManifest.Tool.Field, ToolContext>> toolContextMap;
    private Environment environment;
    private DependencyGraph<PackageDescriptor> dependencyGraph;

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
        this.projectSet = new HashSet<>();
        loadProjects();
        this.dependencyGraph = buildDependencyGraph();
        this.toolContextMap = new HashMap<>();
    }

    /**
     * Private constructor to initialize a Workspace with multiple build projects.
     *
     * @param workspaceRoot The root directory of the workspace
     */
    private Workspace(Path workspaceRoot, WorkspaceBallerinaToml workspaceBallerinaToml,
                      Set<Project> projects, BuildOptions buildOptions,
                      Map<PackageDescriptor, Map<PackageManifest.Tool.Field, ToolContext>> toolContextMap) {
        this.workspaceRoot = workspaceRoot;
        this.workspaceBallerinaToml = workspaceBallerinaToml;
        this.buildOptions = buildOptions;
        this.projectSet = projects;
        this.toolContextMap = toolContextMap;
        this.dependencyGraph = buildDependencyGraph();
    }

    private Workspace(Path projectPath, boolean isSingleFile, BuildOptions buildOptions) {
        this.environment = EnvironmentBuilder.getBuilder().build();
        Project project = loadProject(projectPath, buildOptions, isSingleFile);
        this.workspaceRoot = project.sourceRoot;
        this.workspaceBallerinaToml = null;
        this.buildOptions = project.buildOptions();
        this.workspaceManifest = null;
        this.projectSet = Set.of(project);
        this.dependencyGraph = buildDependencyGraph();
        this.toolContextMap = new HashMap<>();
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
        if (FileUtils.hasExtension(workspacePath)) {
            if (ProjectPaths.isBalFile(workspacePath)) {
                return new Workspace(workspacePath, true, buildOptions);
            }
            throw new ProjectException("Provided path is not a Ballerina source file: "
                    + workspacePath.toAbsolutePath());
        }
        // Validate the presence of BalWorkspace.toml
        Path workspaceConfig = workspacePath.resolve(BALLERINA_TOML);
        if (Files.notExists(workspaceConfig)) {
            throw new ProjectException("Provided path is not a valid Ballerina project: "
                    + workspacePath.toAbsolutePath() + ". Missing '" + BALLERINA_TOML + "' file.");
        }
        if (ProjectPaths.isWorkspaceRoot(workspacePath)) {
            try {
                TomlDocument tomlDocument = TomlDocument.from(BALLERINA_TOML,
                        Files.readString(workspacePath.resolve(BALLERINA_TOML)));
                return new Workspace(workspacePath, tomlDocument, buildOptions);
            } catch (IOException e) {
                throw new ProjectException("Error reading " + BALLERINA_TOML + " file in workspace: "
                        + workspacePath.toAbsolutePath(), e);
            }
        }

        if (ProjectPaths.isPackageRoot(workspacePath)) {
            // If the given path is a package root, load the project directly
            return new Workspace(workspacePath, false, buildOptions);
        }

        // If the given path is not a workspace root or package root, throw an exception
        throw new ProjectException("The specified path is neither a valid project: " + workspacePath.toAbsolutePath());
    }

    /**
     * Retrieves the list of BuildProject instances in this Workspace.
     *
     * @return A list of BuildProject instances
     */
    public List<Package> packages() {
        return projectSet.stream().map(Project::currentPackage).collect(Collectors.toList());
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    public Optional<WorkspaceBallerinaToml> ballerinaToml() {
        return Optional.ofNullable(workspaceBallerinaToml);
    }

    public WorkspaceManifest workspaceManifest() {
        return workspaceManifest;
    }

    public Path target(PackageDescriptor descriptor) {
        Package aPackage = this.projectSet.stream().filter(project ->
                        project.currentPackage().descriptor().equals(descriptor)).findFirst()
                .orElseThrow(() -> new ProjectException("Package with ID '" + descriptor + "' not found in workspace"))
                .currentPackage();
        return aPackage.project().targetDir();
    }

    public BuildOptions buildOptions(PackageDescriptor descriptor) {
        return this.projectSet.stream()
                .filter(project -> project.currentPackage().descriptor().equals(descriptor))
                .findFirst()
                .orElseThrow(() -> new ProjectException("Package with ID '" + descriptor + "' not found in workspace"))
                .currentPackage()
                .project()
                .buildOptions();
    }

    public void setToolContextMap(PackageDescriptor packageDescriptor,
                                  Map<PackageManifest.Tool.Field, ToolContext> toolContextMap) {
        this.toolContextMap.put(packageDescriptor, toolContextMap);
    }

    public Map<PackageManifest.Tool.Field, ToolContext> toolContextMap(PackageDescriptor descriptor) {
        return this.toolContextMap.getOrDefault(descriptor, new HashMap<>());
    }

    private void loadProjects() {
        for (Path packagePath : this.workspaceManifest.packages()) {
            Path ballerinaTomlPath = packagePath.resolve(BALLERINA_TOML);
            if (Files.exists(ballerinaTomlPath)) {
                Project project = loadProject(packagePath, this.buildOptions, false);
                this.projectSet.add(project);
            }
        }
    }

    private Project loadProject(Path packagePath, BuildOptions buildOptions,
                                     boolean isSingleFile) {
        ProjectEnvironmentBuilder environmentBuilder = ProjectEnvironmentBuilder.getBuilder(environment());
        if (isSingleFile) {
            return SingleFileProject.load(environmentBuilder, packagePath, buildOptions, this);
        }
        return BuildProject.load(environmentBuilder, packagePath, buildOptions, this);
    }

    public Path sourceRoot(PackageDescriptor descriptor) {
        return this.projectSet.stream()
                .filter(project -> project.currentPackage().descriptor().equals(descriptor))
                .findFirst()
                .orElseThrow(() -> new ProjectException("Package '" + descriptor + "' not found in workspace"))
                .currentPackage()
                .project()
                .sourceRoot().toAbsolutePath().normalize();
    }

    public DependencyGraph<PackageDescriptor> dependencyGraph() {
        if (dependencyGraph == null) {
            dependencyGraph = buildDependencyGraph();
        }
        return dependencyGraph;
    }

    private DependencyGraph<PackageDescriptor> buildDependencyGraph() {
        WorkspaceDependencyGraphBuilder graphBuilder = new WorkspaceDependencyGraphBuilder();
        for (Project project : this.projectSet) {
            Package pkg = project.currentPackage();
            graphBuilder.addPackage(pkg);
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
        graphBuilder.addPackage(pkg);
        for (ResolvedPackageDependency directDependency : directDependencies) {
            if (directDependency.packageInstance().project().kind() == ProjectKind.BUILD_PROJECT) {
                graphBuilder.addDependency(pkg, directDependency.packageInstance());
                addDependencies(directDependency.packageInstance(), directDependency.packageInstance().getResolution()
                        .dependencyGraph().getDirectDependencies(directDependency), graphBuilder);
            }
        }
    }

    public ProjectKind kind() {
        if (this.projectSet.size() > 1) {
            return ProjectKind.WORKSPACE_PROJECT;
        }
        if (this.projectSet.iterator().next().kind().equals(ProjectKind.BUILD_PROJECT)) {
            return ProjectKind.BUILD_PROJECT;
        } else if (this.projectSet.iterator().next().kind().equals(ProjectKind.BALA_PROJECT)) {
            return ProjectKind.BALA_PROJECT;
        }
        return ProjectKind.SINGLE_FILE_PROJECT;
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
        if (this.kind().equals(ProjectKind.WORKSPACE_PROJECT) || this.kind().equals(ProjectKind.BUILD_PROJECT)) {
            for (Project project : this.projectSet) {
                Package pkg = project.currentPackage();
                Path buildFilePath = target(pkg.descriptor()).resolve(BUILD_FILE);
                boolean shouldUpdate = pkg.getResolution().autoUpdate();

                // if build file does not exist
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

    public Package getPackage(PackageDescriptor descriptor) {
        return this.projectSet.stream()
                .map(Project::currentPackage)
                .filter(pkg -> pkg.descriptor().equals(descriptor))
                .findFirst()
                .orElseThrow(() -> new ProjectException("Package '" + descriptor + "' not found in workspace"));
    }

    public ProjectEnvironment packageEnvironmentContext(PackageDescriptor descriptor) {
        return this.projectSet.stream()
                .filter(project -> project.currentPackage().descriptor().equals(descriptor))
                .findFirst()
                .orElseThrow(() -> new ProjectException("Package '" + descriptor + "' not found in workspace"))
                .projectEnvironmentContext();
    }

    public void removePackage(PackageDescriptor descriptor) {
        if (kind().equals(ProjectKind.SINGLE_FILE_PROJECT)) {
            throw new UnsupportedOperationException("Cannot remove packages from a single file project");
        }

        if (kind().equals(ProjectKind.BALA_PROJECT)) {
            throw new ProjectException("Cannot add packages from a BALA project");
        }

        Project project = this.projectSet.stream().filter(prj -> prj.currentPackage().descriptor()
                .equals(descriptor)).findFirst().orElseThrow(() -> new ProjectException(
                "Package with ID '" + descriptor + "' not found in workspace"));

        // Reset the dependant packages
        if (this.dependencyGraph != null) {
            PackageDescriptor pkgNode = dependencyGraph.toTopologicallySortedList().stream()
                    .filter(descriptor1 -> descriptor1.equals(descriptor)).findFirst().orElseThrow();
            io.ballerina.projects.environment.PackageCache environmentPackageCache =
                    this.environment.getService(io.ballerina.projects.environment.PackageCache.class);

            for (PackageDescriptor dependent : dependencyGraph.getAllDependents(pkgNode)) {
                Project dependentProject = getPackage(dependent).project();
                dependentProject.clearCaches();
                environmentPackageCache.removePackage(dependentProject.currentPackage().descriptor());
            }
            this.dependencyGraph = null; // Reset the dependency graph
        }
        this.projectSet.remove(project);
    }

    public void addPackage(PackageConfig packageConfig) {
        if (kind().equals(ProjectKind.SINGLE_FILE_PROJECT)) {
            throw new UnsupportedOperationException("Cannot add packages to a single file project");
        }

        if (kind().equals(ProjectKind.BALA_PROJECT)) {
            throw new ProjectException("Cannot add packages to a BALA project");
        }

        Project project = loadProject(packageConfig.packagePath(), buildOptions, false);

        if (this.projectSet.stream().anyMatch(prj -> prj.currentPackage().packageId()
                .equals(packageConfig.packageId()))) {
            throw new ProjectException("Package with ID '" + packageConfig.packageId()
                    + "' already exists in the workspace");
        }
        this.projectSet.add(project);
        this.dependencyGraph = null; // Reset the dependency graph
    }

    void resetDependencyGraph() {
        this.dependencyGraph = null; // Reset the dependency graph
    }

    public static class Modifier {
        private final Workspace workspace;
        private WorkspaceBallerinaToml workspaceBallerinaToml;
        private final Set<Project> projects;
        private final DependencyGraph<PackageDescriptor> dependencyGraph;

        private Modifier(Workspace oldWorkspace) {
            this.workspace = oldWorkspace;
            this.workspaceBallerinaToml = oldWorkspace.ballerinaToml().orElseThrow();
            this.projects = oldWorkspace.projectSet;
            this.dependencyGraph = oldWorkspace.dependencyGraph();
        }

        public Modifier updateBalWorkspaceToml(WorkspaceBallerinaToml workspaceBallerinaToml) {
            this.workspaceBallerinaToml = workspaceBallerinaToml;
            return this;
        }

        public Modifier addPackage(PackageConfig packageConfig) {
            Project project = workspace.loadProject(packageConfig.packagePath(),
                    workspace.buildOptions, false);

            if (this.projects.stream().anyMatch(prj -> prj.currentPackage().packageId()
                    .equals(packageConfig.packageId()))) {
                throw new ProjectException("Package with ID '" + packageConfig.packageId()
                        + "' already exists in the workspace");
            }
            this.projects.add(project);
            return this;
        }

        public Modifier removePackage(PackageDescriptor descriptor) {
            Project project = this.projects.stream().filter(prj -> prj.currentPackage().descriptor()
                    .equals(descriptor)).findFirst().orElseThrow(() -> new ProjectException(
                    "Package with ID '" + descriptor + "' not found in workspace"));

            // Reset the dependant packages
            PackageDescriptor pkgNode = dependencyGraph.toTopologicallySortedList().stream()
                    .filter(dep -> dep.equals(descriptor)).findFirst().orElseThrow();
            io.ballerina.projects.environment.PackageCache environmentPackageCache =
                    workspace.environment().getService(io.ballerina.projects.environment.PackageCache.class);

            for (PackageDescriptor dependent : dependencyGraph.getAllDependents(pkgNode)) {
                Project dependentProject = workspace.getPackage(dependent).project();
                dependentProject.clearCaches();
                environmentPackageCache.removePackage(dependentProject.currentPackage().descriptor());
            }
            this.projects.remove(project);
            environmentPackageCache.removePackage(project.currentPackage().descriptor());

            return this;
        }

        public Workspace apply() {
            if (this.workspaceBallerinaToml != this.workspace.workspaceBallerinaToml) {
                return new Workspace(workspace.workspaceRoot, workspaceBallerinaToml.tomlDocument(),
                        workspace.buildOptions);
            }
            return new Workspace(workspace.workspaceRoot, this.workspaceBallerinaToml, projects,
                    workspace.buildOptions, workspace.toolContextMap);
        }
    }
}
