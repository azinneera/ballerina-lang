/*
 *  Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
package io.ballerina.projects.internal;

import io.ballerina.projects.DependencyManifest;
import io.ballerina.projects.DiagnosticResult;
import io.ballerina.projects.ModuleDescriptor;
import io.ballerina.projects.PackageManifest;
import io.ballerina.projects.PackageName;
import io.ballerina.projects.PackageOrg;
import io.ballerina.projects.PackageVersion;
import io.ballerina.projects.Project;
import io.ballerina.projects.SemanticVersion.VersionCompatibilityResult;
import io.ballerina.projects.directory.BuildProject;
import io.ballerina.projects.internal.repositories.AbstractPackageRepository;
import io.ballerina.projects.internal.repositories.MavenPackageRepository;
import io.ballerina.projects.util.ProjectConstants;
import io.ballerina.projects.util.ProjectUtils;
import io.ballerina.tools.diagnostics.Diagnostic;
import io.ballerina.tools.diagnostics.DiagnosticInfo;
import io.ballerina.tools.diagnostics.DiagnosticSeverity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.ballerina.projects.PackageVersion.BUILTIN_PACKAGE_VERSION;

/**
 * Blends dependencies in Dependencies.toml with dependencies specified
 * in Ballerina.toml into a single representation.
 *
 * @since 2.0.0
 */
public class BlendedManifest {
    private final PackageContainer<Dependency> depContainer;
    private final DiagnosticResult diagnosticResult;

    private static final Repository REPOSITORY_LOCAL = new Repository("local");
    private static final Repository REPOSITORY_NOT_SPECIFIED = new Repository("not_specified");

    private BlendedManifest(PackageContainer<Dependency> pkgContainer, DiagnosticResult diagnosticResult) {
        this.depContainer = pkgContainer;
        this.diagnosticResult = diagnosticResult;
    }

    public static BlendedManifest from(DependencyManifest dependencyManifest,
                                       PackageManifest packageManifest,
                                       AbstractPackageRepository localPackageRepository,
                                       Map<String, MavenPackageRepository> mavenPackageRepositoryMap,
                                       boolean offline, Project project) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        PackageContainer<Dependency> depContainer = new PackageContainer<>();
        PackageOrg pkgOrg;
        PackageName pkgName;
        PackageVersion pkgVersion;
        Path dependencyPath = null;
        for (DependencyManifest.Package pkgInDepManifest : dependencyManifest.packages()) {
            pkgOrg = pkgInDepManifest.org();
            pkgName = pkgInDepManifest.name();
            pkgVersion = ProjectUtils.isBuiltInPackage(pkgOrg, pkgName.toString()) ?
                    BUILTIN_PACKAGE_VERSION : pkgInDepManifest.version();
            depContainer.add(pkgOrg, pkgName, new Dependency(pkgOrg, pkgName, pkgVersion,
                    getRelation(pkgInDepManifest.isTransitive()),
                    REPOSITORY_NOT_SPECIFIED, moduleNames(pkgInDepManifest), DependencyOrigin.LOCKED, null));
        }

