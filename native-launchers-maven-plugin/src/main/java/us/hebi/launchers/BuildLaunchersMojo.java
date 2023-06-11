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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
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

            String cTemplate = loadResourceAsString("main-dynamic.c");
            for (Launcher launcher : launchers) {

                Path imgDir = Path.of(Optional.ofNullable(launcher.imageDirectory).orElse(imageDirectory));
                String imgName = Optional.ofNullable(launcher.imageName).orElse(imageName);
                String outputName = launcher.name + (isWindows() ? ".exe" : "");

                // Generate C source file
                String cContent = cTemplate
                        .replaceAll("\\{\\{IMAGE_NAME}}", imgName)
                        .replaceAll("\\{\\{METHOD_NAME}}", launcher.getConventionalName());

                Files.createDirectories(imgDir);

                Path cFile = Files.writeString(imgDir.resolve(launcher.getCFileName()), cContent,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                getLog().info("Generated C source file: " + cFile.toAbsolutePath());

                // Compile the generated file. The dynamic template has no dependencies,
                // so we don't need any includes or linker arguments.
                List<String> compilerArgs = new ArrayList<>();
                if (this.compiler != null) {
                    // pick e.g. "zig cc"
                    compilerArgs.addAll(Arrays.asList(this.compiler.split(" ")));
                } else if (isWindows()) {
                    // use an installed zig compiler and build for the host os (no -target ${arch}-${os})
                    compilerArgs.add("cl");
                } else {
                    // use graal's built-in llvm toolchain
                    compilerArgs.add(getClangPath().toString());

                    // TODO: figure out how to load without a static path?
                    // TODO: add -ldl ? (https://stackoverflow.com/a/71630334/3574093)
                }

                // Add added compiler args first to work with "zig cc"
                if (this.compilerArgs != null) {
                    compilerArgs.addAll(this.compilerArgs);
                }

                // Debug printouts
                if (debug) {
                    compilerArgs.add("-DDEBUG");
                }

                // Add shared arguments
                compilerArgs.add("-o");
                compilerArgs.add(outputName);
                compilerArgs.add(launcher.getCFileName());
                runProcess(imgDir, compilerArgs);

                // Disable the console window for non-console apps
                if (!launcher.console && isWindows()) {
                    // Note that we modify the executable via EditBin because compiling with
                    // /Subsystem:windows requires a template with a WinMain method
                    if (!launcher.console) {
                        getLog().debug("Changing " + launcher.name + " to a non-console app.");
                        runProcess(imgDir, "EditBin.exe", "/Subsystem:windows", launcher.name + ".exe");
                    }
                }

            }

        } catch (IOException ioe) {
            throw new MojoFailureException(ioe);
        }

    }

    private static Path getClangPath() throws MojoFailureException {
        // use built-in llvm toolchain as shown in
        // https://www.graalvm.org/22.2/reference-manual/native-image/guides/build-native-shared-library/
        String graalHome = System.getenv("GRAALVM_HOME");
        if (graalHome == null) {
            throw new MojoFailureException("GRAALVM_HOME is not defined (needed for built-in clang)");
        }
        Path clang = Path.of(graalHome, "/languages/llvm/native/bin/clang");
        if (!Files.isExecutable(clang)) {
            throw new MojoFailureException("could not find clang: " + clang + "\n" +
                    "Please run: $GRAALVM_HOME/bin/gu install llvm-toolchain");
        }
        return clang;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.US).startsWith("win");
    }

    private void runProcess(Path directory, String... args) throws MojoExecutionException {
        runProcess(directory, Arrays.asList(args));
    }

    private void runProcess(Path directory, List<String> args) throws MojoExecutionException {
        try {
            getLog().info("Executing [" + String.join(" ", args) + "]");
            Process process = new ProcessBuilder(args)
                    .directory(directory.toFile())
                    .inheritIO()
                    .start();
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                throw new MojoExecutionException("Execution failed or timed out");
            }
        } catch (InterruptedException interrupted) {
            throw new MojoExecutionException("Execution timed out", interrupted);
        } catch (IOException ioe) {
            throw new MojoExecutionException("Execution failed", ioe);
        }
    }

    private static String loadResourceAsString(String name) throws IOException {
        // There is no simple way in Java 8, so https://stackoverflow.com/a/46613809/3574093
        try (InputStream is = GenerateSourcesMojo.class.getResourceAsStream(name)) {
            if (is == null) throw new IllegalStateException("Resource not found: " + name);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                return reader.lines().collect(Collectors.joining(System.lineSeparator()));
            }
        }
    }

}
