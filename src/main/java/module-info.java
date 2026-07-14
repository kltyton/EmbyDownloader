module com.dud.embydownload {
    requires javafx.controls;
    requires javafx.fxml;
    requires kotlin.stdlib;
    requires java.net.http;
    requires com.google.gson;
    requires java.prefs;
    requires org.yaml.snakeyaml;

    opens com.dud.embydownload to javafx.fxml;
    opens com.dud.embydownload.controller to javafx.fxml;
    opens com.dud.embydownload.emby.model to com.google.gson;
    opens com.dud.embydownload.settings to com.google.gson;

    exports com.dud.embydownload;
    exports com.dud.embydownload.ui;
}
