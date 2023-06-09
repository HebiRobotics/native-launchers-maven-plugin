package us.hebi.demos.zig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @author Florian Enner
 * @since 08 Jun 2023
 */
public class PrintDirectory {
    public static void main(String[] args) {
        String dir = args.length == 0 ? "." : args[0];
        try {
            Files.walk(Path.of(dir), 1).forEach(System.out::println);
        } catch (IOException e) {
            System.err.println(e.getMessage());
        }
    }
}
