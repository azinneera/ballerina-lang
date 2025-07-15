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

import io.ballerina.projects.environment.Environment;
import io.ballerina.projects.environment.EnvironmentBuilder;
import io.ballerina.projects.environment.ProjectEnvironment;
import io.ballerina.projects.internal.environment.DefaultProjectEnvironment;
import io.ballerina.projects.repos.BuildProjectCompilationCache;
import io.ballerina.projects.repos.TempDirCompilationCache;

import java.util.HashMap;
import java.util.Map;

public class PackageEnvironmentBuilder {
    private final Environment environment;
    private final Map<Class<?>, Object> services = new HashMap<>();
    private CompilationCacheFactory compilationCacheFactory;

    private PackageEnvironmentBuilder(Environment environment) {
        this.environment = environment;
    }

    public static PackageEnvironmentBuilder getBuilder(Environment environment) {
        return new PackageEnvironmentBuilder(environment);
    }

    public static PackageEnvironmentBuilder getDefaultBuilder() {
        return new PackageEnvironmentBuilder(EnvironmentBuilder.buildDefault());
    }

    public PackageEnvironmentBuilder addCompilationCacheFactory(CompilationCacheFactory compilationCacheFactory) {
        this.compilationCacheFactory = compilationCacheFactory;
        return this;
    }

    public ProjectEnvironment build(Package pkg) {
        CompilationCache compilationCache;
        if (compilationCacheFactory != null) {
            compilationCache = compilationCacheFactory.createCompilationCache(pkg.project());
        } else {
            compilationCache = switch (pkg.workspace().kind()) {
                case BUILD_PROJECT,
                     WORKSPACE_PROJECT -> BuildProjectCompilationCache.from(pkg.project());
                case SINGLE_FILE_PROJECT -> TempDirCompilationCache.from(pkg.project());
                case BALA_PROJECT ->
                        throw new IllegalStateException("BALAProject should always be created with a CompilationCache");
            };
        }
        services.put(CompilationCache.class, compilationCache);
        return new DefaultProjectEnvironment(pkg.project(), environment, services);
    }
}
