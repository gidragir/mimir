package dev.mimir.daemon

import dev.mimir.core.BuildConfig
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Serializable
data class DaemonConfig(
    val bind: String = "127.0.0.1",
    val port: Int = BuildConfig.API_DEFAULT_PORT,
    val vaultPath: String? = null,
    val token: String? = null,
    val password: String? = null,
    val passwordFile: String? = null,
    val googleClientId: String? = null,
    val googleClientSecret: String? = null,
    val syncIntervalSeconds: Long = 30L,
    val rootFolder: String = "MimirVault",
    val configDir: String? = null,
) {
    /** Пароль шифрования: `password`, иначе содержимое `passwordFile` (без пробелов по краям). */
    fun resolvePassword(): String? {
        if (password != null) return password
        val file = passwordFile ?: return null
        if (isPasswordFileInsecure()) {
            System.err.println("Warning: password file $file is accessible by group or others; run chmod 600")
        }
        return Files.readString(Paths.get(file)).trim()
    }

    /** `true`, если на POSIX-системе `passwordFile` доступен группе или остальным. */
    fun isPasswordFileInsecure(): Boolean {
        val file = Paths.get(passwordFile ?: return false)
        if (!file.fileSystem.supportedFileAttributeViews().contains("posix") || !Files.exists(file)) return false
        return Files.getPosixFilePermissions(file).any { it.name.startsWith("GROUP") || it.name.startsWith("OTHERS") }
    }

    /** Атомарно пишет `config.json` в [configDir] (по умолчанию — свой `configDir`) с правами 0600 на POSIX. */
    fun save(configDir: String? = null) {
        val dir = configDir ?: this.configDir ?: throw IllegalArgumentException("configDir is not set")
        writeFileAtomically(Paths.get(dir).resolve(CONFIG_FILE), prettyJson.encodeToString(this), ownerOnly = true)
    }

    // Секреты не должны попадать в логи.
    override fun toString(): String =
        "DaemonConfig(bind=$bind, port=$port, vaultPath=$vaultPath, token=${redact(token)}, password=${redact(password)}, " +
            "passwordFile=$passwordFile, googleClientId=$googleClientId, googleClientSecret=${redact(googleClientSecret)}, " +
            "syncIntervalSeconds=$syncIntervalSeconds, rootFolder=$rootFolder, configDir=$configDir)"

    companion object {
        private const val CONFIG_FILE = "config.json"
        private val json = Json { ignoreUnknownKeys = true }
        private val prettyJson = Json { prettyPrint = true }

        private fun redact(secret: String?) = if (secret == null) "null" else "***"

        /** Приоритет: переменные окружения → `config.json` в [configDir] → значения по умолчанию. */
        fun load(configDir: String? = null, env: Map<String, String> = System.getenv()): DaemonConfig {
            val dir = configDir ?: env["MIMIR_CONFIG_DIR"] ?: defaultConfigDir(env).toString()
            return readFile(dir).applyEnv(env).copy(configDir = dir)
        }

        /** Только содержимое `config.json` без переменных окружения: то, что безопасно записывать обратно. */
        fun readFile(configDir: String): DaemonConfig {
            val file = Paths.get(configDir).resolve(CONFIG_FILE)
            if (!Files.isRegularFile(file)) return DaemonConfig()
            return try {
                json.decodeFromString<DaemonConfig>(Files.readString(file))
            } catch (e: SerializationException) {
                throw IllegalArgumentException("Cannot parse $file: ${e.message}", e)
            }
        }

        private fun defaultConfigDir(env: Map<String, String>): Path {
            val windowsAppData = env["APPDATA"]
            return when {
                System.getProperty("os.name").orEmpty().startsWith("Windows") && windowsAppData != null ->
                    Paths.get(windowsAppData, "Mimir")
                env["XDG_CONFIG_HOME"] != null -> Paths.get(env.getValue("XDG_CONFIG_HOME"), "mimir")
                else -> Paths.get(System.getProperty("user.home"), ".config", "mimir")
            }
        }
    }
}

fun DaemonConfig.Companion.fromEnv(env: Map<String, String>): DaemonConfig = DaemonConfig().applyEnv(env)

private fun DaemonConfig.applyEnv(env: Map<String, String>): DaemonConfig {
    val port = env["MIMIR_PORT"]?.let {
        it.toIntOrNull()?.takeIf { port -> port in 0..65535 }
            ?: throw IllegalArgumentException("MIMIR_PORT must be a port number, got '$it'")
    } ?: port
    val interval = env["MIMIR_SYNC_INTERVAL"]?.let {
        it.toLongOrNull()?.takeIf { seconds -> seconds > 0 }
            ?: throw IllegalArgumentException("MIMIR_SYNC_INTERVAL must be a positive number of seconds, got '$it'")
    } ?: syncIntervalSeconds
    return copy(
        bind = env["MIMIR_BIND"] ?: bind,
        port = port,
        vaultPath = env["MIMIR_VAULT"] ?: vaultPath,
        token = env["MIMIR_TOKEN"] ?: token,
        password = env["MIMIR_PASSWORD"] ?: password,
        passwordFile = env["MIMIR_PASSWORD_FILE"] ?: passwordFile,
        googleClientId = env["MIMIR_GOOGLE_CLIENT_ID"] ?: googleClientId,
        googleClientSecret = env["MIMIR_GOOGLE_CLIENT_SECRET"] ?: googleClientSecret,
        syncIntervalSeconds = interval,
        rootFolder = env["MIMIR_ROOT_FOLDER"] ?: rootFolder,
    )
}
