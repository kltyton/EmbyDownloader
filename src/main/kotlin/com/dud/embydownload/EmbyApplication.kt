package com.dud.embydownload

import com.dud.embydownload.proxy.DownloadHttpClients
import com.dud.embydownload.ui.AppShell
import javafx.application.Application
import javafx.stage.Stage

class EmbyApplication : Application() {
    override fun start(stage: Stage) {
        AppShell.init(stage)
        AppShell.navigateReplace<Any>("login-view.fxml", "Emby Download")
        AppShell.show()
    }

    override fun stop() {
        DownloadHttpClients.shutdownEmbeddedCore()
        super.stop()
    }
}
