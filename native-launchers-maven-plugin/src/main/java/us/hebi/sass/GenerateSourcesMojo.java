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
import java.io.File;
import java.io.IOException;

/**
 * Generates a Java source file for the
 * GraalVM native image entry points.
 *
 * @author Florian Enner
 * @since 30 Jul 2022
 */
@Mojo(name = "gen-sources")
@Execute(phase = LifecyclePhase.GENERATE_SOURCES)
public class GenerateSourcesMojo extends BaseMojo {

    @Override
    public void execute() throws MojoExecutionException {
        try {
            generateJavaSource();
        } catch (IOException ioe) {
            throw new MojoExecutionException(ioe);
        }
        session.getCurrentProject().addCompileSourceRoot(sourceDirectory);
    }

    private File generateJavaSource() throws IOException {
        // Generate Java wrapper class
        TypeSpec.Builder type = TypeSpec.classBuilder("NativeLaunchers");
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
                .writeToFile(new File(sourceDirectory));
        getLog().debug("Generated Java stubs in " + output.getAbsolutePath());
        return output;

    }

}
