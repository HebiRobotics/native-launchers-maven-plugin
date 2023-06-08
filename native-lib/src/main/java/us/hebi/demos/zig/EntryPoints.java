package us.hebi.demos.zig;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @author Florian Enner
 * @since 08 Jun 2023
 */
public class EntryPoints {

    @CEntryPoint(name = "cli_print_directory_contents")
    public static void printDirectoryContents(IsolateThread thread) throws IOException {
        Files.walk(Path.of("."), 1).forEach(System.out::println);
    }

    @CEntryPoint(name = "math_add")
    static int add(IsolateThread thread, int a, int b) {
        return a + b;
    }

}