        for (PackageManifest.Dependency depInPkgManifest : packageManifest.dependencies()) {
            pkgOrg = depInPkgManifest.org();
            pkgName = depInPkgManifest.name();
            pkgVersion = depInPkgManifest.version();
            AbstractPackageRepository targetRepository = localPackageRepository;
            Optional<Dependency> existingDepOptional = depContainer.get(pkgOrg, pkgName);
            Repository depInPkgManifestRepo = depInPkgManifest.repository() != null &&
                    depInPkgManifest.repository().equals(ProjectConstants.LOCAL_REPOSITORY_NAME) ?
                    REPOSITORY_LOCAL : new Repository(depInPkgManifest.repository());

            if (depInPkgManifest.repository() != null) {
                if (!depInPkgManifest.repository().equals(ProjectConstants.LOCAL_REPOSITORY_NAME) &&
                    !mavenPackageRepositoryMap.containsKey(depInPkgManifest.repository())) {
                    var diagnosticInfo = new DiagnosticInfo(
                            ProjectDiagnosticErrorCode.CUSTOM_REPOSITORY_NOT_FOUND.diagnosticId(),
                            "Provided custom repository (" + depInPkgManifest.repository() +
                                    ") cannot be found in the Settings.toml. ",
                            DiagnosticSeverity.WARNING);
                    PackageDiagnostic diagnostic = new PackageDiagnostic(
                            diagnosticInfo, depInPkgManifest.location().orElseThrow());
                    diagnostics.add(diagnostic);
                    continue;
                }

                if (depInPkgManifest.repository().equals(ProjectConstants.LOCAL_REPOSITORY_NAME) &&
                        !localPackageRepository.isPackageExists(pkgOrg, pkgName, pkgVersion)) {
                    var diagnosticInfo = new DiagnosticInfo(
                            ProjectDiagnosticErrorCode.PACKAGE_NOT_FOUND.diagnosticId(),
                            "Dependency version (" + pkgVersion +
                                    ") cannot be found in the local repository. org: `" + pkgOrg + "` name: " + pkgName,
                            DiagnosticSeverity.WARNING);
                    PackageDiagnostic diagnostic = new PackageDiagnostic(
                            diagnosticInfo, depInPkgManifest.location().orElseThrow());
                    diagnostics.add(diagnostic);
                    continue;
                }

                if (!depInPkgManifest.repository().equals(ProjectConstants.LOCAL_REPOSITORY_NAME)) {
                    targetRepository = mavenPackageRepositoryMap.get(depInPkgManifest.repository());
                    if (!((MavenPackageRepository) targetRepository).isPackageExists(pkgOrg,
                            pkgName, pkgVersion, offline)) {
                        var diagnosticInfo = new DiagnosticInfo(
                                ProjectDiagnosticErrorCode.PACKAGE_NOT_FOUND.diagnosticId(),
                                "Dependency version (" + depInPkgManifest.version() +
                                        ") cannot be found in the custom repository (" +
                                        depInPkgManifest.repository() + "). org: `" + pkgOrg + "` name: " + pkgName,
                                DiagnosticSeverity.WARNING);
                        PackageDiagnostic diagnostic = new PackageDiagnostic(
                                diagnosticInfo, depInPkgManifest.location().orElseThrow());
                        diagnostics.add(diagnostic);
                        continue;
                    }
                }
            } else {
                if (project != null && project.workspace().isPresent()) {
                    if (depInPkgManifest.path().isPresent()) {
                        if (!depInPkgManifest.path().get().isAbsolute()) {
                            dependencyPath = project.sourceRoot().resolve(depInPkgManifest.path().get())
                                    .toAbsolutePath().normalize();
                        }
                        if (dependencyPath != null && !Files.exists(dependencyPath)) {
                            var diagnosticInfo = new DiagnosticInfo(
                                    ProjectDiagnosticErrorCode.PACKAGE_NOT_FOUND.diagnosticId(),
                                    "Dependency path cannot be found: " + depInPkgManifest.path().get(),
                                    DiagnosticSeverity.WARNING);
                            PackageDiagnostic diagnostic = new PackageDiagnostic(
                                    diagnosticInfo, depInPkgManifest.location().orElseThrow());
                            diagnostics.add(diagnostic);
                            continue;
                        }
                        for (BuildProject buildProject : project.workspace().get().projects()) {
                            if (buildProject.sourceRoot().equals(dependencyPath)) {
                                pkgOrg = buildProject.currentPackage().packageOrg();
                                pkgName = buildProject.currentPackage().packageName();
                                pkgVersion = buildProject.currentPackage().packageVersion();
                                if (!Files.exists(buildProject.targetDir().resolve(ProjectConstants.CACHES_DIR_NAME))) {
                                    var diagnosticInfo = new DiagnosticInfo(
                                            ProjectDiagnosticErrorCode.PACKAGE_NOT_FOUND.diagnosticId(),
                                            "Dependency package is not built yet. org: `" + pkgOrg +
                                                    "` name: " + pkgName, DiagnosticSeverity.WARNING);
                                    PackageDiagnostic diagnostic = new PackageDiagnostic(
                                            diagnosticInfo, depInPkgManifest.location().orElseThrow());
                                    diagnostics.add(diagnostic);
                                    break;
                                }
                                break;
                            }
                        }
                    }
                }

                Collection<String> moduleNames = existingDepOptional.isPresent() ?
                        existingDepOptional.get().modules : Collections.emptyList();
                depContainer.add(pkgOrg, pkgName, new Dependency(pkgOrg, pkgName, pkgVersion,
                        DependencyRelation.UNKNOWN, REPOSITORY_NOT_SPECIFIED, moduleNames,
                        DependencyOrigin.USER_SPECIFIED, dependencyPath));
                continue;
            }

            if (existingDepOptional.isEmpty()) {
                depContainer.add(pkgOrg, pkgName, new Dependency(pkgOrg, pkgName, pkgVersion,
                                DependencyRelation.UNKNOWN, depInPkgManifestRepo,
                                moduleNames(depInPkgManifest, targetRepository), DependencyOrigin.USER_SPECIFIED,
                                dependencyPath));
            } else {
                Dependency existingDep = existingDepOptional.get();
                VersionCompatibilityResult compatibilityResult =
                        depInPkgManifest.version().compareTo(existingDep.version());
                if (compatibilityResult == VersionCompatibilityResult.EQUAL ||
                        compatibilityResult == VersionCompatibilityResult.GREATER_THAN) {
                    Dependency newDep = new Dependency(pkgOrg, pkgName, pkgVersion,
                            DependencyRelation.UNKNOWN, depInPkgManifestRepo,
                            moduleNames(depInPkgManifest, targetRepository), DependencyOrigin.USER_SPECIFIED,
                            dependencyPath);
                    depContainer.add(pkgOrg, pkgName, newDep);
                } else if (compatibilityResult == VersionCompatibilityResult.INCOMPATIBLE) {
                    DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                            ProjectDiagnosticErrorCode.INCOMPATIBLE_DEPENDENCY_VERSIONS.diagnosticId(),
                            "Dependency version (" + depInPkgManifest.version() + ") " +
                                    "is incompatible with the version locked in Dependencies.toml ("
                                    + existingDep.version + "). " +
                                    "org: `" + pkgOrg + "` name: " + pkgName, DiagnosticSeverity.ERROR);
                    PackageDiagnostic diagnostic = new PackageDiagnostic(
                            diagnosticInfo, depInPkgManifest.location().orElseThrow());
                    diagnostics.add(diagnostic);
                    Repository repository;
                    if (dependencyPath != null) {
                        repository = new Repository(dependencyPath.toString());
                    } else {
                        repository = existingDep.repository;
                    }
                    Dependency newDep = new Dependency(pkgOrg, pkgName, existingDep.version(), existingDep.relation,
                            repository, existingDep.modules, existingDep.origin,
                            dependencyPath, true);
                    depContainer.add(depInPkgManifest.org(), depInPkgManifest.name(), newDep);
                }
            }
        }
        return new BlendedManifest(depContainer, new DefaultDiagnosticResult(diagnostics));
    }

    private static DependencyRelation getRelation(boolean isTransitive) {
        return isTransitive ? DependencyRelation.TRANSITIVE : DependencyRelation.DIRECT;
    }

    private static Collection<String> moduleNames(DependencyManifest.Package dependency) {
        return dependency.modules()
                .stream()
                .map(DependencyManifest.Module::moduleName)
                .toList();
    }

    private static Collection<String> moduleNames(PackageManifest.Dependency dependency,
                                                  AbstractPackageRepository localPackageRepository) {
        Collection<ModuleDescriptor> moduleDescriptors = localPackageRepository.getModules(
                dependency.org(), dependency.name(), dependency.version());
        return moduleDescriptors.stream()
                .map(moduleDesc -> moduleDesc.name().toString())
                .toList();
    }

    public Optional<Dependency> lockedDependency(PackageOrg org, PackageName name) {
        return dependency(org, name, DependencyOrigin.LOCKED);
    }

    public Collection<Dependency> lockedDependencies() {
        return dependencies(DependencyOrigin.LOCKED);
    }

    public Optional<Dependency> userSpecifiedDependency(PackageOrg org, PackageName name) {
        return dependency(org, name, DependencyOrigin.USER_SPECIFIED);
    }

    public Collection<Dependency> userSpecifiedDependencies() {
        return dependencies(DependencyOrigin.USER_SPECIFIED);
    }

    public Optional<Dependency> dependency(PackageOrg org, PackageName name) {
        return depContainer.get(org, name);
    }

    public Collection<Dependency> dependencies() {
        return depContainer.getAll();
    }

    public Dependency dependencyOrThrow(PackageOrg org, PackageName name) {
        return depContainer.get(org, name).orElseThrow(() -> new IllegalStateException("Dependency with org `" +
                org + "` and name `" + name + "` must exists."));
    }

    private Optional<Dependency> dependency(PackageOrg org, PackageName name, DependencyOrigin origin) {
        return depContainer.get(org, name).filter(dep -> dep.origin == origin);
    }

    private Collection<Dependency> dependencies(DependencyOrigin origin) {
        return depContainer.getAll().stream()
                .filter(dep -> dep.origin == origin)
                .toList();
    }

    public DiagnosticResult diagnosticResult() {
        return diagnosticResult;
    }

    /**
     * Represents a local dependency package.
     *
     * @since 2.0.0
     */
    public static class Dependency {
        private final PackageOrg org;
        private final PackageName name;
        private final PackageVersion version;
        private final DependencyRelation relation;
        private final Repository repository;
        private final Collection<String> modules;
        private final DependencyOrigin origin;
        private final boolean isError;
        private final Path path;


        private Dependency(PackageOrg org,
                           PackageName name,
                           PackageVersion version,
                           DependencyRelation relation,
                           Repository repository,
                           Collection<String> modules,
                           DependencyOrigin origin,
                           Path path) {
            this.org = org;
            this.name = name;
            this.version = version;
            this.repository = repository;
            this.relation = relation;
            this.modules = modules;
            this.origin = origin;
            this.isError = false;
            this.path = path;
        }

        private Dependency(PackageOrg org,
                           PackageName name,
                           PackageVersion version,
                           DependencyRelation relation,
                           Repository repository,
                           Collection<String> modules,
                           DependencyOrigin origin,
                           Path path,
                           boolean isError) {
            this.org = org;
            this.name = name;
            this.version = version;
            this.repository = repository;
            this.relation = relation;
            this.modules = modules;
            this.origin = origin;
            this.isError = isError;
            this.path = path;
        }

        public PackageName name() {
            return name;
        }

        public PackageOrg org() {
            return org;
        }

        public PackageVersion version() {
            return version;
        }

        public boolean isFromLocalRepository() {
            return REPOSITORY_LOCAL.repositoryName.equals(repository.repositoryName);
        }

        public boolean isFromCustomRepository() {
            return (this.repository() != null) && (!REPOSITORY_LOCAL.repositoryName.equals(repository.repositoryName));
        }

        public String repository() {
            return !REPOSITORY_NOT_SPECIFIED.repositoryName.equals(this.repository.repositoryName) ?
                    this.repository.repositoryName : null;
        }

        public DependencyRelation relation() {
            return relation;
        }

        public DependencyOrigin origin() {
            return origin;
        }

        public Collection<String> moduleNames() {
            return modules;
        }

        public boolean isError() {
            return isError;
        }

        public Optional<Path> path() {
            return Optional.ofNullable(path);
        }
    }

    /**
     * Specifies the relation between the root package and the dependency.
     *
     * @since 2.0.0
     */
    public enum DependencyRelation {
        DIRECT,
        TRANSITIVE,
        UNKNOWN
    }

    /**
     * Indicates the origin of a dependency.
     */
    public enum DependencyOrigin {
        /**
         * Dependencies specified in Ballerina.toml file.
         */
        USER_SPECIFIED,

        /**
         * Dependencies specified in Dependencies.toml file.
         */
        LOCKED
    }

    /**
     * Specifies the repository kind.
     */
    private static class Repository {

        private final String repositoryName;

        Repository(String repository) {
            this.repositoryName = repository;

        }
    }
}
