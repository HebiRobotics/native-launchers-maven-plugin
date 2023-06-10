package us.hebi.samples.gui;

import javafx.application.Application;
import javafx.stage.Stage;

/**
 * @author Florian Enner
 * @since 08 Jun 2023
 */
public class HelloWorld extends Application {
    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        primaryStage.show();
    }

}