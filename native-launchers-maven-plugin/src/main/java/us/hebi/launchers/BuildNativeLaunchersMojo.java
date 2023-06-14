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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static us.hebi.launchers.Utils.*;

/**
 * @author Florian Enner
 * @since 09 Jun 2023
 */
@Mojo(name = "build-launchers", defaultPhase = LifecyclePhase.PACKAGE)
public class BuildNativeLaunchersMojo extends BaseConfig {

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (shouldSkip()) return;

        try {

            // Generate wrapper sources
            String template = loadResourceAsString(GenerateJavaSourcesMojo.class, "templates/main-dynamic.c");
            Path sourceDir = getGeneratedCSourceDir();
            Files.createDirectories(sourceDir);
            printDebug("Generating C sources in " + sourceDir);
            for (Launcher launcher : launchers) {
                String imgName = getNonNull(launcher.imageName, imageName);
                String sourceCode = fillTemplate(template, imgName, launcher.getSymbolName());
                writeToDisk(sourceCode, sourceDir, launcher.getCFileName());
                printDebug("Generated source file: " + launcher.getCFileName());
            }

            // Build the executables
            List<String> artifacts = new ArrayList<>();
            List<String> compiler = getCompiler();
            for (Launcher launcher : launchers) {

                // Compile source
                String outputName = launcher.name + (isWindows() ? ".exe" : "");
                Path exeFile = compileSource(compiler, sourceDir, launcher.getCFileName(), outputName, launcher.console);

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

    private String fillTemplate(String template, String imageName, String methodName) {
        return template
                .replaceAll("\\{\\{IMAGE_NAME}}", imageName)
                .replaceAll("\\{\\{METHOD_NAME}}", methodName);
    }

    private Path compileSource(List<String> compiler, Path srcDir, String srcFileName, String outputName, boolean console) throws IOException {
        // Compile the generated file
        List<String> processArgs = new ArrayList<>(compiler);
        processArgs.addAll(compilerArgs);
        processArgs.add("-o");
        processArgs.add(outputName);
        processArgs.add(srcFileName);
        if (debug) processArgs.add("-DDEBUG");
        if (isUnix()) processArgs.add("-ldl");
        processArgs.addAll(linkerArgs);
        processArgs.addAll(getDefaultLoadingPathOptions());
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
        List<String> compilerArgs = new ArrayList<>();
        if (compiler != null) {
            // User-specified compiler
            compilerArgs.addAll(compiler);
        } else {
            // Look through PATH for various candidates
            List<String> candidates = isWindows()
                    ? Arrays.asList("cl.exe", "zig.exe")
                    : Arrays.asList("cc", "gcc", "clang", "zig", getBuiltinClang());
            compilerArgs.add(findCompilerOnPath(candidates));

            // Note: zig cc works for the host target, but the
            // cross-compilation breaks dynamic loading.
            if (compilerArgs.get(0).startsWith("zig")) {
                compilerArgs.add("cc");
            }
        }
        return compilerArgs;
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

    private void runProcess(Path directory, String... args) throws IOException {
        runProcess(directory, Arrays.asList(args));
    }

    private void runProcess(Path directory, List<String> args) throws IOException {
        try {
            printDebug(String.join(" ", args));
            ProcessBuilder builder = new ProcessBuilder(args)
                    .directory(directory.toFile());
            if (debug) builder.inheritIO();
            Process process = builder.start();
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                throw new IOException("Execution timed out after " + timeout + " seconds.");
            }
        } catch (InterruptedException interrupted) {
            throw new IOException("Execution interrupted", interrupted);
        }
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

}
