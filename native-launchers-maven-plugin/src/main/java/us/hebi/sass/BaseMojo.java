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

package us.hebi.sass;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Parameter;

import java.util.List;

/**
 * @author Florian Enner
 * @since 09 Jun 2023
 */
abstract class BaseMojo extends AbstractMojo {

    @Parameter(defaultValue = "${plugin}", readonly = true) // Maven 3 only
    protected PluginDescriptor plugin;

    @Parameter(defaultValue = "${session}", readonly = true)
    protected MavenSession session;

    // https://github.com/graalvm/native-build-tools/blob/master/native-maven-plugin/src/main/java/org/graalvm/buildtools/maven/AbstractNativeImageMojo.java#LL106C28-L106C37
    @Parameter(property = "imageName", defaultValue = "${project.artifactId}", required = true)
    protected String imageName;

    @Parameter(property = "imageDirectory", defaultValue = "${project.build.directory}", required = true)
    protected String imageDirectory;

    @Parameter(property = "sourcePackage", defaultValue = "graalvm", required = true)
    protected String sourcePackage;

    @Parameter(property = "javaSourceDirectory", defaultValue = "${project.build.directory}/generated-sources/native-java", required = true)
    protected String javaSourceDirectory;

    @Parameter(property = "cSourceDirectory", defaultValue = "${project.build.directory}/generated-sources/native-c", required = true)
    protected String cSourceDirectory;

    @Parameter(property = "launchers", required = true)
    protected List<Launcher> launchers;

    public static class Launcher {

        @Parameter(property = "name", required = true)
        protected String name;

        @Parameter(property = "mainClass", required = true)
        protected String mainClass;

        @Parameter(property = "imageName", required = false)
        protected String imageName;

        @Parameter(defaultValue = "${project.build.directory}", required = false)
        protected String imageDirectory;

        public String getConventionalName() {
            return "run_" + mainClass.replaceAll("\\.", "_") + "_main";
        }

    }

}
