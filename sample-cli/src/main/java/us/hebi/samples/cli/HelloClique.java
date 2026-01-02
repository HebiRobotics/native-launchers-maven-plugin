package us.hebi.samples.cli;

import com.github.kusoroadeolu.clique.Clique;
import com.github.kusoroadeolu.clique.ansi.ColorCode;
import com.github.kusoroadeolu.clique.ansi.StyleCode;
import com.github.kusoroadeolu.clique.boxes.Box;
import com.github.kusoroadeolu.clique.boxes.BoxType;
import com.github.kusoroadeolu.clique.config.*;
import com.github.kusoroadeolu.clique.core.utils.AnsiDetector;
import com.github.kusoroadeolu.clique.indent.Flag;
import com.github.kusoroadeolu.clique.indent.Indenter;
import com.github.kusoroadeolu.clique.parser.AnsiStringParser;
import com.github.kusoroadeolu.clique.tables.TableType;
import picocli.CommandLine;

/**
 * Example for https://github.com/kusoroadeolu/Clique
 * full examples in https://github.com/kusoroadeolu/Clique/tree/main/src/main/java/com/github/kusoroadeolu/clique/demo
 *
 * @author Florian Enner
 * @since 31 Dec 2025
 */
@CommandLine.Command(
        name = "clique",
        mixinStandardHelpOptions = true,
        description = "colored clique sample"
)
public class HelloClique implements Runnable {

    public static void main(String[] args) {
        System.exit(new CommandLine(new HelloClique()).execute(args));
    }

    @Override
    public void run() {
        printHello();

        // Sanity check Clique's ANSI detection w/ Picocli
        System.getProperty("picocli.ansi", "tty");
        Clique.styleBuilder()
                .stack("", StyleCode.BOLD)
                .stack("", AnsiDetector.ansiEnabled() ? ColorCode.GREEN : ColorCode.RED)
                .stack("AnsiDetector.ansiEnabled() = " + AnsiDetector.ansiEnabled())
                .stack("\n", CommandLine.Help.Ansi.AUTO.enabled() ? ColorCode.GREEN : ColorCode.RED)
                .stack("Picocli.ANSI.enabled() = " + CommandLine.Help.Ansi.AUTO.enabled())
                .print();

        printStyledTable();
        printBox();
        printIndented();
        printFileTree();

    }

    void printHello() {
        Clique.styleBuilder()
                .stack("Hello", ColorCode.BRIGHT_BLUE, StyleCode.BOLD, StyleCode.UNDERLINE)
                .stack(" Clique!", ColorCode.BLUE, StyleCode.ITALIC)
                .print();
    }

    void printStyledTable() {
        BorderStyle style = BorderStyle.immutableBuilder()
                .horizontalBorderStyles(ColorCode.YELLOW)
                .verticalBorderStyles(ColorCode.BRIGHT_YELLOW)
                .edgeBorderStyles(ColorCode.YELLOW)
                .build();

        TableConfiguration configuration = TableConfiguration
                .immutableBuilder()
                .columnAlignment(0, CellAlign.LEFT)  // Column alignment will always take precedence over table alignment
                .borderStyle(style) // Style class for styling table borders
                .parser(Clique.parser()) // Set a parser for the table to enable markup formatting for rows
                .alignment(CellAlign.CENTER) // Centers each row's values. Rows are left aligned by default
                .padding(2) // The amount of whitespace added to each value in a cell to avoid cramping
                .build();

        var table = Clique.table(TableType.BOX_DRAW, configuration)
                .addHeaders("[green, bold]Name[/]", "[green, bold]Age[/]", "[green, bold]Class[/]") //Notice the markup, Clique automatically parses this under the hood
                .addRows("[red, bold]John[/]", "25", "Class A")
                .addRows("[red, bold]Doe[/]", "26", "Class B");
        table.render();
    }

    void printBox() {
        // Define a colorful border style
        BorderStyle style = BorderStyle.immutableBuilder()
                .horizontalBorderStyles(ColorCode.CYAN)
                .verticalBorderStyles(ColorCode.MAGENTA)
                .edgeBorderStyles(ColorCode.YELLOW)
                .build();

        // Build the box configuration
        BoxConfiguration config = BoxConfiguration.immutableBuilder()
                .borderStyle(style)
                .textAlign(TextAlign.CENTER) // Where the text should be aligned in the box
                .centerPadding(3) // The amount of padding from both sides, when the content of the box is centered
                .autoSize(true) // Will automatically resize the box, if the box cannot wrap around the content
                .parser(Clique.parser()) // A parser is provided by default, but you can pass a customized parser here
                .build();

        Box b = Clique.box(BoxType.DOUBLE_LINE, config)
                .content("[bold, blue]This is a configured box"); // This box will be autoAdjusted, no need for a length or width if you don't need it

        b.render();
    }

    void printIndented() {
        Indenter indenter = Clique.indenter()
                .indent(1, Flag.CIRCLE + " ")  // Create first indent level
                .add("[blue, bold]Root Level[/]") // markdown parsing enabled by default
                .indent(2, "[green]" + Flag.ARROW + "[/] ")  // Nest deeper
                .add("Nested Item 1")
                .add("Nested Item 2")
                .unindent()  // Go back up
                .add("[blue, bold]Back to Root[/]");

        indenter.print();  // Print the indented structure
    }

    void printFileTree() {
        // custom parser for adding closing tags
        AnsiStringParser parser = Clique.parser(ParserConfiguration.immutableBuilder()
                .enableAutoCloseTags()
                .build());

        IndenterConfiguration config = IndenterConfiguration.immutableBuilder()
                .parser(parser)
                .indentLevel(2)
                .build();

        Indenter tree = Clique.indenter(config)
                .add("[blue, bold]project/[/]")
                .indent("[magenta]├─ ")
                .add("[yellow]src/[/]")
                .indent()
                .add("com.github.kusoroadeolu.Main.java")
                .add("Utils.java")
                .unindent()
                .add("[yellow]test/[/]")
                .indent()
                .add("MainTest.java")
                .unindent()
                .unindent()
                .indent("[magenta]└─ ")
                .add("README.md");

        tree.print();

    }

    void resetColors() {
        if (AnsiDetector.ansiEnabled()) {
            System.out.println("\u001B[0m");
            System.out.flush();
        }
    }

    void clearScreen() {
        if (AnsiDetector.ansiEnabled()) {
            System.out.print("\033[H\033[2J");
            System.out.flush();
        }
    }

}
