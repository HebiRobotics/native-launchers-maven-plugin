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

package us.hebi.sass;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author Florian Enner
 * @since 09 Jun 2023
 */
@Mojo(name = "build")
@Execute(phase = LifecyclePhase.PACKAGE)
public class BuildMojo extends BaseMojo {

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {

            String cTemplate = loadResourceAsString("template.c");
            getLog().error(String.valueOf(launchers));
            for (Launcher launcher : launchers) {

                Path imgDir = Path.of(Optional.ofNullable(launcher.imageDirectory).orElse(imageDirectory));
                String imgName = Optional.ofNullable(launcher.imageName).orElse(imageName);

                // Generate C source file
                String cContent = cTemplate
                        .replaceAll("\\{\\{HEADER_FILE}}", imgName + ".h")
                        .replaceAll("\\{\\{METHOD_NAME}}", launcher.getConventionalName());

                Files.createDirectories(imgDir);

                Path cFile = Files.writeString(imgDir.resolve(launcher.getCFileName()), cContent,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                getLog().info("Generated C source file: " + cFile.toAbsolutePath());

                // TODO: use zig for cross-platform builds? e.g.
                // zig cc -o hello.exe hello_world.c -I. -L. -lhello-lib

                final String[] args;
                if (PlatformUtil.isWindows()) {
                    // use MSVC compiler (should be available for native image to work)
                    args = new String[]{
                            "cl.exe", "-I.",
                            launcher.getCFileName(),
                            "/link", imgName + ".lib"
                    };
                } else {
                    // use built-in llvm toolchain as shown in
                    // https://www.graalvm.org/22.2/reference-manual/native-image/guides/build-native-shared-library/
                    String graalHome = System.getenv("GRAALVM_HOME");
                    if (graalHome == null) {
                        throw new MojoFailureException("GRAALVM_HOME is not defined");
                    }
                    args = new String[]{
                            graalHome + "/languages/llvm/native/bin/clang", "-W1",
                            "-I", "./",
                            "-rpath", "./",
                            "-l", imgName,
                            "-o", launcher.getName(),
                            launcher.getCFileName()
                    };
                }


                getLog().info("Compiling C source: [" + String.join(" ", args) + "]");
                Process process = new ProcessBuilder(args)
                        .directory(imgDir.toFile())
                        .inheritIO()
                        .start();
                if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                    getLog().error("Compilation failed or timed out");
                }

            }
        } catch (Exception ioe) {
            throw new MojoFailureException(ioe);
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
