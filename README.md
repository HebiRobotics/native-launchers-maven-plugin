# Native Launchers Maven Plugin

This plugin creates thin native launchers so that a single [native shared library](https://github.com/oracle/graal/blob/release/graal-vm/22.3/docs/reference-manual/native-image/InteropWithNativeCode.md) can be shared between multiple main methods. This significantly reduces the deployment size of multi-app bundles (e.g. CLI tools) that want to make use of [GraalVM](https://www.graalvm.org/)'s native-image.

For a full demo project you can take a look at the [sample-cli](./sample-cli/pom.xml) module, the [Conveyor](https://conveyor.hydraulic.dev/) packaging [configuration](./conveyor.conf), and the corresponding [Github Actions pipeline](.github/workflows/build-demo-images.yml). Installing one of the signed [pre-built packages](https://hebirobotics.github.io/native-launchers-maven-plugin/download.html) places the `launcher-hello` and `launcher-picocli` apps on the path. 

The launchers automatically add several convenience properties for metadata, e.g.,

```bash
bin> launcher-picocli.exe print-properties --sorted --filter launcher.*
launcher.displayName=launcher-picocli
launcher.executablePath=${absolutePath}\launcher-picocli.exe
launcher.imageName=launchers-native-cli
launcher.mainClass=us.hebi.samples.cli.HelloPicocli
launcher.nativeMethod=run_us_hebi_samples_cli_HelloPicocli_main
launcher.windows.userModelID=hebi.launchers.HelloPicocli
```

and provide a better default experience for command line apps: Windows streams are set to `UTF-8` automatically, and `-Dpicocli.ansi=tty` is set to enable ANSI coloring where possible.

## Maven Instructions

1. Add the build plugin and define the names of the executables and the Java main methods they should map to. For a full sample configuration check [sample-cli](./sample-cli/pom.xml).

```xml
<plugin>
    <groupId>us.hebi.launchers</groupId>
    <artifactId>native-launchers-maven-plugin</artifactId>
    <version>0.6</version>
    <configuration>
        <outputDirectory>${graalvm.outputDir}</outputDirectory>
        <imageName>${graalvm.imageName}</imageName>
        <launchers>
            <launcher>
                <name>cli-hello</name>
                <mainClass>us.hebi.samples.cli.HelloWorld</mainClass>
                <jvmArgs>
                    <arg>-Dcustom.property=true</arg>
                </jvmArgs>
                
                <!-- Disable default console for Windows GUI apps -->
                <!--<console>false</console>-->
                
                <!-- 
                For macOS GUI apps to emulate file open events similar
                to Windows/Linux, i.e., file events spawn a new process  
                with the file name as the first argument.
                -->
                <!--<cocoa>true</cocoa>-->
                <!--<cocoaFileHandler>true</cocoaFileHandler>-->
            </launcher>
            <launcher>
                <name>cli-dir</name>
                <mainClass>us.hebi.samples.cli.PrintDirectoryContents</mainClass>
            </launcher>
        </launchers>
    </configuration>
    <executions>
        <execution> <!-- before native-image compilation -->
            <id>generate-launchers</id>
            <goals>
                <goal>generate-launchers</goal>
            </goals>
        </execution>
    </executions>
</plugin> 
```

2. Create the GraalVM native-library with `<sharedLibrary>true</sharedLibrary>`. Note that the source may need [additional configuration](https://www.graalvm.org/22.3/reference-manual/native-image/guides/configure-with-tracing-agent/) (e.g. for JNI and reflection) to be compatible with native-image.

```xml
<plugin>
    <groupId>org.graalvm.buildtools</groupId>
    <artifactId>native-maven-plugin</artifactId>
    <version>${graalvm.tools.version}</version>
    <extensions>true</extensions>
    <configuration>
        <outputDirectory>${outputDir}</outputDirectory>
        <imageName>${imageName}</imageName>
        <sharedLibrary>true</sharedLibrary>
        <skip>${skipNativeBuild}</skip>
        <useArgFile>false</useArgFile>
        <skipNativeTests>true</skipNativeTests>
        <verbose>true</verbose>
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

## How does it work?

The plugin specifies the name of the executable and the corresponding Java method, e.g.,

```xml
<launcher>
    <name>hello</name> <!-- name of the executable -->
    <mainClass>us.hebi.samples.cli.HelloWorld</mainClass> <!-- mapped Java main method -->
    <console>true</console> <!-- false for UI apps -->
    <jvmArgs> <!-- custom properties and jvm options -->
        <arg>-Dcustom.property=true</arg>
    </jvmArgs>
    <userModelId>samples.hello!uniqueId</userModelId> <!-- unique id for Windows taskbar support -->
</launcher>
```

The plugin then generates a `jni-config.json` to ensure that the required entry points are included in the native-image.

```json
[
  {
    "name": "us.hebi.samples.cli.HelloWorld",
    "methods": [
      {
        "name": "main",
        "parameterTypes": ["java.lang.String[]"]
      }
    ]
  }
]
```

It additionally creates small native launchers that dynamically load the native library and call the entry point via JNI. Since the loading is dynamic, there are no compile time dependencies, and the launchers can be built independently of the native-image. The template for the native code generation is in [main-dynamic.c](native-launchers-maven-plugin/src/main/resources/us/hebi/launchers/templates/main-dynamic.c).

A hello world app with debug info enabled (`-Dlaunchers.debug`) produces the following printout

```bash
bin> hello.exe arg1 arg2
[DEBUG] Running on (Windows|Linux|macOS)
[DEBUG] Determining executable path property
[DEBUG] Set console output to UTF-8 (check: Æøåæøå)
[DEBUG] Set Application User Model Id: samples.hello!uniqueId
[DEBUG] Adding vm options:
[DEBUG] -Dpicocli.ansi=tty
[DEBUG] -Dfile.encoding=UTF-8
[DEBUG] -Dnative.encoding=UTF-8
[DEBUG] -Dsun.jnu.encoding=UTF-8
[DEBUG] -Dstdin.encoding=UTF-8
[DEBUG] -Dstdout.encoding=UTF-8
[DEBUG] -Dstderr.encoding=UTF-8
[DEBUG] -Dlauncher.windows.userModelID=samples.hello!uniqueId
[DEBUG] -Dlauncher.executablePath=${absolutePath}\launcher-hello.exe
[DEBUG] -Dlauncher.mainClass=us.hebi.samples.cli.HelloWorld
[DEBUG] -Dlauncher.displayName=launcher-hello
[DEBUG] -Dlauncher.imageName=launchers-native-cli
[DEBUG] -Dlauncher.nativeMethod=run_us_hebi_samples_cli_HelloWorld_main
[DEBUG] -Dlauncher.debug=true
[DEBUG] -Dcustom.property=true
[DEBUG] Loading library L"launchers-native-cli.dll"
[DEBUG] Looking up symbol: JNI_CreateJavaVM
[DEBUG] Loading class: us/hebi/samples/cli/HelloWorld
[DEBUG] Looking up method: main([Ljava/lang/String;)V
[DEBUG] Invoking main method for us.hebi.samples.cli.HelloWorld
Hello world! 🌍 مرحبا بك 你好 こんにちは
```

## Building the source

```bash
# build everything including native image and native launchers
mvn package -Pnative

# generate launchers, but skip native image
mvn package -Pnative -DskipNativeBuild

# build the sample project with the native image and additional debug info
mvn package -Pnative --projects sample-cli -am -Dlaunchers.debug
```

## JavaFX Applications

The JavaFX samples in [samples-javafx](./samples-javafx) are disabled by default, and were only confirmed working with [bellsoft's Liberica Native Image Kit](https://bell-sw.com/liberica-native-image-kit/)  NIK23 (JDK21) distribution of GraalVM. You can enable them with the `-PnativeJavaFX` profile.

```bash
mvn package -PnativeJavaFX
.\sample-javafx\target\image\windows-amd64\launcher-gui-verbose.exe
```

Most applications will likely need to run the tracing agent to determine used resources and reflectively accessed classes. It can be enabled by running the application with the vm option `-agentlib:native-image-agent=config-output-dir=sample-cli/src/main/resources/META-INF/native-image` and exploring all code paths at least once.

Newer versions (e.g. `NIK 25`) unfortunately fail with various configuration errors, e.g.,

```bash
Error: com.oracle.svm.util.ReflectionUtil$ReflectionUtilError: java.lang.NoSuchMethodException: com.sun.prism.shader.AlphaOne_Color_Loader.loadShader(com.sun.prism.ps.ShaderFactory,java.io.InputStream)
Caused by: com.oracle.svm.util.ReflectionUtil$ReflectionUtilError: java.lang.NoSuchMethodException: com.sun.prism.shader.AlphaOne_Color_Loader.loadShader(com.sun.prism.ps.ShaderFactory,java.io.InputStream)
        at org.graalvm.nativeimage.base/com.oracle.svm.util.ReflectionUtil.lookupMethod(ReflectionUtil.java:94)
        at org.graalvm.nativeimage.base/com.oracle.svm.util.ReflectionUtil.lookupMethod(ReflectionUtil.java:81)
        at org.graalvm.nativeimage.builder/com.oracle.svm.core.jdk.JNIRegistrationUtil.method(JNIRegistrationUtil.java:98)
        ...
```

## Known limitations
* The `@CEntryPoint` annotations require a compile dependency on the graal sdk. Attempts to generate the `.class` file directly ended in errors due to native-image requiring [a matching source file](https://github.com/graalvm/graal-jvmci-8/blob/master/jvmci/jdk.vm.ci.meta/src/jdk/vm/ci/meta/ResolvedJavaType.java#L315-L318).
* Each platform needs to be compiled individually, like the Graal native-image itself. Static linking requires an existing native-image, and dynamic linking is not supported when cross-compiling (tested with [zig 0.10.1](https://ziglang.org/download/0.10.1/release-notes.html)).
