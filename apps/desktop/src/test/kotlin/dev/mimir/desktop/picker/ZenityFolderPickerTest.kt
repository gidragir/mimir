package dev.mimir.desktop.picker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZenityFolderPickerTest {
    private class Script(vararg val replies: Pair<String, () -> ProcessResult>) : ProcessExecutor {
        val calls = mutableListOf<List<String>>()
        override suspend fun execute(command: List<String>, env: Map<String, String>): ProcessResult {
            calls += command
            return replies.first { it.first == command.first() }.second()
        }
    }

    @Test
    fun `zenity selection is returned and kdialog is not called`() = runTest {
        val exec = Script("zenity" to { ProcessResult(0, "/home/user/Vault\n") })
        assertEquals(PickOutcome.Selected("/home/user/Vault"), ZenityFolderPicker(exec).tryPick(null))
        assertEquals(1, exec.calls.size)
    }

    @Test
    fun `zenity cancel stops the chain`() = runTest {
        val exec = Script("zenity" to { ProcessResult(1, "") }, "kdialog" to { error("must not be called") })
        assertEquals(PickOutcome.Cancelled, ZenityFolderPicker(exec).tryPick(null))
        assertEquals(1, exec.calls.size)
    }

    @Test
    fun `missing or failing zenity falls through to kdialog`() = runTest {
        val missing = Script("zenity" to { throw IOException("nope") }, "kdialog" to { ProcessResult(0, "/k/vault\n") })
        assertEquals(PickOutcome.Selected("/k/vault"), ZenityFolderPicker(missing).tryPick(null))
        val failing = Script("zenity" to { ProcessResult(255, "") }, "kdialog" to { ProcessResult(0, "/k/vault\n") })
        assertEquals(PickOutcome.Selected("/k/vault"), ZenityFolderPicker(failing).tryPick(null))
    }

    @Test
    fun `kdialog cancel returns Cancelled`() = runTest {
        val exec = Script("zenity" to { throw IOException() }, "kdialog" to { ProcessResult(1, "") })
        assertEquals(PickOutcome.Cancelled, ZenityFolderPicker(exec).tryPick(null))
    }

    @Test
    fun `both utilities unavailable`() = runTest {
        val exec = Script("zenity" to { throw IOException() }, "kdialog" to { ProcessResult(255, "") })
        assertIs<PickOutcome.Unavailable>(ZenityFolderPicker(exec).tryPick(null))
    }

    @Test
    fun `filename flag only for existing directory`() = runTest {
        val none = Script("zenity" to { ProcessResult(1, "") })
        ZenityFolderPicker(none).tryPick(null)
        assertFalse(none.calls.single().any { it.startsWith("--filename") })
        val missing = Script("zenity" to { ProcessResult(1, "") })
        ZenityFolderPicker(missing).tryPick("/no/such/dir/${System.nanoTime()}/x")
        assertFalse(missing.calls.single().any { it.startsWith("--filename") })

        val tmp = File(System.getProperty("java.io.tmpdir"))
        val existing = Script("zenity" to { ProcessResult(1, "") })
        ZenityFolderPicker(existing).tryPick(tmp.absolutePath)
        assertTrue("--filename=${tmp.absolutePath.trimEnd('/')}/" in existing.calls.single())
    }

    @Test
    fun `cancellation is rethrown`() = runTest {
        val exec = Script("zenity" to { throw CancellationException("x") })
        assertFailsWith<CancellationException> { ZenityFolderPicker(exec).tryPick(null) }
    }
}
