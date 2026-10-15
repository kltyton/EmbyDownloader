module com.dud.embydownload {
    requires javafx.controls;
    requires javafx.fxml;
    requires kotlin.stdlib;
    requires java.net.http;
    requires com.google.gson;
    requires java.prefs;
    requires org.yaml.snakeyaml;
    // jlink 自定义运行时默认可能不带这些模块，会导致 HTTPS 握手失败（handshake_failure）
    requires jdk.crypto.ec;
    requires jdk.crypto.cryptoki;

    opens com.dud.embydownload to javafx.fxml;
    opens com.dud.embydownload.controller to javafx.fxml;
    opens com.dud.embydownload.emby.model to com.google.gson;
    opens com.dud.embydownload.settings to com.google.gson;
    opens com.dud.embydownload.integration.theater to com.google.gson;

    exports com.dud.embydownload;
    exports com.dud.embydownload.ui;
}
