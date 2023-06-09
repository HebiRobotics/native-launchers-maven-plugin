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

package us.hebi.sass;

import com.squareup.javapoet.*;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointerPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;

import javax.lang.model.element.Modifier;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * @author Florian Enner
 * @since 30 Jul 2022
 */
@Mojo(name = "gen-entries")
@Execute(phase = LifecyclePhase.GENERATE_SOURCES)
public class GenerateEntriesMojo extends BaseMojo {

    @Override
    public void execute() throws MojoExecutionException {
        try {
            generateJavaSource();
            generateCSources();
        } catch (IOException ioe) {
            throw new MojoExecutionException(ioe);
        }
        session.getCurrentProject().addCompileSourceRoot(javaSourceDirectory);
    }

    private File generateJavaSource() throws IOException {
        // Generate Java wrapper class
        TypeSpec.Builder type = TypeSpec.classBuilder("EntryPoints");
        for (Launcher launcher : launchers) {
            getLog().debug("Generating Java stub for " + launcher.mainClass);

            // Annotation for including the method in the native library
            AnnotationSpec cEntry = AnnotationSpec.builder(CEntryPoint.class)
                    .addMember("name", "$S", launcher.getConventionalName())
                    .build();

            // Wrapper that forwards to the specified main
            type.addMethod(MethodSpec.methodBuilder(launcher.getConventionalName())
                    .addModifiers(Modifier.STATIC)
                    .addAnnotation(cEntry)
                    .returns(int.class)
                    .addParameter(IsolateThread.class, "thread", Modifier.FINAL)
                    .addParameter(int.class, "argc", Modifier.FINAL)
                    .addParameter(CCharPointerPointer.class, "argv", Modifier.FINAL)
                    .beginControlFlow("try")
                    .addStatement("$T.main(toJavaArgs(argc, argv))", ClassName.bestGuess(launcher.mainClass))
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
        File output = JavaFile.builder(sourcePackage, type.build()).build()
                .writeToFile(new File(javaSourceDirectory));
        getLog().debug("Generated Java stubs in " + output.getAbsolutePath());
        return output;

    }

    private List<File> generateCSources() throws IOException {
        List<File> files = new ArrayList<>();
        for (Launcher launcher : launchers) {

            final byte[] content = loadResourceAsString("template.c")
                    .replaceAll("\\{\\{HEADER_FILE}}", Optional.ofNullable(launcher.imageName).orElse(imageName) + ".h")
                    .replaceAll("\\{\\{METHOD_NAME}}", launcher.getConventionalName())
                    .getBytes(StandardCharsets.UTF_8);

            Path cFile = Paths.get(cSourceDirectory, launcher.name + ".c");
            Files.createDirectories(cFile.getParent());
            getLog().debug("Generated C wrapper in " + cFile);

            Files.write(cFile, content,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);

        }
        return files;
    }

    private static String loadResourceAsString(String name) throws IOException {
        // There is no simple way in Java 8, so https://stackoverflow.com/a/46613809/3574093
        try (InputStream is = GenerateEntriesMojo.class.getResourceAsStream(name)) {
            if (is == null) throw new IllegalStateException("Resource not found: " + name);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                return reader.lines().collect(Collectors.joining(System.lineSeparator()));
            }
        }
    }

}
