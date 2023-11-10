package us.hebi.samples.gui;

import javafx.application.Application;

/**
 * @author Florian Enner
 * @since 09 Nov 2023
 */
public class GuiAppVerbose {

    public static void main(String[] args) throws Exception {
        // We can't set properties on a shared lib, so we need to manually add fx properties
        System.setProperty("javafx.verbose", "true");
        System.out.println(Class.forName("javafx.application.Application"));
        System.out.println(Class.forName("us.hebi.samples.gui.GuiApp"));
        GuiApp.main(args);
    }

}
