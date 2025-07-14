/*
 *  Copyright (c) 2022, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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

package io.ballerina.cli.task;

import io.ballerina.projects.DiagnosticResult;
import io.ballerina.projects.Package;
import io.ballerina.projects.PackageDescriptor;
import io.ballerina.projects.PackageResolution;
import io.ballerina.projects.Project;
import io.ballerina.projects.ProjectException;
import io.ballerina.projects.ProjectKind;
import io.ballerina.projects.Workspace;
import io.ballerina.projects.util.ProjectUtils;
import io.ballerina.tools.diagnostics.Diagnostic;
import org.ballerinalang.central.client.CentralClientConstants;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.ballerina.cli.launcher.LauncherUtils.createLauncherException;

/**
 * Task for creating the dependency graph.
 *
 * @since 2201.2.0
 */
public class CreateDependencyGraphTask implements Task {
    private final transient PrintStream out;
    private final transient PrintStream err;
    private final Path projectPath;

    public CreateDependencyGraphTask(PrintStream err, PrintStream out, Path projectPath) {
        this.out = out;
        this.err = err;
        this.projectPath = projectPath;
    }

    @Override
    public void execute(Workspace workspace) {
        this.out.println();
        this.out.println("Resolving dependencies");
        Package pkg = workspace.packages().stream().filter(aPackage ->
                        workspace.sourceRoot(aPackage.descriptor()).equals(this.projectPath))
                .findFirst()
                .orElseThrow();
        execute(pkg.descriptor(), workspace);
    }

    private void execute(PackageDescriptor descriptor, Workspace workspace) {
        if (ProjectUtils.isPackageEmpty(workspace.getPackage(descriptor))) {
            throw createLauncherException("package is empty. Please add at least one .bal file.");
        }
        System.setProperty(CentralClientConstants.ENABLE_OUTPUT_STREAM, "true");

        try {
            List<Diagnostic> diagnostics = new ArrayList<>();

            PackageResolution packageResolution = workspace.getPackage(descriptor).getResolution();

            if (workspace.getPackage(descriptor).compilationOptions().dumpRawGraphs()) {
                this.out.println();
                this.out.println("Generating dependency graph");
                packageResolution.dumpGraphs(out);
            }

            // run built-in code generator compiler plugins
            // Errors in package resolution denotes version incompatibility errors.
            // We run code generators/modifiers only if package resolution does not have errors.
            if (!isResolutionErroneous(workspace.getPackage(descriptor))) {
                if (isProjectKindSuitableForCodeGenAndModify(workspace.kind())) {
                    DiagnosticResult codeGenAndModifyDiagnosticResult = workspace.getPackage(descriptor)
                            .runCodeGenAndModifyPlugins();
                    if (codeGenAndModifyDiagnosticResult != null) {
                        diagnostics.addAll(codeGenAndModifyDiagnosticResult.diagnostics());
                    }
                }
            }

            // We dump the raw graphs twice only if code generator/modifier plugins are engaged
            // since the package has changed now
            if (packageResolution != workspace.getPackage(descriptor).getResolution()) {
                packageResolution = workspace.getPackage(descriptor).getResolution();
                if (workspace.getPackage(descriptor).compilationOptions().dumpRawGraphs()) {
                    packageResolution.dumpGraphs(out);
                }
            }
            if (workspace.getPackage(descriptor).compilationOptions().dumpGraph()) {
                packageResolution.dumpGraphs(out);
            }

            if (isResolutionErroneous(workspace.getPackage(descriptor))) {
                diagnostics.addAll(workspace.getPackage(descriptor).getResolution().diagnosticResult().diagnostics());
                diagnostics.forEach(d -> err.println(d.toString()));
                throw createLauncherException("package resolution contains errors");
            }
        } catch (ProjectException e) {
            throw createLauncherException("dependency graph resolution failed: " + e.getMessage());
        }
    }

    private boolean isProjectKindSuitableForCodeGenAndModify(ProjectKind projectKind) {
        // BalaProject is a read-only project.
        // Hence, we run the code generators/modifiers only for BuildProject and SingleFileProject
        return !projectKind.equals(ProjectKind.BALA_PROJECT);
    }

    private boolean isResolutionErroneous(Package pkg) {
        return pkg.getResolution().diagnosticResult().hasErrors();
    }
}
