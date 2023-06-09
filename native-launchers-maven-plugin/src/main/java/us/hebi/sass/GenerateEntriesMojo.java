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

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.FixedValue;
import net.bytebuddy.implementation.Implementation;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointerPointer;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;

/**
 * @author Florian Enner
 * @since 30 Jul 2022
 */
@Mojo(name = "gen-entries")
@Execute(phase = LifecyclePhase.COMPILE)
public class GenerateEntriesMojo extends BaseMojo {

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("Generating entries");

        // Generate compiled class contents so that users don't need to compile against graal libs.
        // Unfortunately, it looks like GraalVM requires a source file, so the pre-compiled approach
        // may not work. This attempt fails with:
        //
        //      Fatal error: java.lang.NullPointerException: Cannot invoke "String.compareTo(String)"
        //      because the return value of "jdk.vm.ci.meta.ResolvedJavaType.getSourceFileName()" is null

        DynamicType.Builder<?> clazz = new ByteBuddy(ClassFileVersion.JAVA_V8)
                .subclass(Object.class)
                .name("graal.Entries");

        for (Launcher launcher : launchers) {

            Implementation impl = FixedValue.value(0);

            AnnotationDescription cEntryAnnotation = AnnotationDescription.Builder.ofType(CEntryPoint.class)
                    .define("name", launcher.getConventionalName())
                    .build();

            clazz = clazz.defineMethod(launcher.getConventionalName(), int.class, Modifier.PUBLIC | Modifier.STATIC)
                    .withParameters(IsolateThread.class, int.class, CCharPointerPointer.class)
                    .intercept(impl)
                    .annotateMethod(cEntryAnnotation);

        }

        // write to a .class file
        try {
            clazz.make().saveIn(new File(classOutputDirectory));
        } catch (IOException e) {
            throw new MojoExecutionException(e);
        }


        System.out.println(classOutputDirectory);

        getLog().error(String.valueOf(launchers));

    }

}
