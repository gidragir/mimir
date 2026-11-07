import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("mimir.jvm")
    kotlin("plugin.serialization")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

dependencies {
    implementation(project(":libs:core"))
    implementation(project(":libs:drive"))
    implementation(project(":libs:client"))
    implementation(project(":apps:daemon"))
    implementation(project(":libs:ui"))
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
    // Dispatchers.Main = AWT EDT (ServiceLoader): Main.kt переключается на него из трея и IPC-активации
    runtimeOnly(libs.coroutines.swing)
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
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg)
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
                iconFile.set(project.file("src/main/resources/icon.icns"))
                // jpackage на macOS отвергает версию с мажором 0 (CFBundleVersion). Пока mimir.version = 0.x,
                // bundle получает 1.x.y; имя dmg в релизе release.yml возвращает к настоящей версии.
                val macVersion = macBundleVersion(providers.gradleProperty("mimir.version").get())
                packageVersion = macVersion
                dmgPackageVersion = macVersion
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

// Тесты, как и приложение: Skiko и PlatformFocus (FFM) обращаются к нативному коду.
tasks.withType<Test>().configureEach { jvmArgs("--enable-native-access=ALL-UNNAMED") }

// Отключаем перезапуск Windows-лаунчера jpackage (win.norestart=true), чтобы в Диспетчере задач
// не создавался родительский процесс-обёртка и отображался ровно один процесс Mimir.exe.
// Логика патча намеренно не вынесена в функцию скрипта: ссылка на объект скрипта в действии
// задачи ломает configuration cache. Захватываются только значения (каталог сборки, имя).
val patchCfgBuildDir = layout.buildDirectory.asFile
val patchCfgAppName = providers.gradleProperty("mimir.appName")
tasks.configureEach {
    val isDistributable = name.startsWith("createDistributable") || name.startsWith("createReleaseDistributable")
    val isMsi = name.contains("packageMsi", ignoreCase = true) || name.contains("packageReleaseMsi", ignoreCase = true)
    if (isDistributable || isMsi) {
        val buildDirFile = patchCfgBuildDir.get()
        val appName = patchCfgAppName.get()
        val patch = Action<Task> {
            if (buildDirFile.exists()) {
                buildDirFile.walkTopDown()
                    .filter { it.isFile && it.name.equals("$appName.cfg", ignoreCase = true) }
                    .forEach { cfg ->
                        val content = cfg.readText()
                        if (!content.contains("win.norestart")) {
                            cfg.writeText(content.replace("[Application]", "[Application]\r\nwin.norestart=true"))
                        }
                    }
            }
        }
        if (isDistributable) doLast(patch) else doFirst(patch)
    }
}

fun macBundleVersion(version: String): String {
    val parts = version.substringBefore('-').split('.')
    return if (parts.first() == "0") (listOf("1") + parts.drop(1)).joinToString(".") else version
}
