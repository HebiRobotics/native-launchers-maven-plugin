/*-
 * #%L
 * native-launchers-maven-plugin Maven Mojo
 * %%
 * Copyright (C) 2023 HEBI Robotics
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

package us.hebi.launchers;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;

import static us.hebi.launchers.Utils.*;

/**
 * @author Florian Enner
 * @since 09 Jun 2023
 */
@Mojo(name = "build-launchers", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class BuildNativeLaunchersMojo extends BaseConfig {

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (shouldSkip()) return;

        try {

            // Generate wrapper sources
            String template = loadResourceAsString(GenerateJavaSourcesMojo.class, "templates/launcher_dynamic.c");
            Path sourceDir = getGeneratedCSourceDir();
            Files.createDirectories(sourceDir);
            printDebug("Generating C sources in " + sourceDir);
            boolean hasOnlyConsoleLaunchers = true;
            for (Launcher launcher : launchers) {
                hasOnlyConsoleLaunchers &= launcher.console;
                String sourceCode = fillTemplate(template, launcher);
                writeToDisk(sourceCode, sourceDir, launcher.getCFileName());
                printDebug("Generated source file: " + launcher.getCFileName());
            }

            // Add shared header
            writeToDisk(loadResourceAsString(GenerateJavaSourcesMojo.class, "templates/graal_jni_dynamic.h"), sourceDir, "graal_jni_dynamic.h");
            writeToDisk(loadResourceAsString(GenerateJavaSourcesMojo.class, "templates/launcher_utils.h"), sourceDir, "launcher_utils.h");

            // Add optional Cocoa launcher
            if (isMac() && !hasOnlyConsoleLaunchers) {
                writeToDisk(
                        loadResourceAsString(GenerateJavaSourcesMojo.class, "templates/AppDelegate.m"),
                        sourceDir, "AppDelegate.m");
                printDebug("Copied source file: AppDelegate.m");
            }

            // Build the executables
            List<String> artifacts = new ArrayList<>();
            List<String> compiler = getCompiler();
            for (Launcher launcher : launchers) {

                // Compile source
                String outputName = launcher.name + (isWindows() ? ".exe" : "");
                getLog().info("Compiling " + launcher.getCFileName());
                Path exeFile = compileSource(compiler, sourceDir, launcher.getCFileName(), outputName, launcher.console, launcher.userModelId);

                // Move result to the desired output directory
                Path outputDir = Paths.get(getNonNull(launcher.outputDirectory, outputDirectory));
                Files.createDirectories(outputDir);
                Path targetFile = outputDir.resolve(outputName);
                Files.move(exeFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                artifacts.add(targetFile.toString());

            }

            getLog().info("Produced artifacts:\n " + String.join("\n ", artifacts));

        } catch (IOException ioe) {
            throw new MojoFailureException(ioe);
        }

    }

    private String fillTemplate(String template, Launcher launcher) {
        String imageName = getNonNull(launcher.imageName, this.imageName);
        String entrypoint = launcher.getSymbolName();

        List<String> jvmArgs = new ArrayList<>();
        jvmArgs.add("-Dlauncher.mainClass=" + launcher.mainClass);
        jvmArgs.add("-Dlauncher.displayName=" + launcher.name);
        jvmArgs.add("-Dlauncher.imageName=" + imageName);
        jvmArgs.add("-Dlauncher.nativeMethod=" + entrypoint);
        if (debug) {
            jvmArgs.add("-Dlauncher.debug=true");
        }
        jvmArgs.addAll(this.jvmArgs);
        jvmArgs.addAll(launcher.jvmArgs);

        printDebug("global jvm args: " + this.jvmArgs);
        printDebug("local jvm args: " + launcher.jvmArgs);

        StringBuilder argString = new StringBuilder();
        for (String jvmArg : jvmArgs) {
            argString.append("\n    options[nOptions++].optionString = \"").append(jvmArg).append("\";");
        }

        return template
                .replaceAll("\\{\\{NUM_JVM_ARGS}}", String.valueOf(jvmArgs.size()))
                .replaceAll("\\{\\{JVM_ARGS}}", argString.toString())
                .replaceAll("\\{\\{IMAGE_NAME}}", imageName)
                .replaceAll("\\{\\{METHOD_NAME}}", entrypoint);
    }

    private Path compileSource(List<String> compiler, Path srcDir, String srcFileName, String outputName, boolean console, String userModelId) throws MojoExecutionException {
        // Compile the generated file
        List<String> processArgs = new ArrayList<>(compiler);
        processArgs.addAll(compilerArgs);
        if (isWindows()) {
            processArgs.add("/Fe" + outputName);
        } else {
            processArgs.add("-o");
            processArgs.add(outputName);
        }
        processArgs.add(srcFileName);
        if (!console && isMac()) {
            processArgs.add("AppDelegate.m");
            processArgs.add("-framework");
            processArgs.add("Cocoa");
        }
        if (console) processArgs.add("-DCONSOLE");
        if (debug) processArgs.add("-DDEBUG");
        if (isWindows() && !Utils.isNullOrEmpty(userModelId)) {
            processArgs.add("-DAUMID=" + userModelId);
            processArgs.add("/link");
            processArgs.add("shell32.lib");
        }
        if (isUnix()) processArgs.add("-ldl");
        processArgs.addAll(linkerArgs);
        processArgs.addAll(getDefaultLoadingPathOptions());
        processArgs.addAll(getConveyorOptions());
        runProcess(srcDir, processArgs);

        // Disable the console window for non-console apps
        if (!console && isWindows()) {
            // Note that we modify the executable via EditBin because compiling with
            // /Subsystem:windows requires a template with a WinMain method
            printDebug("Changing " + outputName + " to a non-console app.");
            runProcess(srcDir, "EditBin.exe", "/Subsystem:windows", outputName);
        }
        return srcDir.resolve(outputName);
    }

    private List<String> getCompiler() throws FileNotFoundException {
        // Note: we could compile the native launchers with various compilers, but
        // due to some limitations regarding dynamic loading of libraries (zig doesn't
        // support it for non-native platforms) and compiling objc code for GUI applications
        // on macOS, we stick to the compilers officially supported by GraalVM. Non-standard
        // options can be chosen manually via the compiler args.
        if (compiler != null && !compiler.isEmpty()) return new ArrayList<>(compiler);
        return Collections.singletonList(getGraalDefaultCompiler());
    }

    private static String getGraalDefaultCompiler() {
        if (isWindows()) return "cl.exe";
        if (isMac()) return "cc";
        return "gcc";
    }

    private String findCompilerOnPath(List<String> candidates) throws FileNotFoundException {
        String[] directories = Optional.ofNullable(System.getenv("PATH")).orElse("")
                .split(isWindows() ? ";" : ":");
        for (String dir : directories) {
            Path pathDir = Paths.get(dir);
            for (String name : candidates) {
                Path cmd = pathDir.resolve(name);
                if (Files.isExecutable(cmd)) {
                    printDebug("Using compiler: " + cmd.toString());
                    return name;
                }
            }
        }
        throw new FileNotFoundException("None of the supported compilers were found on your system: " + candidates);
    }

    private static String getBuiltinClang() {
        // the graalvm llvm toolchain includes a clang
        // install: $GRAALVM_HOME/bin/gu install llvm-toolchain
        // https://www.graalvm.org/22.2/reference-manual/native-image/guides/build-native-shared-library/
        return Optional.ofNullable(System.getenv("GRAALVM_HOME"))
                .map(graalHome -> Paths.get(graalHome, "/languages/llvm/native/bin/clang"))
                .map(Path::toAbsolutePath)
                .map(Path::toString)
                .orElse("");
    }

    private void runProcess(Path directory, String... args) throws MojoExecutionException {
        runProcess(directory, Arrays.asList(args));
    }

    private void runProcess(Path directory, List<String> rawArgs) throws MojoExecutionException {
        try {
            List<String> args = escapeArgs(rawArgs);
            printDebug(String.join(" ", args));
            Commandline cli = new Commandline();
            cli.setWorkingDirectory(directory.toFile());
            cli.addArguments(args.toArray(new String[0]));
            int returnCode = CommandLineUtils.executeCommandLine(cli,
                    System.out::println,
                    System.err::println);
            if (returnCode != 0) {
                throw new MojoExecutionException("Compiler returned error code " + returnCode + ". Args:\n" + String.join(" ", args));
            }
        } catch (CommandLineException ex) {
            throw new MojoExecutionException(ex);
        }
    }

    private static List<String> escapeArgs(List<String> args) {
        if (!isWindows()) {
            return args;
        }

        // Powershell has issues with -D arguments, e.g., -DCONSOLE,
        // but always adding quotes breaks linux and macOS.
        List<String> escaped = new ArrayList<>(args.size());
        for (String arg : args) {
            if (arg.startsWith("-D")) {
                escaped.add("\"" + arg + "\"");
            } else {
                escaped.add(arg);
            }
        }
        return escaped;
    }

    /**
     * Where the library can be found relative to the executable:
     * Default to same directory and Conveyor-like app package layouts
     */
    static List<String> getDefaultLoadingPathOptions() {
        if (isWindows()) {
            return Collections.emptyList();
        } else if (isMac()) {
            return Arrays.asList(
                    "-Wl,-rpath,@loader_path",
                    "-Wl,-rpath,@loader_path/../lib",
                    "-Wl,-rpath,@loader_path/../Frameworks",
                    "-Wl,-rpath,@loader_path/../runtime/Contents/Home/lib",
                    "-Wl,-rpath,@loader_path/../runtime/Contents/Home/lib/server"
            );
        } else {
            return Arrays.asList(
                    "-Wl,-rpath,${ORIGIN}",
                    "-Wl,-rpath,${ORIGIN}/../lib",
                    "-Wl,-rpath,${ORIGIN}/../lib/runtime/lib",
                    "-Wl,-rpath,${ORIGIN}/../lib/runtime/lib/server"
            );
        }
    }

    static List<String> getConveyorOptions() {
        if (isMac()) {
            // On macOS Conveyor will inject its own library into the binary to initialize
            // the update system. This ensures that there is empty space in the headers
            // to make this possible.
            // see https://conveyor.hydraulic.dev/15.1/configs/native-apps/#building-compatible-binaries
            return Arrays.asList(
                    "-Wl,-rpath,@executable_path/../Frameworks",
                    "-Wl,-headerpad,0xFF"
            );
        }
        return Collections.emptyList();
    }

}
