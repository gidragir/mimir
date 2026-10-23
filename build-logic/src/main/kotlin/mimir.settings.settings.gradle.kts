import dev.mimir.build.AndroidSdk
import org.gradle.api.logging.Logging

// Android-модуль подключается только при наличии SDK; иначе сборка идёт без него.
if (AndroidSdk.isAvailable(rootDir)) {
    if (File(rootDir, "app-android").isDirectory) include(":app-android")
} else {
    Logging.getLogger("mimir").warn("Android SDK not found: skipping Android targets")
}
