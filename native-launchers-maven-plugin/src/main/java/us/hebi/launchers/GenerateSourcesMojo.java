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

import com.squareup.javapoet.*;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointerPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;

import javax.lang.model.element.Modifier;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Generates a Java source file for the
 * GraalVM native image entry points.
 *
 * @author Florian Enner
 * @since 30 Jul 2022
 */
@Mojo(name = "gen-sources", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class GenerateSourcesMojo extends BaseConfig {

    @Override
    public void execute() throws MojoExecutionException {
        try {
            generateJavaSource();
        } catch (IOException ioe) {
            throw new MojoExecutionException(ioe);
        }

        // Add the generated sources to the compilation
        session.getCurrentProject().addCompileSourceRoot(sourceDirectory);

        // Warn if the project is missing a dependency
        String groupId = "org.graalvm.nativeimage";
        String artifactId = "native-image-base";
        String version = "22.3.2";
        String scope = "provided";
        boolean hasCompileDependency = session.getCurrentProject().getDependencies().stream()
                .filter(dep -> Objects.equals(groupId, dep.getGroupId()))
                .anyMatch(dep -> Objects.equals(artifactId, dep.getArtifactId()));
        if (!hasCompileDependency) {
            getLog().info("-------------------------------------------------------------");
            getLog().warn("REQUIRED ANNOTATION DEPENDENCY MAY BE MISSING:");
            getLog().info("-------------------------------------------------------------\n" +
                    "  <dependency>\n" +
                    "      <groupId>" + groupId + "</groupId>\n" +
                    "      <artifactId>" + artifactId + "</artifactId>\n" +
                    "      <version>" + version + "</version>\n" +
                    "      <scope>" + scope + "</scope>\n" +
                    "  </dependency>");
        }
    }

    private File generateJavaSource() throws IOException {
        // Generate Java wrapper class
        TypeSpec.Builder type = TypeSpec.classBuilder("NativeLaunchers");
        for (Launcher launcher : launchers) {
            getLog().info("Generating launcher entry point for " + launcher.mainClass);

            // Annotation for including the method in the native library
            AnnotationSpec cEntry = AnnotationSpec.builder(CEntryPoint.class)
                    .addMember("name", "$S", launcher.getConventionalName())
                    .build();

            // Optional debug block
            CodeBlock.Builder debugCode = CodeBlock.builder();
            if (debug) {
                debugCode.addStatement("$T.out.println(String.format($S, $T.toString(args)))", System.class,
                        "[DEBUG] calling " + launcher.getMainClass() + " (args: %s)", Arrays.class);
            }

            // Wrapper that forwards to the specified main
            type.addMethod(MethodSpec.methodBuilder(launcher.getConventionalName())
                    .addModifiers(Modifier.STATIC)
                    .addAnnotation(cEntry)
                    .returns(int.class)
                    .addParameter(IsolateThread.class, "thread", Modifier.FINAL)
                    .addParameter(int.class, "argc", Modifier.FINAL)
                    .addParameter(CCharPointerPointer.class, "argv", Modifier.FINAL)
                    .beginControlFlow("try")
                    .addStatement("String[] args = toJavaArgs(argc, argv)")
                    .addCode(debugCode.build())
                    .addStatement("$T.main(args)", ClassName.bestGuess(launcher.mainClass))
                    .addStatement("return 0")
                    .nextControlFlow("catch (Throwable t)")
                    .addStatement("t.printStackTrace()")
                    .addStatement("return -1")
                    .endControlFlow()
                    .build());
        }

        // Convert C-style argc/argv to Java-style args
        type.addMethod(MethodSpec.methodBuilder("toJavaArgs")
                .addParameter(int.class, "argc", Modifier.FINAL)
                .addParameter(CCharPointerPointer.class, "argv", Modifier.FINAL)
                .returns(String[].class)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addComment("C adds the executable name as the first argument")
                .addStatement("String[] args = new String[argc - 1]")
                .beginControlFlow(" for (int i = 0; i < args.length; i++)")
                .addStatement("args[i] = $T.toJavaString(argv.addressOf(i + 1).read())", CTypeConversion.class)
                .endControlFlow()
                .addStatement("return args")
                .build());

        // write to a .java file
        File output = JavaFile.builder(launcherPackage, type.build()).build()
                .writeToFile(new File(sourceDirectory));
        getLog().debug("Generated Java stubs in " + output.getAbsolutePath());
        return output;

    }

}
