/*
 *  Copyright (c) 2025, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package io.ballerina.projects;

import io.ballerina.projects.util.BalToolUtils;
import io.ballerina.projects.util.ProjectUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.ballerina.projects.util.ProjectConstants.DISTRIBUTION_REPOSITORY_NAME;

public class BlendedBalToolsManifest {
    private final Map<String, Map<String, Map<String, BalToolsManifest.Tool>>> tools;

    public BlendedBalToolsManifest(Map<String, Map<String, Map<String, BalToolsManifest.Tool>>> tools) {
        this.tools = tools;
    }

    public static BlendedBalToolsManifest from(
            BalToolsManifest localBalToolManifest, BalToolsManifest distBalToolManifest) {
        return mergeBalToolManifests(localBalToolManifest, distBalToolManifest);
    }

    private static BlendedBalToolsManifest mergeBalToolManifests(BalToolsManifest localBalToolsManifest,
            BalToolsManifest distBalToolsManifest) {
        Map<String, Map<String, Map<String, BalToolsManifest.Tool>>> mergedTools =
                new HashMap<>(localBalToolsManifest.tools());

        for (String toolId : mergedTools.keySet()) {
            if (BalToolUtils.getInBuiltToolCommands().contains(toolId)) {
                continue;
            }
            Optional<BalToolsManifest.Tool> activeTool = localBalToolsManifest.getActiveTool(toolId);
            if (activeTool.isEmpty()) {
                // No tools installed
                continue;
            }
            String org = activeTool.get().org();
            String name = activeTool.get().name();
            String version = activeTool.get().version();

            // Set the active tool version if the current active version is incompatible with the distribution
            SemanticVersion.VersionCompatibilityResult versionCompatibilityResult =
                    BalToolUtils.compareWithDist(org, name, version);

            if (!versionCompatibilityResult.equals(SemanticVersion.VersionCompatibilityResult.EQUAL)) {
                Optional<PackageVersion> highestVersion = getHighestCompatibleLocalVersion(localBalToolsManifest,
                        toolId, org, name);
                if (highestVersion.isEmpty()) {
                    continue;
                }
                mergedTools.get(toolId).get(version).get(null).setActive(false);
                mergedTools.get(toolId).get(highestVersion.get().toString()).get(null).setActive(true);
            }
        }

        // Handle active versions of distribution tools

        // 1. No locally installed versions
        // 1.1 if the local bal-tools.toml is empty, return the distribution bal-tools.toml
        // 1.2 if no active tool is set in the local bal-tools.toml, return the distribution bal-tools.toml

        // 2. if there are no compatible versions in the local bal-tools.toml, return the distribution bal-tools.toml
        // 3. if the versions in the local bal-tools.toml are < the versions in the distribution bal-tools.toml, return
        // the distribution bal-tools.toml
        // 4. if there is a version in the local bal-tools.toml are > the version in the distribution bal-tools.toml, return
        // the local bal-tools.toml
        for (String toolCommand : BalToolUtils.getInBuiltToolCommands()) {
            Optional<BalToolsManifest.Tool> activeToolDist = distBalToolsManifest.getActiveTool(toolCommand);
            if (!mergedTools.containsKey(toolCommand)) {
                BalToolsManifest.Tool tool = activeToolDist.orElseThrow();
                BalToolsManifest.Tool toolNew = new BalToolsManifest.Tool(
                        tool.id(), tool.org(), tool.name(), tool.version(), true, DISTRIBUTION_REPOSITORY_NAME);
                mergedTools.put(tool.id(), Map.of(tool.version(), Map.of(DISTRIBUTION_REPOSITORY_NAME, toolNew)));
                continue;
            }

            Optional<BalToolsManifest.Tool> activeToolLocal = mergedTools.get(toolCommand).values().stream()
                    .flatMap(v -> v.values().stream()).filter(BalToolsManifest.Tool::active).findFirst();

            if (activeToolLocal.isEmpty()) {
                BalToolsManifest.Tool tool = activeToolDist.orElseThrow();
                BalToolsManifest.Tool toolNew = new BalToolsManifest.Tool(
                        tool.id(), tool.org(), tool.name(), tool.version(), true, DISTRIBUTION_REPOSITORY_NAME);
                mergedTools.get(toolCommand).put(tool.version(), Map.of(DISTRIBUTION_REPOSITORY_NAME, toolNew));
                continue;
            }

            BalToolsManifest.Tool localTool = activeToolLocal.get();
            SemanticVersion.VersionCompatibilityResult versionCompatibilityResult =
                    BalToolUtils.compareWithDist(localTool.org(), localTool.name(), localTool.version());
            if (versionCompatibilityResult.equals(SemanticVersion.VersionCompatibilityResult.LESS_THAN)) {
                BalToolsManifest.Tool tool = activeToolDist.orElseThrow();
                BalToolsManifest.Tool toolNew = new BalToolsManifest.Tool(
                        tool.id(), tool.org(), tool.name(), tool.version(), true, DISTRIBUTION_REPOSITORY_NAME);
                mergedTools.get(toolCommand).put(tool.version(), Map.of(DISTRIBUTION_REPOSITORY_NAME, toolNew));
                continue;
            }

            if (versionCompatibilityResult.equals(SemanticVersion.VersionCompatibilityResult.GREATER_THAN)) {
                // 2. Locally active version is incompatible

                // Check if dist version is higher than the highest compatible version in the local bal-tools.toml
                Optional<PackageVersion> highestVersion = getHighestCompatibleLocalVersion(localBalToolsManifest,
                        localTool.id(), localTool.org(), localTool.name());
                if (highestVersion.isEmpty() ||
                        highestVersion.get().compareTo(PackageVersion.from(activeToolDist.orElseThrow().version()))
                        .equals(SemanticVersion.VersionCompatibilityResult.LESS_THAN)) {
                    mergedTools.get(localTool.id()).forEach((k, v) -> v.forEach((k1, v1) -> v1.setActive(false)));
                    mergedTools.get(localTool.id()).get(localTool.version()).get(null).setActive(false);

                    BalToolsManifest.Tool tool = activeToolDist.orElseThrow();
                    BalToolsManifest.Tool toolNew = new BalToolsManifest.Tool(
                            tool.id(), tool.org(), tool.name(), tool.version(), true, DISTRIBUTION_REPOSITORY_NAME);
                    mergedTools.get(toolCommand).put(tool.version(), Map.of(DISTRIBUTION_REPOSITORY_NAME, toolNew));
                    continue;
                }

                // 4. Highest local version is greater than the distribution version
                BalToolsManifest.Tool tool = new BalToolsManifest.Tool(
                        localTool.id(), localTool.org(), localTool.name(),
                        highestVersion.toString(), true, localTool.repository());
                mergedTools.get(localTool.id()).forEach((k, v) -> v.forEach((k1, v1) -> v1.setActive(false)));
                mergedTools.get(tool.id()).get(localTool.version()).get(null).setActive(false);
                mergedTools.get(tool.id()).get(highestVersion.get().toString()).get(null).setActive(true);
            }
            // Leave the current active version as it is
        }

        return new BlendedBalToolsManifest(mergedTools);
    }

    private static Optional<PackageVersion> getHighestCompatibleLocalVersion(
            BalToolsManifest localBalToolsManifest, String toolId, String org, String name) {
        List<PackageVersion> toolVersions = new ArrayList<>(localBalToolsManifest.tools()
                .get(toolId).keySet().stream()
                .map(PackageVersion::from)
                .filter(version -> !localBalToolsManifest.tools().get(toolId).get(version.toString())
                        .containsKey("local"))
                .toList());

        // Check if there are any compatible versions in the local bal-tools.toml
        toolVersions.removeIf(version -> BalToolUtils.compareWithDist(org, name, version.toString())
                .equals(SemanticVersion.VersionCompatibilityResult.GREATER_THAN));

        if (toolVersions.isEmpty()) {
            return Optional.empty();
        }
        PackageVersion highestVersion = toolVersions.stream().findFirst().orElseThrow();
        for (PackageVersion toolVersion : toolVersions) {
            ProjectUtils.getLatest(highestVersion, toolVersion);
            if (toolVersion.compareTo(highestVersion)
                    .equals(SemanticVersion.VersionCompatibilityResult.GREATER_THAN)) {
                highestVersion = toolVersion;
            }
        }
        return Optional.of(highestVersion);
    }

    public Map<String, Map<String, Map<String, BalToolsManifest.Tool>>> tools() {
        return tools;
    }

    public Map<String, Map<String, Map<String, BalToolsManifest.Tool>>> compatibleTools() {
        // Remove incompatible versions
        Map<String, Map<String, Map<String, BalToolsManifest.Tool>>> compatibleTools = new HashMap<>(tools);
        for (String toolId : tools.keySet()) {
            Map<String, Map<String, BalToolsManifest.Tool>> versions = compatibleTools.get(toolId);
            versions.keySet().removeIf(version -> !BalToolUtils.checkToolDistCompatibility(
                    versions.get(version).values().iterator().next().org(),
                    versions.get(version).values().iterator().next().name(), version));
        }

        return compatibleTools;
    }

    public Optional<BalToolsManifest.Tool> getTool(String id, String version, String repository) {
        if (tools.containsKey(id) && tools.get(id).containsKey(version)) {
            return Optional.ofNullable(tools.get(id).get(version).get(repository));
        }
        return Optional.empty();
    }

    public Optional<BalToolsManifest.Tool> getActiveTool(String id) {
        if (tools.containsKey(id)) {
            return tools.get(id).values().stream().flatMap(
                    v -> v.values().stream()).filter(BalToolsManifest.Tool::active).findFirst();
        }
        return Optional.empty();
    }

    public void setActiveToolVersion(String id, String version, String repository) {
        if (tools.containsKey(id)) {
            tools.get(id).forEach((k, v) -> v.forEach((k1, v1) -> v1.setActive(false)));
            tools.get(id).get(version).get(repository).setActive(true);
        }
    }
}
