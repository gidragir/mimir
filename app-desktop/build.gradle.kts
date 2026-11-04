import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

group = providers.gradleProperty("mimir.group").get()
version = providers.gradleProperty("mimir.version").get()

dependencies {
    implementation(project(":core"))
    implementation(project(":drive"))
    implementation(project(":client"))
    implementation(project(":daemon"))
    implementation(project(":ui"))
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.transport.native)
    implementation(compose.desktop.currentOs)
    implementation(libs.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}

compose.desktop {
    application {
        mainClass = "dev.mimir.desktop.MainKt"
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = providers.gradleProperty("mimir.appName").get()
            packageVersion = providers.gradleProperty("mimir.version").get()
            appResourcesRootDir.set(project.layout.projectDirectory.dir("src/packaging"))
            windows {
                perUserInstall = true
                shortcut = true
                menu = true
                dirChooser = true
                upgradeUuid = providers.gradleProperty("mimir.msiUpgradeUuid").get()
                iconFile.set(project.file("src/main/resources/icon.ico"))
            }
            macOS {
                bundleID = providers.gradleProperty("mimir.appId").get()
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleURLTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleURLName</key>
                                <string>Mimir Protocol</string>
                                <key>CFBundleURLSchemes</key>
                                <array>
                                    <string>mimir</string>
                                </array>
                            </dict>
                        </array>
                    """.trimIndent()
                }
            }
        }
    }
}

// Wayland-композиторы без репарентинга (niri): иначе AWT рисует окно неверно
// задача run регистрируется Compose-плагином позже, поэтому configureEach
tasks.withType<JavaExec>().configureEach {
    if (name == "run") {
        environment("_JAVA_AWT_WM_NONREPARENTING", "1")
        val mimirEnv = providers.environmentVariablesPrefixedBy("MIMIR_").get()
        environment.keys.removeIf { it.startsWith("MIMIR_") }
        environment(mimirEnv)
    }
}

// Kotlin 2.4 таргетит JVM 26 при JDK 27, выравниваем javac по Kotlin
tasks.withType<JavaCompile>().configureEach { options.release = 26 }

// Отключаем перезапуск Windows-лаунчера jpackage (win.norestart=true), чтобы в Диспетчере задач
// не создавался родительский процесс-обёртка и отображался ровно один процесс Mimir.exe.
tasks.configureEach {
    if (name.startsWith("createDistributable") || name.startsWith("createReleaseDistributable")) {
        doLast {
            patchWindowsCfg()
        }
    }
    if (name.contains("packageMsi", ignoreCase = true) || name.contains("packageReleaseMsi", ignoreCase = true)) {
        doFirst {
            patchWindowsCfg()
        }
    }
}

fun patchWindowsCfg() {
    val buildDir = layout.buildDirectory.orNull?.asFile ?: return
    val appName = providers.gradleProperty("mimir.appName").get()
    if (buildDir.exists()) {
        buildDir.walkTopDown()
            .filter { it.isFile && it.name.equals("$appName.cfg", ignoreCase = true) }
            .forEach { cfg ->
                val content = cfg.readText()
                if (!content.contains("win.norestart")) {
                    cfg.writeText(content.replace("[Application]", "[Application]\r\nwin.norestart=true"))
                }
            }
    }
}
