/*-
 * #%L
 * sass-cli-maven-plugin Maven Mojo
 * %%
 * Copyright (C) 2022 HEBI Robotics
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
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Generates a GraalVM config file to enable
 * lookup via JNI calls.
 *
 * @author Florian Enner
 * @since 09 Jun 2023
 */
@Mojo(name = "gen-config", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class GenerateConfigMojo extends BaseConfig {

    @Override
    public void execute() throws MojoExecutionException {
        if (shouldSkip()) return;

        try {
            Path targetDir = getGeneratedMetaInfDir();
            generateJniConfig(targetDir);
            printDebug("Generated JNI configuration for native-image");
        } catch (IOException ioe) {
            throw new MojoExecutionException(ioe);
        }

    }

    private Path generateJniConfig(Path targetDir) throws IOException {
        Files.createDirectories(targetDir);

        Set<String> processedClasses = new HashSet<>();
        StringBuilder jniConfig = new StringBuilder();
        jniConfig.append("[\n");
        boolean needsComma = false;

        for (Launcher launcher : launchers) {
            String mainClass = launcher.getMainClass();

            // Only add each class once
            if (!processedClasses.add(mainClass)) {
                continue;
            }

            if (needsComma) {
                jniConfig.append(",\n");
            }
            needsComma = true;
            jniConfig.append("  {\n");
            jniConfig.append("    \"name\": \"").append(mainClass).append("\",\n");
            jniConfig.append("    \"methods\": [\n");
            jniConfig.append("      {\n");
            jniConfig.append("        \"name\": \"main\",\n");
            jniConfig.append("        \"parameterTypes\": [\"java.lang.String[]\"]\n");
            jniConfig.append("      }\n");
            jniConfig.append("    ]\n");
            jniConfig.append("  }");
        }

        jniConfig.append("\n]");

        Path configFile = targetDir.resolve("jni-config.json");
        Files.write(configFile, jniConfig.toString().getBytes(StandardCharsets.UTF_8));

        StringBuilder msg = new StringBuilder("Generated JNI config in ").append(configFile).append(":");
        for (Launcher launcher : launchers) {
            msg.append("\n  ").append(launcher.getMainClass()).append(".main(String[])");
        }
        getLog().info(msg.toString());

        return configFile;
    }

    public Path getGeneratedMetaInfDir() {
        return Paths.get(
                session.getCurrentProject().getBuild().getOutputDirectory(),
                "META-INF",
                "native-image",
                "launchers",
                groupId,
                artifactId
        ).toAbsolutePath();
    }

}
