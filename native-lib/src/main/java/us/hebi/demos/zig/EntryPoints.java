package us.hebi.demos.zig;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointerPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import us.hebi.demos.zig.apps.HelloWorld;
import us.hebi.demos.zig.apps.PrintDirectory;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * @author Florian Enner
 * @since 08 Jun 2023
 */
public class EntryPoints {

    @CEntryPoint(name = "run_print_directory_contents")
    public static int printDirectoryContents(IsolateThread thread, int argc, CCharPointerPointer argv) {
        return runJavaMain(argc, argv, PrintDirectory::main);
    }

    @CEntryPoint(name = "run_hello_world")
    public static int runHelloWorld(IsolateThread thread, int argc, CCharPointerPointer argv) {
        return runJavaMain(argc, argv, HelloWorld::main);
    }

    @CEntryPoint(name = "math_add")
    static int add(IsolateThread thread, int a, int b) {
        return a + b;
    }

    // Default main
    public static void main(String[] args) {
        HelloWorld.main(args);
    }

    private static int runJavaMain(int argc, CCharPointerPointer argv, Consumer<String[]> main) {
        // The first argument is the executable name
        String executableName = CTypeConversion.toJavaString(argv.addressOf(0).read());
        System.out.println("executableName = " + executableName);

        // Convert C argv to args (the first value is the name)
        String[] args = new String[argc - 1];
        for (int i = 0; i < args.length; i++) {
            args[i] = CTypeConversion.toJavaString(argv.addressOf(i + 1).read());
        }
        System.out.println("args = " + Arrays.toString(args));

        // Print errors on the Java side
        try {
            System.out.println("running main");
            main.accept(args);
            return 0;
        } catch (Throwable t) {
            t.printStackTrace();
            return 1;
        }
    }

}
