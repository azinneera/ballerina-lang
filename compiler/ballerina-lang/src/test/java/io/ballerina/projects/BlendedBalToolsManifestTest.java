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

import io.ballerina.projects.internal.BalToolsManifestBuilder;
import io.ballerina.projects.util.BalToolUtils;
import io.ballerina.projects.util.ProjectUtils;
import org.ballerinalang.central.client.CentralClientConstants;
import org.ballerinalang.central.client.exceptions.CentralClientException;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.when;

public class BlendedBalToolsManifestTest {

    private final Path balToolsTomlPath = Paths.get("src/test/resources/bal-tools-tomls/bal-tools.toml");
    private final Path distBalToolsTomlPath = Paths.get("src/test/resources/bal-tools-tomls/bal-tools-dist.toml");

    private BalToolsManifest balToolsManifest;
    private BalToolsManifest distBalToolsManifest;
    private final List<String> toolCommands = List.of("openapi", "asyncapi", "graphql", "edi", "persist", "grpc");

    @BeforeClass
    public void setup() {
        BalToolsToml balToolsToml = BalToolsToml.from(balToolsTomlPath);
        this.balToolsManifest = BalToolsManifestBuilder.from(balToolsToml).build();
        BalToolsToml distBalToolsToml = BalToolsToml.from(distBalToolsTomlPath);
        this.distBalToolsManifest = BalToolsManifestBuilder.from(distBalToolsToml).build();
    }

    /* Test cases
    * 1. Only in dist - persist
    * 2. Only local - consolidate-packages
    * 3. Both in dist and local
    * 3.1 Local active is compatible
    * 3.1.1 local active < dist - edi
    * 3.1.2 local active >= dist - graphql
    * 3.2 Local active is incompatible
     * 3.2.1 Dist > locals - openapi
     * 3.2.2 Locals > dist - asyncapi
    * 4 No locally active versions - grpc
    * 5. Not a tool
    * 5.1 No locally active versions
    * 5.2 No versions at all
    * */

    @Test
    public void testToolOnlyInDist() {
        // persist
        try (MockedStatic<BalToolUtils> utils = Mockito.mockStatic(BalToolUtils.class, CALLS_REAL_METHODS)) {
            utils.when(() -> BalToolUtils.compareWithDist(anyString(), anyString(), anyString()))
                    .thenReturn(SemanticVersion.VersionCompatibilityResult.EQUAL);
            when(BalToolUtils.getInBuiltToolCommands()).thenReturn(this.toolCommands);

            BlendedBalToolsManifest blendedBalToolsManifest = BlendedBalToolsManifest
                    .from(balToolsManifest, distBalToolsManifest);
            Optional<BalToolsManifest.Tool> activePersistTool = blendedBalToolsManifest.getActiveTool("persist");
            Assert.assertEquals(activePersistTool.orElseThrow().version(), "0.9.0");
        }
    }

    @Test
    public void testToolOnlyInLocal() {
        // consolidate-packages
        try (MockedStatic<BalToolUtils> utils = Mockito.mockStatic(BalToolUtils.class, CALLS_REAL_METHODS)) {
            utils.when(() -> BalToolUtils.compareWithDist(anyString(), anyString(), anyString()))
                    .thenReturn(SemanticVersion.VersionCompatibilityResult.EQUAL);
            when(BalToolUtils.getInBuiltToolCommands()).thenReturn(this.toolCommands);

            BlendedBalToolsManifest blendedBalToolsManifest = BlendedBalToolsManifest
                    .from(balToolsManifest, distBalToolsManifest);
            Optional<BalToolsManifest.Tool> activeConsolidatePackagesTool =
                    blendedBalToolsManifest.getActiveTool("consolidate-packages");
            Assert.assertEquals(activeConsolidatePackagesTool.orElseThrow().version(), "0.1.7");
        }
    }

