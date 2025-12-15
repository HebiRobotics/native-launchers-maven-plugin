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

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static us.hebi.launchers.Utils.*;

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
                    "24.0.2",
                    "provided"
            );

        } catch (IOException ioe) {
            throw new MojoExecutionException(ioe);
        }

    }

    private Path generateJavaSource(Path targetDir) throws IOException {
        // Generate Java wrapper class
        TypeSpec.Builder type = TypeSpec.classBuilder("NativeLaunchers");
        Set<String> generatedEntryPoints = new HashSet<>();
        for (Launcher launcher : launchers) {
            // Allow multiple launchers using the same entry with different options
            if (!generatedEntryPoints.add(launcher.getSymbolName())) {
                continue;
            }

            // Annotation for including the method in the native library
            AnnotationSpec cEntry = AnnotationSpec.builder(CEntryPointClass)
                    .addMember("name", "$S", launcher.getSymbolName())
                    .build();

            // Optional debug block
            CodeBlock.Builder debugCode = CodeBlock.builder();
            if (debug) {
                debugCode.addStatement("$T.out.println(String.format($S, $T.toString(args)))", System.class,
                        "[DEBUG] calling " + launcher.getMainClass() + " (args: %s)", Arrays.class);
            }

            // Wrapper that forwards to the specified main
            type.addMethod(MethodSpec.methodBuilder(launcher.getSymbolName())
                    .addModifiers(Modifier.STATIC)
                    .addAnnotation(cEntry)
                    .returns(int.class)
                    .addParameter(IsolateThreadClass, "thread", Modifier.FINAL)
                    .addParameter(int.class, "argc", Modifier.FINAL)
                    .addParameter(CCharPointerPointerClass, "argv", Modifier.FINAL)
                    .beginControlFlow("try")
                    .addStatement("String[] args = toJavaArgs(argc, argv)")
                    .addCode(debugCode.build())
                    .addStatement("$T.main(args)", ClassName.bestGuess(launcher.mainClass))
                    .addStatement("return 0")
                    .nextControlFlow("catch (Throwable t)")
                    .addStatement("t.printStackTrace()")
                    .addStatement("return 1")
                    .endControlFlow()
                    .build());
        }

        // Convert C-style argc/argv to Java-style args
        type.addMethod(MethodSpec.methodBuilder("toJavaArgs")
                .addParameter(int.class, "argc", Modifier.FINAL)
                .addParameter(CCharPointerPointerClass, "argv", Modifier.FINAL)
                .returns(String[].class)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addComment("Java omits the name of the program argv[0]")
                .addStatement("String[] args = new String[argc - 1]")
                .beginControlFlow(" for (int i = 0; i < args.length; i++)")
                .addStatement("args[i] = $T.toJavaString(argv.addressOf(i + 1).read())", CTypeConversionClass)
                .endControlFlow()
                .addStatement("return args")
                .build());

        // write to a .java file
        Path outFile = JavaFile.builder(launcherPackage, type.build()).build().writeToPath(targetDir);
        StringBuilder msg = new StringBuilder("Generated launcher entry points in ").append(outFile).append(":");
        getLog().info(appendLauncherEntriesTable(msg));
        return outFile;

    }

    private StringBuilder appendLauncherEntriesTable(StringBuilder out) {
        int columnWidth = launchers.stream()
                .map(Launcher::getMainClass)
                .mapToInt(String::length)
                .max().orElse(0);
        for (Launcher launcher : launchers) {
            out.append("\n ");
            appendSpaced(out, launcher.getMainClass(), columnWidth);
            out.append(" (").append(launcher.getSymbolName()).append(")");
        }
        return out;
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

    // Use ClassName so we can remove the Graal sdk dependency
    static final ClassName IsolateThreadClass = ClassName.bestGuess("org.graalvm.nativeimage.IsolateThread");
    static final ClassName CEntryPointClass = ClassName.bestGuess("org.graalvm.nativeimage.c.function.CEntryPoint");
    static final ClassName CCharPointerPointerClass = ClassName.bestGuess("org.graalvm.nativeimage.c.type.CCharPointerPointer");
    static final ClassName CTypeConversionClass = ClassName.bestGuess("org.graalvm.nativeimage.c.type.CTypeConversion");

}
