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
import io.ballerina.projects.BuildOptions;
import io.ballerina.projects.CodeGeneratorResult;
import io.ballerina.projects.CodeModifierResult;
import io.ballerina.projects.DependencyGraph;
import io.ballerina.projects.JBallerinaBackend;
import io.ballerina.projects.JvmTarget;
import io.ballerina.projects.Package;
import io.ballerina.projects.PackageCompilation;
import io.ballerina.projects.PackageDescriptor;
import io.ballerina.projects.PackageId;
import io.ballerina.projects.PackageManifest;
import io.ballerina.projects.PackageResolution;
import io.ballerina.projects.PlatformLibraryScope;
import io.ballerina.projects.Project;
import io.ballerina.projects.ProjectException;
import io.ballerina.projects.ProjectKind;
import io.ballerina.projects.ResolvedPackageDependency;
import io.ballerina.projects.SemanticVersion;
import io.ballerina.projects.Workspace;
import io.ballerina.projects.directory.SingleFileProject;
import io.ballerina.projects.environment.ResolutionOptions;
import io.ballerina.projects.internal.PackageDiagnostic;
import io.ballerina.projects.internal.ProjectDiagnosticErrorCode;
import io.ballerina.projects.util.ProjectUtils;
import io.ballerina.tools.diagnostics.Diagnostic;
import io.ballerina.tools.diagnostics.DiagnosticInfo;
import io.ballerina.tools.diagnostics.DiagnosticSeverity;
import org.ballerinalang.central.client.CentralClientConstants;
import org.wso2.ballerinalang.util.RepoUtils;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static io.ballerina.cli.launcher.LauncherUtils.createLauncherException;
import static io.ballerina.projects.internal.ProjectDiagnosticErrorCode.CORRUPTED_DEPENDENCIES_TOML;
import static io.ballerina.projects.util.ProjectConstants.DOT;
import static io.ballerina.projects.util.ProjectConstants.TOOL_DIAGNOSTIC_CODE_PREFIX;

/**
 * Task for compiling a package.
 *
 * @since 2.0.0
 */
public class CompileTask implements Task {
    private final transient PrintStream out;
    private final transient PrintStream err;
    private final boolean compileForBalPack;
    private final boolean compileForBalBuild;
    private final Path projectPath;
    private final boolean isPackageModified;
    private final boolean cachesEnabled;
    private long start = 0;
    List<Diagnostic> diagnostics = new ArrayList<>();

    public CompileTask(PrintStream out, PrintStream err) {
        this(out, err, false, false, true, false);
    }

    public CompileTask(PrintStream out,
                       PrintStream err,
                       boolean compileForBalPack,
                       boolean compileForBalBuild,
                       boolean isPackageModified,
                       boolean cachesEnabled) {
        this.out = out;
        this.err = err;
        this.compileForBalPack = compileForBalPack;
        this.compileForBalBuild = compileForBalBuild;
        this.isPackageModified = isPackageModified;
        this.cachesEnabled = cachesEnabled;
        this.projectPath = null;
    }

    public CompileTask(PrintStream out, PrintStream err, boolean compileForBalPack, boolean compileForBalBuild,
                       Path projectPath) {
        this.out = out;
        this.err = err;
        this.compileForBalPack = compileForBalPack;
        this.compileForBalBuild = compileForBalBuild;
        this.projectPath = projectPath;
        this.isPackageModified = true;
        this.cachesEnabled = false;

    }

    @Override
    public void execute(Project project) {
        try {
            // Print the source
            if (project instanceof SingleFileProject) {
                printPackageInfo(ProjectKind.SINGLE_FILE_PROJECT, project.currentPackage());
            } else {
                printPackageInfo(ProjectKind.BUILD_PROJECT, project.currentPackage());
            }

            // Validate the source
            validateProject(project.currentPackage());
            // Get the package resolution
            PackageResolution packageResolution = getResolution(project.currentPackage(), project.buildOptions());
            Set<String> packageImports = ProjectUtils.getPackageImports(project.currentPackage());

            // Run code generator and modifier plugins
            if (!packageResolution.diagnosticResult().hasErrors()) {
                runCodeGenerators(project.currentPackage(), project.buildOptions(),
                        project.currentPackage().workspace().kind());
                runCodeModifiers(project.currentPackage(), project.buildOptions(),
                        project.currentPackage().workspace().kind());
            }

            // Dump the package dependency graphs if required
            dumpRawGraphsIfRequired(project.currentPackage(), packageResolution, packageImports);
            // Report resolution diagnostics
            reportResolutionDiagnostics(project.currentPackage());
            // Compile the package
            getCompilationAndSave(project.currentPackage(), project.buildOptions());
        } catch (ProjectException e) {
            throw createLauncherException("compilation failed: " + e.getMessage());
        }
    }

