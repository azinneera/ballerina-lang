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
package io.ballerina.projects.internal;

import io.ballerina.projects.DependencyGraph;
import io.ballerina.projects.DependencyGraph.DependencyGraphBuilder;
import io.ballerina.projects.Package;
import io.ballerina.projects.PackageDependencyScope;
import io.ballerina.projects.PackageDescriptor;
import io.ballerina.projects.ResolvedPackageDependency;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * This class is responsible for creating a workspace dependency graph.
 *
 * @since 2.0.0
 */
public class WorkspaceDependencyGraphBuilder {
    private final Map<PackageDescriptor, Set<PackageDescriptor>> depGraph = new HashMap<>();
    private final DependencyGraphBuilder<PackageDescriptor> rawGraphBuilder;

    public WorkspaceDependencyGraphBuilder() {
        this.rawGraphBuilder = DependencyGraphBuilder.getBuilder(null);
    }

    public DependencyGraph<PackageDescriptor> buildGraph() {
        DependencyGraphBuilder<PackageDescriptor> graphBuilder = DependencyGraphBuilder.getBuilder(null);
        for (Map.Entry<PackageDescriptor, Set<PackageDescriptor>> entry : depGraph.entrySet()) {
            graphBuilder.addDependencies(entry.getKey(), entry.getValue());
        }

        return graphBuilder.build();
    }

    public void addPackage(Package pkg) {
        PackageDescriptor key = pkg.descriptor();
        if (!depGraph.containsKey(key)) {
            depGraph.put(key, new HashSet<>());
        }
    }

    public void addDependency(Package dependent, Package dependency) {
        PackageDescriptor dependentDesc = dependent.descriptor();
        if (!depGraph.containsKey(dependentDesc)) {
            throw new IllegalStateException("Dependent package does not exist in the graph: " + dependent);
        }
        PackageDescriptor dependencyDesc = dependency.descriptor();
        depGraph.get(dependentDesc).add(dependencyDesc);
        // Add to raw graph for troubleshooting
        rawGraphBuilder.addDependency(dependentDesc, dependencyDesc);
    }
}
