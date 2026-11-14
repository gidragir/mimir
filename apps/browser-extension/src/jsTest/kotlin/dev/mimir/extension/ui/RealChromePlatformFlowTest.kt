package dev.mimir.extension.ui

import dev.mimir.core.Account
import dev.mimir.core.CanaryVerifier
import dev.mimir.core.InMemoryRemoteStorage
import dev.mimir.core.RcloneCipher
import dev.mimir.core.RemotePath
import dev.mimir.core.testing.TestCiphers
import dev.mimir.extension.platform.ChromeExtensionPlatform
import dev.mimir.extension.vault.AccountCodec
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.writeString
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Тот же сценарий, но через боевой [ChromeExtensionPlatform] поверх асинхронной заглушки `chrome.*`. */
class RealChromePlatformFlowTest {
    private fun installChromeStub() {
        js(
            """
            (function () {
              var listeners = [];
              function fire(area, changes) {
                setTimeout(function () { listeners.slice().forEach(function (l) { l(changes, area); }); }, 0);
              }
              function makeArea(name) {
                var data = {};
                return {
                  get: function (k) { var r = {}; if (k in data) r[k] = data[k]; return new Promise(function (ok) { setTimeout(function () { ok(r); }, 0); }); },
                  set: function (o) { var ch = {}; Object.keys(o).forEach(function (k) { ch[k] = { oldValue: data[k], newValue: o[k] }; data[k] = o[k]; }); fire(name, ch); return new Promise(function (ok) { setTimeout(ok, 0); }); },
                  remove: function (k) { var ch = {}; if (k in data) { ch[k] = { oldValue: data[k] }; delete data[k]; fire(name, ch); } return new Promise(function (ok) { setTimeout(ok, 0); }); },
                  clear: function () { var ch = {}; Object.keys(data).forEach(function (k) { ch[k] = { oldValue: data[k] }; }); data = {}; fire(name, ch); return new Promise(function (ok) { setTimeout(ok, 0); }); }
                };
              }
              globalThis.chrome = {
                storage: { session: makeArea("session"), local: makeArea("local"), onChanged: { addListener: function (l) { listeners.push(l); } } },
                runtime: { getURL: function (p) { return p; } },
                tabs: { create: function () { return Promise.resolve({ id: 1 }); } },
                identity: {}
              };
            })()
            """,
        )
    }

    private suspend fun pause(ms: Int = 20) {
        Promise<Unit> { resolve, _ -> window.setTimeout({ resolve(Unit) }, ms) }.await()
    }

    private suspend fun waitFor(
        timeoutMs: Int = 10000,
        intervalMs: Int = 20,
        description: String = "condition",
        predicate: () -> Boolean,
    ) {
        val start = window.performance.now()
        while (!predicate()) {
            if (window.performance.now() - start > timeoutMs) {
                error("Timeout waiting for $description after ${timeoutMs}ms")
            }
            pause(intervalMs)
        }
    }

    @Test
    fun backAndToolbarButtonsWorkThroughRealPlatformAdapter() = runTest {
        installChromeStub()
        val cipher = TestCiphers.forPassword("pw")
        val remote = InMemoryRemoteStorage()
        remote.write(RemotePath(CanaryVerifier.CANARY_FILENAME), cipher.encrypt(Buffer().apply { writeString("""{"magic":"mimir-vault-canary","version":1}""") }))
        remote.write(RemotePath.of(listOf("A.md").map(cipher::encryptName)), cipher.encrypt(Buffer().apply { writeString("# A\ntext") }))
        val platform = ChromeExtensionPlatform()
        platform.setLocalString("auth.account", AccountCodec.encode(Account("u@e.c", null)))
        val controller = ExtensionController(platform, { remote }, FakeExtensionAuthHandler(platform), cipherFactory = TestCiphers::forPassword)
        val container = document.createElement("div") as HTMLElement
        document.body!!.appendChild(container)
        val view = PopupDomView(container, controller, platform, isFullTabMode = false, scope = MainScope())
        val job = controller.state.onEach { view.render(it) }.launchIn(MainScope())

        controller.initialize()
        waitFor(description = "password input") { container.querySelector("input[type='password']") != null }
        (container.querySelector("input[type='password']") as HTMLInputElement).value = "pw"
        (container.querySelector("[data-testid='unlock-btn']") as HTMLButtonElement).click()
        waitFor(description = "vault list") { container.querySelector("[data-testid='vault-tree-pane']") != null }

        (container.querySelector("button.tree-row.file") as HTMLButtonElement).click()
        waitFor(description = "note reader") { container.querySelector("[data-testid='note-reader-pane']") != null }

        (container.querySelector("[data-testid='back-to-list-btn']") as HTMLButtonElement).click()
        waitFor(description = "vault tree after back") {
            container.querySelector("[data-testid='vault-tree-pane']") != null &&
                container.querySelector("[data-testid='note-reader-pane']") == null
        }

        (container.querySelector("[data-testid='lock-btn']") as HTMLButtonElement).click()
        waitFor(description = "unlock screen after lock") { container.querySelector("[data-testid='unlock-screen']") != null }
        job.cancel()
        container.remove()
    }
}
