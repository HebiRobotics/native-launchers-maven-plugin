# Native Launchers Maven Plugin

This plugin creates thin native launchers so that one [native shared library](https://www.graalvm.org/22.2/reference-manual/native-image/guides/build-native-shared-library/) can be shared between multiple main methods. This significantly reduces the deployment size of suite of apps (e.g. CLI tools) that want to make use of [GraalVM](https://www.graalvm.org/)'s native-image.

It roughly works like this:
* A Java file with appropriate `@CEntryPoint` methods is generated during the `generate-sources` stage
* The Java file gets included in the native-image compilation and includes matching symbols
* A tiny native executable loads the library and calls the native entry point

The launchers use dynamic linking and have no external dependencies, so they can be built at any time and without requiring the native library or headers. A hello world app with debug info enabled prints the following

```bash
bin> cli-hello.exe arg1 arg2
[DEBUG] Windows
[DEBUG] load library cli
[DEBUG] lookup symbol graal_create_isolate
[DEBUG] lookup symbol run_us_hebi_samples_cli_HelloWorld_main
[DEBUG] initializing isolate
[DEBUG] calling run_us_hebi_samples_cli_HelloWorld_main
[DEBUG] calling us.hebi.samples.cli.HelloWorld (args: [arg1, arg2])
Hello world!
```

**Known issues / limitations**
* The `@CEntryPoint` annotations require a compile dependency on the graal sdk. Attempts to generate the `.class` file directly ended in errors due to native-image requiring [a matching source file](https://github.com/graalvm/graal-jvmci-8/blob/master/jvmci/jdk.vm.ci.meta/src/jdk/vm/ci/meta/ResolvedJavaType.java#L315-L318).
* Each platform needs to be compiled individually like the Graal native-image. Static linking requires an existing native-image, and dynamic linking is not supported when cross-compiling (at least on [zig 0.10.1](https://ziglang.org/download/0.10.1/release-notes.html)).

**Things that still need to be figured out**
* Linux / macOS
  * **Calling apps from another directory currently fails due to the library not being found (!!!)**. The lib should be loaded relative to the binary rather than the working directory.
  * Support known relative locations to better support app packages (e.g. macOS bundles)
* JavaFX
  * Support JavaFX launchers. The [gluonfx plugin](https://github.com/gluonhq/gluonfx-maven-plugin) added a `sharedlib` target, but for some reason [it does not work with JavaFX code](https://docs.gluonhq.com/#_native_shared_libraries).

## Usage

1. add the compilation dependency for the generated graal annotations
```xml
<dependency>
    <groupId>org.graalvm.nativeimage</groupId>
    <artifactId>native-image-base</artifactId>
    <version>22.3.2</version>
    <scope>provided</scope>
</dependency>
```

2. add the build plugin and define the names of the executables and the Java main methods they should map to. For a full sample configuration check [sample-cli](./sample-cli/pom.xml).

```xml
<plugin>
    <groupId>us.hebi.launchers</groupId>
    <artifactId>native-launchers-maven-plugin</artifactId>
    <version>1.0-SNAPSHOT</version>
    <configuration>
        <outputDirectory>${graalvm.imageDir}</outputDirectory>
        <imageName>${graalvm.imageName}</imageName>
        <launchers>
            <launcher>
                <name>cli-hello</name>
                <mainClass>us.hebi.samples.cli.HelloWorld</mainClass>
            </launcher>
            <launcher>
                <name>cli-dir</name>
                <mainClass>us.hebi.samples.cli.PrintDirectoryContents</mainClass>
            </launcher>
        </launchers>
    </configuration>
    <executions>
        <execution> <!-- before compilation and native-image -->
            <id>generate-stubs</id>
            <goals>
                <goal>gen-sources</goal>
            </goals>
        </execution>
        <execution> <!-- at any time, defaults to the packaging phase -->
            <id>build-executables</id>
            <goals>
                <goal>build-launchers</goal>
            </goals>
        </execution>
    </executions>
</plugin> 
```

3. create the GraalVM native-library with `<sharedLibrary>true</sharedLibrary>`. Note that the source may need [additional configuration](https://www.graalvm.org/22.3/reference-manual/native-image/guides/configure-with-tracing-agent/) (e.g. for JNI and reflection) to be compatible with native-image.

```xml
<plugin>
    <groupId>org.graalvm.buildtools</groupId>
    <artifactId>native-maven-plugin</artifactId>
    <version>${graalvm.tools.version}</version>
    <extensions>true</extensions>
    <configuration>
        <outputDirectory>${graalvm.imageDir}</outputDirectory>
        <imageName>${graalvm.imageName}</imageName>
        <sharedLibrary>true</sharedLibrary>
        <skip>${graalvm.skip}</skip>
        <verbose>true</verbose>
        <useArgFile>false</useArgFile>
        <fallback>false</fallback>
        <skipNativeTests>true</skipNativeTests>
    </configuration>
    <executions>
        <execution>
            <id>build-native</id>
            <goals>
                <goal>compile-no-fork</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

## Build

```bash
# everything including native image and native launchers
mvn package -Pnative

# generate launchers, but skip native image
mvn package -Pnative -Dgraalvm.skip

# sample project with native image and additional debug info
mvn package -Pnative --projects sample-cli -am -Dlaunchers.debug
```

