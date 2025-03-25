package io.ballerina.projects.util;

import io.ballerina.projects.SemanticVersion;
import io.ballerina.projects.internal.BalaFiles;
import io.ballerina.projects.internal.model.PackageJson;
import org.wso2.ballerinalang.util.RepoUtils;

import java.nio.file.Path;

import static io.ballerina.projects.util.ProjectConstants.CENTRAL_REPOSITORY_CACHE_NAME;
import static io.ballerina.projects.util.ProjectConstants.LOCAL_REPOSITORY_NAME;
import static io.ballerina.projects.util.ProjectConstants.REPOSITORIES_DIR;

public class BalToolUtils {

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

    private static SemanticVersion getToolDistVersionFromCache(String org, String name, String version, String repository) {
        Path balaDirPath = RepoUtils.createAndGetHomeReposPath().resolve(REPOSITORIES_DIR).resolve(repository)
                .resolve(ProjectConstants.BALA_DIR_NAME);
        Path balaPath = ProjectUtils.getPackagePath(balaDirPath, org, name, version);
        PackageJson packageJson = BalaFiles.readPackageJson(balaPath);
        return SemanticVersion.from(packageJson.getBallerinaVersion());
    }

}
