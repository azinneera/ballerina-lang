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
import io.ballerina.projects.directory.BuildProject;

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
    private final Map<BuildProject, Set<BuildProject>> depGraph = new HashMap<>();
    private final DependencyGraphBuilder<BuildProject> rawGraphBuilder;

    public WorkspaceDependencyGraphBuilder() {
        this.rawGraphBuilder = DependencyGraphBuilder.getBuilder(null);
    }

    public DependencyGraph<BuildProject> buildGraph() {
        DependencyGraphBuilder<BuildProject> graphBuilder = DependencyGraphBuilder.getBuilder(null);
        for (Map.Entry<BuildProject, Set<BuildProject>> entry : depGraph.entrySet()) {
            BuildProject project = entry.getKey();
            Set<BuildProject> dependencies = entry.getValue();
            graphBuilder.addDependencies(project, dependencies);
        }

        return graphBuilder.build();
    }

    public DependencyGraph<BuildProject> rawGraph() {
        return rawGraphBuilder.build();
    }

    public void addProject(BuildProject project) {
        if (!depGraph.containsKey(project)) {
            depGraph.put(project, new HashSet<>());
        }
    }

    public void addDependency(BuildProject dependent, BuildProject dependency) {
        if (!depGraph.containsKey(dependent)) {
            throw new IllegalStateException("Dependent project does not exist in the graph: " + dependent);
        }
        depGraph.get(dependent).add(dependency);
        // Add to raw graph for troubleshooting
        rawGraphBuilder.addDependency(dependent, dependency);
    }
}
