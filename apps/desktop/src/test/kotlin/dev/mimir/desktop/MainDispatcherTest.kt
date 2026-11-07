package dev.mimir.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Main.kt переключается на Dispatchers.Main из обработчиков трея и IPC-активации. Без kotlinx-coroutines-swing
 * в runtime-классовом пути его нет, и одиночный клик по иконке трея ронял приложение
 * (IllegalStateException: Module with the Main dispatcher is missing).
 */
class MainDispatcherTest {
    @Test
    fun mainDispatcherIsTheAwtEventDispatchThread() = runBlocking {
        val onEdt = withContext(Dispatchers.Main) { EventQueue.isDispatchThread() }
        assertTrue(onEdt, "Dispatchers.Main must run on the AWT event dispatch thread")
    }
}
