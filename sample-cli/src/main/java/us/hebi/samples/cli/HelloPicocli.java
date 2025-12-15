package us.hebi.samples.cli;

import picocli.CommandLine;

import java.util.AbstractMap;
import java.util.Comparator;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * @author Florian Enner
 * @since 15 Dec 2025
 */
@CommandLine.Command(
        name = "cli",
        mixinStandardHelpOptions = true,
        description = "sample cli tool",
        subcommands = {
                HelloPicocli.PrintProperties.class,
                HelloPicocli.PrintEnvironmentVariables.class,
        }
)
public class HelloPicocli implements Runnable {

    public static void main(String[] args) {
        System.exit(new CommandLine(new HelloPicocli()).execute(args));
    }

    @Override
    public void run() {
        System.out.println("Hello from Picocli! Add --help for options. \uD83C\uDF0D مرحبا بك 你好 こんにちは.");
    }

    public static class FilterMixin {

        @CommandLine.Option(names = {"--sorted"}, description = "Sorts the output.")
        private boolean sorted = false;

        @CommandLine.Option(names = {"--filter"}, description = "Filter entries by regex pattern.")
        private String filter;

        public Stream<Map.Entry<String, String>> filter(Stream<Map.Entry<String, String>> stream) {
            if (filter != null) {
                var pattern = Pattern.compile(filter);
                stream = stream.filter(entry -> pattern.matcher(entry.getKey()).find());
            }
            if (sorted) {
                stream = stream.sorted(Comparator.comparing(Map.Entry::getKey));
            }
            return stream;
        }

    }

    @CommandLine.Command(
            name = "print-environment-variables",
            mixinStandardHelpOptions = true,
            description = "prints all environment variables"
    )
    public static class PrintEnvironmentVariables implements Runnable {

        @CommandLine.Mixin
        private FilterMixin filterMixin;

        @Override
        public void run() {
            filterMixin.filter(System.getenv().entrySet().stream())
                    .forEach(entry -> System.out.println(entry.getKey() + "=" + entry.getValue()));
        }

    }

    @CommandLine.Command(
            name = "print-properties",
            mixinStandardHelpOptions = true,
            description = "prints all properties"
    )
    public static class PrintProperties implements Runnable {

        @CommandLine.Mixin
        private FilterMixin filterMixin;

        @Override
        public void run() {
            filterMixin.filter(System.getProperties().entrySet()
                            .stream().map(entry -> new AbstractMap.SimpleEntry<>(
                                    String.valueOf(entry.getKey()),
                                    String.valueOf(entry.getValue())
                            )))
                    .forEach(entry -> System.out.println(entry.getKey() + "=" + entry.getValue()));
        }

    }

}
