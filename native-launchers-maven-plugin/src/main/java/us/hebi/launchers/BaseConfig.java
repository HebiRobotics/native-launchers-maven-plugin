/*-
 * #%L
 * native-launchers-maven-plugin Maven Mojo
 * %%
 * Copyright (C) 2022 - 2023 HEBI Robotics
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

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Parameter;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

/**
 * @author Florian Enner
 * @since 09 Jun 2023
 */
abstract class BaseConfig extends AbstractMojo {

    @Parameter(defaultValue = "${plugin}", readonly = true) // Maven 3 only
    protected PluginDescriptor plugin;

    @Parameter(defaultValue = "${session}", readonly = true)
    protected MavenSession session;

    @Parameter(property = "outputDir", defaultValue = "${project.build.directory}", required = true)
    protected String outputDirectory; // default to native-maven-plugin value

    @Parameter(property = "imageName", defaultValue = "${project.artifactId}", required = true)
    protected String imageName; // default to native-maven-plugin value

    @Parameter(property = "launchers.skip")
    protected boolean skip = false;

    @Parameter(property = "launchers.debug")
    protected boolean debug = false;

    @Parameter(property = "launchers.timeout")
    protected int timeout = 10;

    @Parameter(property = "launchers.sourceDirectory", required = true,
            defaultValue = "${project.build.directory}/generated-sources/native-launchers")
    protected String sourceDirectory;

    @Parameter(property = "launchers.sourceDirectory", defaultValue = "launchers", required = true)
    protected String launcherPackage;

    @Parameter
    protected List<String> compiler;

    @Parameter
    protected List<String> compilerArgs = Collections.emptyList();

    @Parameter
    protected List<String> linkerArgs = Collections.emptyList();

    @Parameter(required = true)
    protected List<Launcher> launchers;

    public Path getGeneratedJavaSourceDir() {
        return Paths.get(sourceDirectory, "java").toAbsolutePath();
    }

    public Path getGeneratedCSourceDir() {
        return Paths.get(sourceDirectory, "c").toAbsolutePath();
    }

    public static class Launcher {

        @Parameter(required = true)
        protected String name;

        @Parameter(required = true)
        protected String mainClass;

        @Parameter
        protected String outputDirectory;

        @Parameter
        protected String imageName;

        @Parameter
        protected boolean console = true;

        public String getMainClass() {
            return mainClass;
        }

        public String getName() {
            return name;
        }

        public String getCFileName() {
            return name + ".c";
        }

        public String getConventionalName() {
            return "run_" + mainClass.replaceAll("\\.", "_") + "_main";
        }

    }

    protected void printDebug(String message) {
        if (debug) {
            getLog().info(message);
        } else {
            getLog().debug(message);
        }
    }

    protected boolean shouldSkip() {
        if (skip) {
            getLog().info("Skipping native launcher generation (parameter skip is true)");
        }
        return skip;
    }

}
