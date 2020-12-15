package io.ballerina.projects;

import io.ballerina.tools.diagnostics.Diagnostic;
import io.ballerina.tools.diagnostics.DiagnosticInfo;
import io.ballerina.tools.diagnostics.Location;

public class PackageCompilationDiagnostic extends Diagnostic {
    private final ModuleName moduleName;
    private final Location location;
    private final DiagnosticInfo diagnosticInfo;
    private final String message;

    private PackageCompilationDiagnostic(Location location, DiagnosticInfo diagnosticInfo, String message, ModuleName moduleName) {
        this.location = location;
        this.diagnosticInfo = diagnosticInfo;
        this.message = message;
        this.moduleName = moduleName;
    }

    public static PackageCompilationDiagnostic from(Diagnostic diagnostic, ModuleName moduleName) {
        return new PackageCompilationDiagnostic(diagnostic.location(), diagnostic.diagnosticInfo(), diagnostic.message(), moduleName);
    }

    public ModuleName moduleName() {
        return moduleName;
    }

    @Override
    public Location location() {
        return location;
    }

    @Override
    public DiagnosticInfo diagnosticInfo() {
        return diagnosticInfo;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public String toString() {
        return diagnosticInfo().severity().toString() + " [" + moduleName() + "::" +
                location().lineRange().filePath() + ":" + location().lineRange() + "] " + message();
    }
}