    @Override
    public void execute(Workspace workspace) {
        try {
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
                PackageDescriptor packageDescriptor = packageDependency.packageInstance().descriptor();
                // Print the source
                printPackageInfo(workspace.kind(), workspace.getPackage(packageDescriptor));
                // Validate the source
                validateProject(workspace.getPackage(packageDescriptor));
                // Get the package resolution
                PackageResolution packageResolution = getResolution(workspace.getPackage(packageDescriptor),
                        workspace.buildOptions(packageDescriptor));
                Set<String> packageImports = ProjectUtils.getPackageImports(workspace.getPackage(packageDescriptor));

                // Run code generator and modifier plugins
                if (!packageResolution.diagnosticResult().hasErrors()) {
                    runCodeGenerators(workspace.getPackage(packageDescriptor), workspace.buildOptions(packageDescriptor),
                            workspace.kind());
                    runCodeModifiers(workspace.getPackage(packageDescriptor), workspace.buildOptions(packageDescriptor),
                            workspace.kind());
                }

                // Dump the package dependency graphs if required
                dumpRawGraphsIfRequired(workspace.getPackage(packageDescriptor), packageResolution, packageImports);
                // Report resolution diagnostics
                reportResolutionDiagnostics(workspace.getPackage(packageDescriptor));

                // Compile the package
                getCompilationAndSave(workspace.getPackage(packageDescriptor), workspace.buildOptions(packageDescriptor));
            }
        } catch (ProjectException e) {
            throw createLauncherException("compilation failed: " + e.getMessage());
        }
    }

    private void printPackageInfo(ProjectKind kind, Package pkg) {
        String sourceName;
        if (kind.equals(ProjectKind.SINGLE_FILE_PROJECT)) {
            sourceName = pkg.getDefaultModule().document(
                    pkg.getDefaultModule().documentIds().iterator().next()).name();
        } else {
            sourceName = pkg.packageOrg().toString() + "/" +
                    pkg.packageName().toString() + ":" +
                    pkg.packageVersion();
        }
        this.out.println("Compiling source");
        this.out.println("\t" + sourceName);
    }


    private void dumpRawGraphsIfRequired(Package pkg, PackageResolution packageResolution, Set<String> packageImports) {
        // We dump the raw graphs twice only if code generator/modifier plugins are engaged
        // since the package has changed now
        Set<String> newPackageImports = ProjectUtils.getPackageImports(pkg);
        ResolutionOptions resolutionOptions = ResolutionOptions.builder().setOffline(true).build();
        if (!packageImports.equals(newPackageImports)) {
            resolutionOptions = ResolutionOptions.builder().setOffline(false).build();
        }
        if (packageResolution != pkg.getResolution(resolutionOptions)) {
            packageResolution = pkg.getResolution();
            if (pkg.compilationOptions().dumpRawGraphs()) {
                packageResolution.dumpGraphs(out);
            }
        }
        if (pkg.compilationOptions().dumpGraph()) {
            packageResolution.dumpGraphs(out);
        }
    }

    private void getCompilationAndSave(Package pkg, BuildOptions buildOptions) {
        if (buildOptions.dumpBuildTime()) {
            start = System.currentTimeMillis();
        }

        Optional<Diagnostic> projectLoadingDiagnostic = ProjectUtils.getProjectLoadingDiagnostic().stream().filter(
                diagnostic -> diagnostic.diagnosticInfo().code().equals(
                        ProjectDiagnosticErrorCode.DEPRECATED_RESOURCES_STRUCTURE.diagnosticId())).findAny();

        projectLoadingDiagnostic.ifPresent(out::println);
        PackageCompilation packageCompilation = pkg.getCompilation();
        if (buildOptions.dumpBuildTime()) {
            BuildTime.getInstance().packageCompilationDuration = System.currentTimeMillis() - start;
            start = System.currentTimeMillis();
        }
        JBallerinaBackend jBallerinaBackend = JBallerinaBackend.from(packageCompilation, JvmTarget.JAVA_21);
        if (buildOptions.dumpBuildTime()) {
            BuildTime.getInstance().codeGenDuration = System.currentTimeMillis() - start;
        }

        // Report package compilation and backend diagnostics
        diagnostics.addAll(jBallerinaBackend.diagnosticResult().diagnostics(false));
        diagnostics.forEach(d -> {
            if (d.diagnosticInfo().code() == null || (!d.diagnosticInfo().code().equals(
                    ProjectDiagnosticErrorCode.BUILT_WITH_OLDER_SL_UPDATE_DISTRIBUTION.diagnosticId()) &&
                    !d.diagnosticInfo().code().startsWith(TOOL_DIAGNOSTIC_CODE_PREFIX))) {
                err.println(d);
            }
        });

        // Add tool resolution diagnostics to diagnostics
        diagnostics.addAll(pkg.getBuildToolResolution().getDiagnosticList());
        boolean hasErrors = false;
        for (Diagnostic d : diagnostics) {
            if (d.diagnosticInfo().severity().equals(DiagnosticSeverity.ERROR)) {
                hasErrors = true;
            }
        }
        if (hasErrors) {
            throw createLauncherException("compilation contains errors");
        }
        pkg.workspace().save();
    }

    private void reportResolutionDiagnostics(Package pkg) {
        // Print diagnostics and exit when version incompatibility issues are found in package resolution.
        if (pkg.getResolution().diagnosticResult().hasErrors()) {
            // add resolution diagnostics
            diagnostics.addAll(pkg.getResolution().diagnosticResult().diagnostics());
            // add package manifest diagnostics
            diagnostics.addAll(pkg.manifest().diagnostics().diagnostics());
            // add dependency manifest diagnostics
            diagnostics.addAll(pkg.dependencyManifest().diagnostics().diagnostics());
            diagnostics.forEach(d -> {
                if (!d.diagnosticInfo().code().startsWith(TOOL_DIAGNOSTIC_CODE_PREFIX)) {
                    err.println(d);
                }
            });
            throw createLauncherException("package resolution contains errors");
        }

        // Add corrupted dependencies toml diagnostic
        pkg.dependencyManifest().diagnostics().diagnostics().forEach(diagnostic -> {
            if (diagnostic.diagnosticInfo().code().equals(CORRUPTED_DEPENDENCIES_TOML.diagnosticId())) {
                diagnostics.add(diagnostic);
            }
        });
    }

    private void validateProject(Package pkg) {
        if (ProjectUtils.isPackageEmpty(pkg) && skipCompilationForBalPack(pkg)) {
            throw createLauncherException("package is empty. Please add at least one .bal file.");
        }
    }

    private boolean isPackCmdForATemplatePkg(Package pkg) {
        return compileForBalPack && pkg.manifest().template();
    }

    private PackageResolution getResolution(Package pkg, BuildOptions buildOptions) {
        System.setProperty(CentralClientConstants.ENABLE_OUTPUT_STREAM, "true");
        printWarningForHigherDistribution(pkg, buildOptions);
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (this.compileForBalBuild) {
            addDiagnosticForProvidedPlatformLibs(pkg, diagnostics);
        }

        if (pkg.compilationOptions().dumpGraph()
                || pkg.compilationOptions().dumpRawGraphs()) {
            this.out.println();
            this.out.println("Resolving dependencies");
        }

        if (buildOptions.dumpBuildTime()) {
            start = System.currentTimeMillis();
        }
        PackageResolution packageResolution = pkg.getResolution();
        if (buildOptions.dumpBuildTime()) {
            BuildTime.getInstance().packageResolutionDuration = System.currentTimeMillis() - start;
        }

        if (pkg.compilationOptions().dumpRawGraphs()) {
            packageResolution.dumpGraphs(out);
        }

        if (buildOptions.dumpBuildTime()) {
            BuildTime.getInstance().codeGeneratorPluginDuration = 0;
            BuildTime.getInstance().codeModifierPluginDuration = 0;
            start = System.currentTimeMillis();
        }
        return packageResolution;
    }

    private void runCodeGenerators(Package pkg, BuildOptions buildOptions, ProjectKind projectKind) {
        if (projectKind.equals(ProjectKind.BALA_PROJECT) || projectKind.equals(ProjectKind.SINGLE_FILE_PROJECT) ||
                isPackCmdForATemplatePkg(pkg)) {
            return;
        }

        if (!this.isPackageModified && this.cachesEnabled) {
            return;
        }
        CodeGeneratorResult codeGeneratorResult = pkg.runCodeGeneratorPlugins();
        diagnostics.addAll(codeGeneratorResult.reportedDiagnostics().diagnostics());
        if (buildOptions.dumpBuildTime()) {
            BuildTime.getInstance().codeGeneratorPluginDuration =
                    System.currentTimeMillis() - start;
            start = System.currentTimeMillis();
        }
    }

    private void runCodeModifiers(Package pkg, BuildOptions buildOptions, ProjectKind projectKind) {
        if (projectKind.equals(ProjectKind.BALA_PROJECT) || projectKind.equals(ProjectKind.SINGLE_FILE_PROJECT) ||
                isPackCmdForATemplatePkg(pkg)) {
            return;
        }

        if (!this.isPackageModified && this.cachesEnabled) {
            return;
        }
        CodeModifierResult codeModifierResult = pkg.runCodeModifierPlugins();
        diagnostics.addAll(codeModifierResult.reportedDiagnostics().diagnostics());
        if (buildOptions.dumpBuildTime()) {
            BuildTime.getInstance().codeModifierPluginDuration =
                    System.currentTimeMillis() - start;
        }
    }

    /**
     * Prints the warning that explains the dependency update due to the detection of a new distribution.
     *
     * @param pkg package instance
     */
    private void printWarningForHigherDistribution(Package pkg, BuildOptions buildOptions) {
        SemanticVersion prevDistributionVersion = pkg.dependencyManifest().distributionVersion();
        SemanticVersion currentDistributionVersion = SemanticVersion.from(RepoUtils.getBallerinaShortVersion());

        if (pkg.dependencyManifest().dependenciesTomlVersion() != null) {
            String currentVersionForDiagnostic = String.valueOf(currentDistributionVersion.minor());
            if (currentDistributionVersion.patch() != 0) {
                currentVersionForDiagnostic += DOT + currentDistributionVersion.patch();
            }
            String prevVersionForDiagnostic;
            if (null != prevDistributionVersion) {
                prevVersionForDiagnostic = String.valueOf(prevDistributionVersion.minor());
                if (prevDistributionVersion.patch() != 0) {
                    prevVersionForDiagnostic += DOT + prevDistributionVersion.patch();
                }
            } else {
                prevVersionForDiagnostic = "4 or an older Update";
            }
            String warning = null;
            // existing project
            if (prevDistributionVersion == null
                    || ProjectUtils.isNewUpdateDistribution(prevDistributionVersion, currentDistributionVersion)) {
                // Built with a previous Update. Therefore, we issue a warning
                warning = "Detected an attempt to compile this package using Swan Lake Update "
                        + currentVersionForDiagnostic +
                        ". However, this package was built using Swan Lake Update " + prevVersionForDiagnostic + ".";
                if (buildOptions.sticky()) {
                    warning += "\nHINT: Execute the bal command with --sticky=false";
                } else {
                    warning += " To ensure compatibility, the Dependencies.toml file will be updated with the " +
                            "latest versions that are compatible with Update " + currentVersionForDiagnostic + ".";
                }
            }
            if (warning != null) {
                DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                        ProjectDiagnosticErrorCode.BUILT_WITH_OLDER_SL_UPDATE_DISTRIBUTION.diagnosticId(),
                        warning, DiagnosticSeverity.WARNING);
                PackageDiagnostic diagnostic = new PackageDiagnostic(diagnosticInfo,
                        pkg.descriptor().name().toString());
                err.println(diagnostic);
            }
        }
    }

    private void addDiagnosticForProvidedPlatformLibs(Package pkg, List<Diagnostic> diagnostics) {
        Map<String, PackageManifest.Platform> platforms = pkg.manifest().platforms();
        for (PackageManifest.Platform javaPlatform : platforms.values()) {
            if (javaPlatform == null || javaPlatform.dependencies().isEmpty()) {
                continue;
            }
            for (Map<String, Object> dependency : javaPlatform.dependencies()) {
                if (Objects.equals(dependency.get("scope"), PlatformLibraryScope.PROVIDED.getStringValue())) {
                    DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                            ProjectDiagnosticErrorCode.INVALID_PROVIDED_SCOPE_IN_BUILD.diagnosticId(),
                            String.format("'%s' scope for platform dependencies is not allowed with package build%n",
                                    PlatformLibraryScope.PROVIDED.getStringValue()),
                            DiagnosticSeverity.ERROR);
                    diagnostics.add(new PackageDiagnostic(diagnosticInfo, pkg.descriptor().name().toString()));
                    return;
                }
            }
        }
    }

    /**
     * If CompileTask is triggered by `bal pack` command, and project does not have CompilerPlugin.toml or BalTool.toml,
     * skip the compilation if project is empty. The project should be evaluated for emptiness before calling this.
     *
     * @param pkg package instance
     * @return true if compilation should be skipped, false otherwise
     */
    private boolean skipCompilationForBalPack(Package pkg) {
        return (!compileForBalPack || pkg.compilerPluginToml().isEmpty() && pkg.balToolToml().isEmpty());
    }
}
