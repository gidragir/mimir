package dev.mimir.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.TimeUnit

/**
 * Возвращает фокус окну приложения средствами композитора: Wayland не даёт клиенту забрать фокус самому
 * (AWT `toFront` под niri ничего не делает). Сейчас поддержан niri.
 */
class CompositorFocus(
    private val env: Map<String, String> = System.getenv(),
    private val run: (List<String>) -> String = ::runCommand,
    private val pid: Long = ProcessHandle.current().pid(),
    private val title: String,
) {
    /** `true`, если композитор принял команду. */
    fun focusWindow(): Boolean {
        if (env["NIRI_SOCKET"].isNullOrEmpty()) return false
        return try {
            val id = niriWindowId(run(listOf("niri", "msg", "--json", "windows")), pid, title) ?: return false
            run(listOf("niri", "msg", "action", "focus-window", "--id", id.toString()))
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        /** Окно с нашим pid; если его нет в списке (XWayland) — окно с таким заголовком. */
        fun niriWindowId(windowsJson: String, pid: Long, title: String): Long? {
            val windows = runCatching { Json.parseToJsonElement(windowsJson) as? JsonArray }.getOrNull() ?: return null
            val objects = windows.map { it.jsonObject }
            fun idOf(match: kotlinx.serialization.json.JsonObject) = match["id"]?.jsonPrimitive?.longOrNull
            objects.firstOrNull { it["pid"]?.jsonPrimitive?.longOrNull == pid }?.let { return idOf(it) }
            return objects.firstOrNull { it["title"]?.jsonPrimitive?.content == title }?.let(::idOf)
        }
    }
}

private fun runCommand(command: List<String>): String {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) { "${command.first()} failed" }
    return output
}
