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

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author Florian Enner
 * @since 09 Jun 2023
 */
@Mojo(name = "build-launchers", defaultPhase = LifecyclePhase.PACKAGE)
public class BuildLaunchersMojo extends BaseConfig {

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {

            // Generate wrapper sources
            String template = loadResourceAsString(GenerateSourcesMojo.class, "main-dynamic.c");
            Path sourceDir = getGeneratedCSourceDir();
            Files.createDirectories(sourceDir);
            getLog().debug("Generating C sources in " + sourceDir);
            for (Launcher launcher : launchers) {
                String imgName = getNonNull(launcher.imageName, imageName);
                String sourceCode = fillTemplate(template, imgName, launcher.getConventionalName());
                writeSourceToDisk(sourceCode, sourceDir, launcher.getCFileName());
            }

            // Build the executables
            List<String> compiler = getCompiler();
            for (Launcher launcher : launchers) {

                // Compile source
                String outputName = launcher.name + (isWindows() ? ".exe" : "");
                Path exeFile = compileSource(compiler, sourceDir, launcher.getCFileName(), outputName, launcher.console);

                // Move result to the desired output directory
                Path outputDir = Path.of(getNonNull(launcher.outputDirectory, outputDirectory));
                Files.createDirectories(outputDir);
                Path targetFile = outputDir.resolve(outputName);
                Files.move(exeFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                getLog().info("output: " + targetFile);

            }

        } catch (IOException ioe) {
            throw new MojoFailureException(ioe);
        }

    }

    private String fillTemplate(String template, String imageName, String methodName) {
        return template
                .replaceAll("\\{\\{IMAGE_NAME}}", imageName)
                .replaceAll("\\{\\{METHOD_NAME}}", methodName);
    }

    private Path writeSourceToDisk(String content, Path targetDir, String fileName) throws IOException {
        Path srcFile = Files.writeString(targetDir.resolve(fileName), content,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        getLog().debug("Generated source file: " + fileName);
        return srcFile;
    }

    private Path compileSource(List<String> compiler, Path srcDir, String srcFileName, String outputName, boolean console) throws IOException {
        // Compile the generated file
        List<String> processArgs = new ArrayList<>(compiler);
        processArgs.addAll(compilerArgs);
        processArgs.add("-o");
        processArgs.add(outputName);
        processArgs.add(srcFileName);
        if (debug) {
            processArgs.add("-DDEBUG");
        }
        if (isUnix()) {
            processArgs.add("-ldl");
        }
        processArgs.addAll(linkerArgs);
        runProcess(srcDir, processArgs);

        // Disable the console window for non-console apps
        if (!console && isWindows()) {
            // Note that we modify the executable via EditBin because compiling with
            // /Subsystem:windows requires a template with a WinMain method
            getLog().debug("Changing " + outputName + " to a non-console app.");
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
            Path pathDir = Path.of(dir);
            for (String name : candidates) {
                Path cmd = pathDir.resolve(name);
                if (Files.isExecutable(cmd)) {
                    getLog().debug("Using compiler: " + cmd.toString());
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
                .map(graalHome -> Path.of(graalHome, "/languages/llvm/native/bin/clang"))
                .map(Path::toAbsolutePath)
                .map(Path::toString)
                .orElse("");
    }

    private void runProcess(Path directory, String... args) throws IOException {
        runProcess(directory, Arrays.asList(args));
    }

    private void runProcess(Path directory, List<String> args) throws IOException {
        try {
            getLog().debug(String.join(" ", args));
            ProcessBuilder builder = new ProcessBuilder(args)
                    .directory(directory.toFile());
            if (debug) {
                builder.inheritIO();
            }
            Process process = builder.start();
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                throw new IOException("Execution timed out");
            }
        } catch (InterruptedException interrupted) {
            throw new IOException("Execution interrupted", interrupted);
        }
    }

    private static String loadResourceAsString(Class<?> clazz, String name) throws IOException {
        // There is no simple way in Java 8, so https://stackoverflow.com/a/46613809/3574093
        try (InputStream is = clazz.getResourceAsStream(name)) {
            if (is == null) throw new IllegalStateException("Resource not found: " + name);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                return reader.lines().collect(Collectors.joining(System.lineSeparator()));
            }
        }
    }

    private static <T> T getNonNull(T... choices) {
        for (T choice : choices) {
            if (choice != null) return choice;
        }
        throw new IllegalArgumentException("all options are null");
    }

    private static boolean isMac() {
        return OS.contains("mac");
    }

    private static boolean isUnix() {
        return (OS.contains("nix") || OS.contains("nux") || OS.indexOf("aix") > 0);
    }

    private static boolean isWindows() {
        return OS.startsWith("win");
    }

    private static final String OS = System.getProperty("os.name").toLowerCase(Locale.US);

}
