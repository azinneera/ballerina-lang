/*
 * Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.ballerina.projects.util;

import io.ballerina.projects.CompilationOptions;
import io.ballerina.projects.DependencyGraph;
import io.ballerina.projects.ModuleId;
import io.ballerina.projects.Package;
import io.ballerina.projects.PackageDependencyScope;
import io.ballerina.projects.Project;
import io.ballerina.projects.ProjectKind;
import io.ballerina.projects.ResolvedPackageDependency;
import io.ballerina.projects.directory.Workspace;
import io.ballerina.projects.environment.ResolutionOptions;
import io.ballerina.projects.internal.WorkspaceDependencyGraphBuilder;
import io.ballerina.projects.internal.repositories.WorkspaceRepository;

import java.util.Collection;

/**
 * Project dependencies related util methods.
 *
 * @since 2.0.0
 */
public final class DependencyUtils {

    private DependencyUtils() {

    }

    /**
     * Pull missing dependencies from central.
     *
     * @param project project
     */
    public static void pullMissingDependencies(Project project) {
        CompilationOptions.CompilationOptionsBuilder compilationOptionsBuilder = CompilationOptions.builder();
        compilationOptionsBuilder.setOffline(false).setSticky(false);
        project.currentPackage().getResolution(compilationOptionsBuilder.build());
    }

    public static DependencyGraph<ResolvedPackageDependency> getWorkspaceDependencyGraph(Workspace workspace) {
        WorkspaceDependencyGraphBuilder graphBuilder = new WorkspaceDependencyGraphBuilder();
        for (Package pkg : workspace.packages()) {

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
}
