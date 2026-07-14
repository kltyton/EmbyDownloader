package com.dud.embydownload.proxy

object MihomoConfigBuilder {
    const val PROXY_GROUP = "PROXY"
    const val CONTROLLER_SECRET = "embydownload"

    fun build(
        subscriptionUrl: String,
        mixedPort: Int,
        controllerPort: Int
    ): String {
        val escapedUrl = subscriptionUrl.trim().replace("'", "''")
        return """
            mixed-port: $mixedPort
            allow-lan: false
            bind-address: 127.0.0.1
            mode: rule
            log-level: error
            ipv6: false
            external-controller: 127.0.0.1:$controllerPort
            secret: $CONTROLLER_SECRET

            proxy-providers:
              subscription:
                type: http
                url: '$escapedUrl'
                interval: 86400
                health-check:
                  enable: false
                header:
                  User-Agent:
                    - ${MihomoInstaller.USER_AGENT}

            proxy-groups:
              - name: $PROXY_GROUP
                type: select
                use:
                  - subscription

            rules:
              - MATCH,$PROXY_GROUP
        """.trimIndent() + "\n"
    }
}
