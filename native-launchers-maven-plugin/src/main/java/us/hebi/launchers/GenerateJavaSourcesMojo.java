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
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/**
 * Generates a Java source file for the
 * GraalVM native image entry points.
 *
 * @author Florian Enner
 * @since 09 Jun 2023
 */
@Mojo(name = "gen-sources", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class GenerateJavaSourcesMojo extends BaseConfig {

    @Override
    public void execute() throws MojoExecutionException {
        if (shouldSkip()) return;

        try {

            // Create Java source and add to the compilation
            Path targetDir = getGeneratedJavaSourceDir();
            generateJavaSource(targetDir);
            session.getCurrentProject().addCompileSourceRoot(targetDir.toString());

            // Warn if the project is missing a dependency. The annotation seems to
            // be duplicated across different artifacts, so it may still be there.
            checkAnnotationDependency(
                    "org.graalvm.nativeimage",
                    "native-image-base",
                    "22.3.2",
                    "provided"
            );

        } catch (IOException ioe) {
            throw new MojoExecutionException(ioe);
        }

    }

    private Path generateJavaSource(Path targetDir) throws IOException {
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
        Path output = JavaFile.builder(launcherPackage, type.build()).build().writeToPath(targetDir);
        printDebug("Generated Java stubs in " + output);
        return output;

    }

    private boolean checkAnnotationDependency(String groupId, String artifactId, String version, String scope) {
        boolean hasDependency = session.getCurrentProject().getDependencies().stream()
                .filter(dep -> Objects.equals(groupId, dep.getGroupId()))
                .anyMatch(dep -> Objects.equals(artifactId, dep.getArtifactId()));
        if (!hasDependency) {
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
        return hasDependency;
    }

}