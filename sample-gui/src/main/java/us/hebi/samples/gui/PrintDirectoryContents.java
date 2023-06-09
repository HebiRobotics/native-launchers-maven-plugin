package us.hebi.samples.gui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @author Florian Enner
 * @since 08 Jun 2023
 */
public class PrintDirectoryContents {
    public static void main(String[] args) {
        Path dir = Path.of(args.length > 0 ? args[0] : ".");
        int maxDepth = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        try {
            Files.walk(dir, maxDepth)
                    .map(dir::relativize)
                    .forEach(System.out::println);
        } catch (IOException e) {
            System.err.println(e.getMessage());
        }
    }
}
