plugins {
    java
    application
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.javamodularity.moduleplugin") version "1.8.15"
    id("org.openjfx.javafxplugin") version "0.0.13"
    id("org.beryx.jlink") version "2.25.0"
}

group = "com.dud"
version = "1.0-SNAPSHOT"

// jpackage 安装包版本（不能用 SNAPSHOT）
val appReleaseVersion = "1.1.3"

repositories {
    mavenCentral()
}

val junitVersion = "5.12.1"

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

application {
    mainModule.set("com.dud.embydownload")
    mainClass.set("com.dud.embydownload.LauncherKt")
    applicationDefaultJvmArgs = listOf("-Djava.net.useSystemProxies=true")
}

kotlin {
    jvmToolchain(17)
}

javafx {
    version = "21.0.6"
    modules = listOf("javafx.controls", "javafx.fxml")
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.yaml:snakeyaml:2.3")

    testImplementation("org.junit.jupiter:junit-jupiter-api:${junitVersion}")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:${junitVersion}")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

jlink {
    imageZip.set(layout.buildDirectory.file("/distributions/app-${javafx.platform.classifier}.zip"))
    // 强制带上 TLS/HTTPS 所需加密模块（仅依赖 java.net.http 时 jlink 常会漏掉）
    options.set(
        listOf(
            "--strip-debug",
            "--compress", "2",
            "--no-header-files",
            "--no-man-pages",
            "--add-modules", "jdk.crypto.ec,jdk.crypto.cryptoki",
        )
    )
    launcher {
        name = "EmbyDownload"
    }
    jpackage {
        appVersion = appReleaseVersion
        imageName = "EmbyDownload"
        installerName = "EmbyDownload"
        installerType = "exe"
        icon = "packaging/app-icon.ico"
        // 避免旧 installer 进程锁住 build/jpackage 导致无法清理重建
        outputDir = "jpackage-out"
        installerOptions = listOf(
            "--win-dir-chooser",
            "--win-menu",
            "--win-shortcut",
        )
    }
}

// 将内置 mihomo 打进发行包，用户解压安装目录即可使用
val nativeMihomoDir = layout.projectDirectory.dir("native/windows")

distributions {
    main {
        contents {
            from(nativeMihomoDir) {
                into("native/windows")
                include("mihomo.exe")
            }
        }
    }
}

tasks.named<Sync>("installDist") {
    from(nativeMihomoDir) {
        into("native/windows")
        include("mihomo.exe")
    }
}

tasks.named("jlink") {
    doLast {
        val imageDir = layout.buildDirectory.dir("image").get().asFile
        copy {
            from(nativeMihomoDir) {
                include("mihomo.exe")
                into("native/windows")
            }
            into(imageDir)
        }
    }
}

tasks.named("jpackageImage") {
    doLast {
        val imageDir = layout.buildDirectory.dir("jpackage-out/EmbyDownload").get().asFile
        copy {
            from(nativeMihomoDir) {
                include("mihomo.exe")
                into("native/windows")
            }
            into(imageDir)
        }
    }
}

// 便携版：含 EmbyDownload.exe + 内置 JRE + mihomo，无需 WiX
tasks.register<Zip>("packagePortable") {
    group = "distribution"
    description = "打包便携版 zip（解压后运行 EmbyDownload.exe）"
    dependsOn("jpackageImage")
    archiveFileName.set("EmbyDownload-$appReleaseVersion-portable-win.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.buildDirectory.dir("jpackage-out/EmbyDownload"))
}

tasks.register<Zip>("packageTheater") {
    group = "distribution"
    description = "打包 Emby Theater 插件与后台运行时"
    dependsOn("jpackageImage")
    archiveFileName.set("EmbyDownload-$appReleaseVersion-theater-plugin-win.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from("integration/theater/plugin") { into("plugin") }
    from("integration/theater/install") { into("install") }
    from(layout.buildDirectory.dir("jpackage-out/EmbyDownload")) { into("backend") }
    from("LICENSE")
}
