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

    @Parameter
    protected boolean debug = false;

    @Parameter
    protected int timeout = 10;

    // where binaries get copied to. Should be the GraalVM default output
    // https://github.com/graalvm/native-build-tools/blob/master/native-maven-plugin/src/main/java/org/graalvm/buildtools/maven/AbstractNativeImageMojo.java#L100-L101
    @Parameter(property = "outputDir", defaultValue = "${project.build.directory}", required = true)
    protected String outputDirectory; // where the binary files get copied to

    @Parameter(property = "imageName", defaultValue = "${project.artifactId}", required = true)
    protected String imageName;

    @Parameter(property = "sourceDirectory", defaultValue = "${project.build.directory}/generated-sources/native-launchers", required = true)
    protected String sourceDirectory;

    @Parameter(defaultValue = "launchers", required = true)
    protected String launcherPackage;

    @Parameter
    protected List<String> compiler;

    @Parameter
    protected List<String> compilerArgs = Collections.emptyList();

    @Parameter
    protected List<String> linkerArgs = Collections.emptyList();

    @Parameter(required = true)
    protected List<Launcher> launchers;

    public Path getGeneratedJavaSourceDir(){
        return Path.of(sourceDirectory, "java").toAbsolutePath();
    }

    public Path getGeneratedCSourceDir(){
        return Path.of(sourceDirectory, "c").toAbsolutePath();
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

}
