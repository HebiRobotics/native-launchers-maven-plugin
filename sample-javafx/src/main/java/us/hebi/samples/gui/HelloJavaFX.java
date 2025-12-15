package us.hebi.samples.gui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * @author Florian Enner
 * @since 19 Okt 2023
 */
public class HelloJavaFX extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        stage.setTitle(String.format("%s (%s) + Java %s + JavaFX %s",
                System.getProperty("os.name"),
                System.getProperty("os.version"),
                System.getProperty("java.version"),
                System.getProperty("javafx.version")
        ));
        var root = new StackPane(new Button("Hello World!"));
        stage.setScene(new Scene(root, 500, 500));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }

}
