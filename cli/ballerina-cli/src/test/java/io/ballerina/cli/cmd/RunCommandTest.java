package io.ballerina.cli.cmd;

import io.ballerina.projects.env.BuildEnvContext;
import org.ballerinalang.tool.BLauncherException;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.wso2.ballerinalang.compiler.diagnostic.BLangDiagnosticLog;
import org.wso2.ballerinalang.compiler.util.CompilerContext;
import picocli.CommandLine;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * Run command tests.
 *
 * @since 2.0.0
 */
public class RunCommandTest extends BaseCommandTest {
    private Path testResources;

    @BeforeClass
    public void setup() throws IOException {
        super.setup();
        try {
            this.testResources = super.tmpDir.resolve("build-test-resources");
            URI testResourcesURI = Objects.requireNonNull(
                    getClass().getClassLoader().getResource("test-resources")).toURI();
            Files.walkFileTree(Paths.get(testResourcesURI), new BuildCommandTest.Copy(Paths.get(testResourcesURI),
                    this.testResources));
        } catch (URISyntaxException e) {
            Assert.fail("error loading resources");
        }
    }

    @Test(description = "Run a valid ballerina file")
    public void testRunValidBalFile() throws IOException {
        Path validBalFilePath = this.testResources.resolve("valid-run-bal-file").resolve("file_create.bal");

        System.setProperty("user.dir", this.testResources.resolve("valid-run-bal-file").toString());
        Path tempFile = this.testResources.resolve("valid-run-bal-file").resolve("temp.txt");
        // set valid source root
        RunCommand runCommand = new RunCommand(validBalFilePath, printStream, false);
        // name of the file as argument
        new CommandLine(runCommand).parse(validBalFilePath.toString(), tempFile.toString());

        Assert.assertFalse(tempFile.toFile().exists());
        runCommand.execute();

        String buildLog = readOutput(true);
        Assert.assertEquals(buildLog.replaceAll("\r", ""), "\nCompiling source\n" +
                "\tfile_create.bal\n" +
                "\n" +
                "Running executable\n\n");

        Assert.assertTrue(tempFile.toFile().exists());

        Files.delete(tempFile);
    }

    @Test(description = "Run non .bal file")
    public void testRunNonBalFile() throws IOException {
        Path nonBalFilePath = this.testResources.resolve("non-bal-file").resolve("hello_world.txt");
        RunCommand runCommand = new RunCommand(nonBalFilePath, printStream, false);
        new CommandLine(runCommand).parse(nonBalFilePath.toString());
        runCommand.execute();

        String buildLog = readOutput(true);
        Assert.assertTrue(buildLog.replaceAll("\r", "")
                .contains("provided path is not a valid Ballerina standalone file: " + nonBalFilePath.toString()));
    }

    @Test(description = "Run non existing bal file")
    public void testRunNonExistingBalFile() throws IOException {
        // valid source root path
        Path validBalFilePath = this.testResources.resolve("valid-run-bal-file").resolve("xyz.bal");
        RunCommand runCommand = new RunCommand(validBalFilePath, printStream, false);
        // non existing bal file
        new CommandLine(runCommand).parse(validBalFilePath.toString());
        runCommand.execute();
        String buildLog = readOutput(true);
        Assert.assertTrue(buildLog.replaceAll("\r", "")
                .contains("project path does not exist:" + validBalFilePath.toString()));

    }

    @Test(description = "Run bal file with no entry")
    public void testRunBalFileWithNoEntry() {
        // valid source root path
        Path projectPath = this.testResources.resolve("valid-bal-file-with-no-entry").resolve("hello_world.bal");
        RunCommand runCommand = new RunCommand(projectPath, printStream, false);
        // non existing bal file
        new CommandLine(runCommand).parse(projectPath.toString());
        try {
            runCommand.execute();
        } catch (RuntimeException e) {
            Assert.assertTrue(e.getMessage().contains("no entrypoint found in package"));
        }
    }

    @Test(description = "Run bal file containing syntax error")
    public void testRunBalFileWithSyntaxError() {
        // valid source root path
        Path balFilePath = this.testResources.resolve("bal-file-with-syntax-error").resolve("hello_world.bal");
        RunCommand runCommand = new RunCommand(balFilePath, printStream, false);
        // non existing bal file
        new CommandLine(runCommand).parse(balFilePath.toString());
        try {
            runCommand.execute();
        } catch (BLauncherException e) {
            Assert.assertTrue(e.getDetailedMessages().get(0).contains("compilation contains errors"));
        } finally {
            //TODO: remove this once the build env ctx is created per project
            BuildEnvContext buildEnvContext = BuildEnvContext.getInstance();
            CompilerContext compilerContext = buildEnvContext.compilerContext();
            BLangDiagnosticLog bLangDiagnosticLog = BLangDiagnosticLog.getInstance(compilerContext);
            bLangDiagnosticLog.resetErrorCount();
        }
    }

    @Test(description = "Run a valid ballerina file")
    public void testRunValidBalProject() throws IOException {
        Path projectPath = this.testResources.resolve("validRunProject");

        System.setProperty("user.dir", projectPath.toString());
        Path tempFile = projectPath.resolve("temp.txt");
        // set valid source root
        RunCommand runCommand = new RunCommand(projectPath, printStream, false);
        // name of the file as argument
        new CommandLine(runCommand).parse(tempFile.toString());

        Assert.assertFalse(tempFile.toFile().exists());
        runCommand.execute();
        Assert.assertTrue(tempFile.toFile().exists());

        Files.delete(tempFile);
    }

    @Test(description = "Run a valid ballerina file from a different directory")
    public void testRunValidBalProjectFromDifferentDirectory() throws IOException {
        Path projectPath = this.testResources.resolve("validRunProject");

        Path tempFile = projectPath.resolve("temp.txt");
        // set valid source root
        RunCommand runCommand = new RunCommand(projectPath, printStream, false);
        // name of the file as argument
        new CommandLine(runCommand).parse(projectPath.toString(), tempFile.toString());

        Assert.assertFalse(tempFile.toFile().exists());
        runCommand.execute();
        Assert.assertTrue(tempFile.toFile().exists());

        Files.delete(tempFile);
    }
}
