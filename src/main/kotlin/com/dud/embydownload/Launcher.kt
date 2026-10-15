package com.dud.embydownload

import javafx.application.Application

fun main(args: Array<String>) {
    if (args.contentEquals(arrayOf("--theater-service"))) {
        com.dud.embydownload.integration.theater.PluginService.run()
    } else {
        Application.launch(EmbyApplication::class.java, *args)
    }
}