    @Test
    public void testLocalActiveAvailableWithSameDist() {
        try (MockedStatic<BalToolUtils> utils = Mockito.mockStatic(BalToolUtils.class, CALLS_REAL_METHODS)) {
            utils.when(() -> BalToolUtils.compareWithDist(anyString(), anyString(), anyString()))
                    .thenReturn(SemanticVersion.VersionCompatibilityResult.EQUAL);
            when(BalToolUtils.getInBuiltToolCommands()).thenReturn(this.toolCommands);

            BlendedBalToolsManifest blendedBalToolsManifest = BlendedBalToolsManifest
                    .from(balToolsManifest, distBalToolsManifest);
            // local active < dist - edi
            Optional<BalToolsManifest.Tool> activeEdiTool = blendedBalToolsManifest.getActiveTool("edi");
            Assert.assertEquals(activeEdiTool.orElseThrow().version(), "1.0.0");

            // local active >= dist - graphql
            Optional<BalToolsManifest.Tool> activeGraphqlTool = blendedBalToolsManifest.getActiveTool("graphql");
            Assert.assertEquals(activeGraphqlTool.orElseThrow().version(), "1.1.5");
        }
    }

    @Test
    public void testLocalActiveWithHigherDist() {
        try (MockedStatic<BalToolUtils> utils = Mockito.mockStatic(BalToolUtils.class, CALLS_REAL_METHODS)) {
            utils.when(() -> BalToolUtils.compareWithDist(anyString(), anyString(), anyString()))
                    .thenReturn(SemanticVersion.VersionCompatibilityResult.EQUAL);

            utils.when(() -> BalToolUtils.compareWithDist("ballerina", "tool_openapi", "1.3.0"))
                    .thenReturn(SemanticVersion.VersionCompatibilityResult.GREATER_THAN);
            utils.when(() -> BalToolUtils.compareWithDist("ballerina", "tool_openapi", "1.1.0"))
                            .thenReturn(SemanticVersion.VersionCompatibilityResult.LESS_THAN);
            utils.when(() -> BalToolUtils.compareWithDist("ballerina", "tool_openapi", "1.2.0"))
                    .thenReturn(SemanticVersion.VersionCompatibilityResult.EQUAL);
            utils.when(() -> BalToolUtils.compareWithDist("ballerina", "tool_openapi", "1.2.1"))
                    .thenReturn(SemanticVersion.VersionCompatibilityResult.EQUAL);
            utils.when(() -> BalToolUtils.compareWithDist("ballerina", "tool_openapi", "1.2.2"))
                    .thenReturn(SemanticVersion.VersionCompatibilityResult.EQUAL);

            utils.when(() -> BalToolUtils.compareWithDist("ballerina", "tool_asyncapi", "1.0.0"))
                    .thenReturn(SemanticVersion.VersionCompatibilityResult.EQUAL);
            utils.when(() -> BalToolUtils.compareWithDist("ballerina", "tool_asyncapi", "1.1.0"))
                    .thenReturn(SemanticVersion.VersionCompatibilityResult.EQUAL);
            utils.when(() -> BalToolUtils.compareWithDist("ballerina", "tool_asyncapi", "1.2.0"))
                    .thenReturn(SemanticVersion.VersionCompatibilityResult.GREATER_THAN);
            when(BalToolUtils.getInBuiltToolCommands()).thenReturn(this.toolCommands);
            BlendedBalToolsManifest blendedBalToolsManifest = BlendedBalToolsManifest
                    .from(balToolsManifest, distBalToolsManifest);

            Optional<BalToolsManifest.Tool> activeOpenApiTool = blendedBalToolsManifest.getActiveTool("openapi");
            Assert.assertEquals(activeOpenApiTool.orElseThrow().version(), "1.2.1");

            Optional<BalToolsManifest.Tool> activeAsyncApiTool = blendedBalToolsManifest.getActiveTool("asyncapi");
            Assert.assertEquals(activeAsyncApiTool.orElseThrow().version(), "1.1.0");
        }
    }

    @Test
    public void noLocallyActiveVersions() {
        try (MockedStatic<BalToolUtils> utils = Mockito.mockStatic(BalToolUtils.class, CALLS_REAL_METHODS)) {
            utils.when(() -> BalToolUtils.compareWithDist(anyString(), anyString(), anyString()))
                    .thenReturn(SemanticVersion.VersionCompatibilityResult.EQUAL);
            when(BalToolUtils.getInBuiltToolCommands()).thenReturn(this.toolCommands);

            BlendedBalToolsManifest blendedBalToolsManifest = BlendedBalToolsManifest
                    .from(balToolsManifest, distBalToolsManifest);
            Optional<BalToolsManifest.Tool> activeGrpcTool = blendedBalToolsManifest.getActiveTool("grpc");
            Assert.assertEquals(activeGrpcTool.orElseThrow().version(), "1.0.0");
        }
    }
}
