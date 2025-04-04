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
package io.ballerina.projects.util;

import io.ballerina.projects.SemanticVersion;
import io.ballerina.projects.internal.BalaFiles;
import io.ballerina.projects.internal.model.PackageJson;
import org.wso2.ballerinalang.util.RepoUtils;

import java.nio.file.Path;
import java.util.List;

import static io.ballerina.projects.util.ProjectConstants.CENTRAL_REPOSITORY_CACHE_NAME;
import static io.ballerina.projects.util.ProjectConstants.REPOSITORIES_DIR;

public class BalToolUtils {

    public static final String OPENAPI_COMMAND = "openapi";

    public static boolean isCompatibleWithLocalDistVersion(
            SemanticVersion localDistVersion, SemanticVersion toolDistVersion) {
        return localDistVersion.major() == toolDistVersion.major()
                && localDistVersion.minor() >= toolDistVersion.minor();
    }

    public static boolean checkToolDistCompatibility(String org, String name, String versions, String repository) {
        SemanticVersion currentDistVersion = SemanticVersion.from(RepoUtils.getBallerinaShortVersion());
        SemanticVersion toolDistVersion = getToolDistVersionFromCache(org, name, versions, repository);
        return isCompatibleWithLocalDistVersion(currentDistVersion, toolDistVersion);
    }

    public static boolean checkToolDistCompatibility(String org, String name, String versions) {
        SemanticVersion currentDistVersion = SemanticVersion.from(RepoUtils.getBallerinaShortVersion());
        SemanticVersion toolDistVersion = getToolDistVersionFromCache(org, name, versions,
                CENTRAL_REPOSITORY_CACHE_NAME);
        return isCompatibleWithLocalDistVersion(currentDistVersion, toolDistVersion);
    }

    public static SemanticVersion.VersionCompatibilityResult compareWithDist(
            String org, String name, String versions) {
        SemanticVersion currentDistVersion = SemanticVersion.from(RepoUtils.getBallerinaShortVersion());
        SemanticVersion toolDistVersion = getToolDistVersionFromCache(org, name, versions,
                CENTRAL_REPOSITORY_CACHE_NAME);
        return toolDistVersion.compareTo(currentDistVersion);
    }

    private static SemanticVersion getToolDistVersionFromCache(String org, String name, String version, String repository) {
        if (repository == null) {
            repository = CENTRAL_REPOSITORY_CACHE_NAME;
        }
        Path balaDirPath = RepoUtils.createAndGetHomeReposPath().resolve(REPOSITORIES_DIR).resolve(repository)
                .resolve(ProjectConstants.BALA_DIR_NAME);
        Path balaPath = ProjectUtils.getPackagePath(balaDirPath, org, name, version);
        PackageJson packageJson = BalaFiles.readPackageJson(balaPath);
        return SemanticVersion.from(packageJson.getBallerinaVersion());
    }

    public static List<String> getInBuiltToolCommands() {
        return List.of(OPENAPI_COMMAND);
    }
}
