package dev.mimir.desktop.picker

import dev.mimir.desktop.PlatformFocus
import kotlinx.coroutines.CancellationException
import java.util.Base64

private val POWERSHELL_SCRIPT = """
Add-Type -AssemblyName System.Windows.Forms
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class MimirFolderDialog {
    [ComImport, Guid("DC1C5A9C-E88A-4dde-A5A1-60F82A20AEF7")]
    private class FileOpenDialog { }

    [ComImport, Guid("42f85136-db7e-439c-85f1-e4075d135fc8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IFileDialog {
        [PreserveSig] int Show(IntPtr owner);
        void SetFileTypes(uint count, IntPtr types);
        void SetFileTypeIndex(uint index);
        void GetFileTypeIndex(out uint index);
        void Advise(IntPtr events, out uint cookie);
        void Unadvise(uint cookie);
        void SetOptions(uint options);
        void GetOptions(out uint options);
        void SetDefaultFolder(IShellItem item);
        void SetFolder(IShellItem item);
        void GetFolder(out IShellItem item);
        void GetCurrentSelection(out IShellItem item);
        void SetFileName([MarshalAs(UnmanagedType.LPWStr)] string name);
        void GetFileName([MarshalAs(UnmanagedType.LPWStr)] out string name);
        void SetTitle([MarshalAs(UnmanagedType.LPWStr)] string title);
        void SetOkButtonLabel([MarshalAs(UnmanagedType.LPWStr)] string text);
        void SetFileNameLabel([MarshalAs(UnmanagedType.LPWStr)] string text);
        void GetResult(out IShellItem item);
    }

    [ComImport, Guid("43826D1E-E718-42EE-BC55-A1E261C37BFE"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IShellItem {
        void BindToHandler(IntPtr bc, ref Guid bhid, ref Guid riid, out IntPtr ppv);
        void GetParent(out IShellItem item);
        void GetDisplayName(uint sigdn, [MarshalAs(UnmanagedType.LPWStr)] out string name);
        void GetAttributes(uint mask, out uint attributes);
        void Compare(IShellItem other, uint hint, out int order);
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = false)]
    private static extern void SHCreateItemFromParsingName(string path, IntPtr bc, ref Guid riid, out IShellItem item);

    private const uint FOS_PICKFOLDERS = 0x20;
    private const uint FOS_FORCEFILESYSTEM = 0x40;
    private const uint FOS_PATHMUSTEXIST = 0x800;
    private const uint SIGDN_FILESYSPATH = 0x80058000;
    private const int ERROR_CANCELLED = unchecked((int)0x800704C7);

    public static string Pick(IntPtr owner, string title, string initial) {
        IFileDialog dialog = (IFileDialog)new FileOpenDialog();
        uint options;
        dialog.GetOptions(out options);
        dialog.SetOptions(options | FOS_PICKFOLDERS | FOS_FORCEFILESYSTEM | FOS_PATHMUSTEXIST);
        dialog.SetTitle(title);
        if (!string.IsNullOrEmpty(initial)) {
            Guid iid = new Guid("43826D1E-E718-42EE-BC55-A1E261C37BFE");
            IShellItem folder;
            SHCreateItemFromParsingName(initial, IntPtr.Zero, ref iid, out folder);
            dialog.SetFolder(folder);
        }
        int hr = dialog.Show(owner);
        if (hr == ERROR_CANCELLED) return null;
        if (hr != 0) Marshal.ThrowExceptionForHR(hr);
        IShellItem result;
        dialog.GetResult(out result);
        string path;
        result.GetDisplayName(SIGDN_FILESYSPATH, out path);
        return path;
    }
}
'@
${'$'}init = ${'$'}env:MIMIR_INITIAL_PATH
if ([string]::IsNullOrWhiteSpace(${'$'}init) -or !(Test-Path -LiteralPath ${'$'}init -PathType Container)) { ${'$'}init = ${'$'}null }
${'$'}top = New-Object System.Windows.Forms.Form
${'$'}top.TopMost = ${'$'}true
${'$'}top.ShowInTaskbar = ${'$'}false
${'$'}null = ${'$'}top.Handle
try {
    ${'$'}picked = [MimirFolderDialog]::Pick(${'$'}top.Handle, '$FOLDER_PICKER_TITLE', ${'$'}init)
    if (${'$'}picked) { [Console]::Out.Write(${'$'}picked) }
} finally {
    ${'$'}top.Dispose()
}
""".trimIndent()

/**
 * Windows: Common Item Dialog (`IFileOpenDialog` + `FOS_PICKFOLDERS`, современный диалог проводника) через `powershell.exe -Sta` и COM-интероп в `Add-Type`; `FolderBrowserDialog` не используется: в Windows PowerShell 5.1 (.NET Framework) он всегда рисует старое дерево `SHBrowseForFolder`. Скрипт идёт через `-EncodedCommand` (UTF-16LE Base64),
 * начальный путь — через переменную окружения, чтобы кириллица и кавычки не ломали разбор.
 */
class WindowsFolderPicker(
    private val executor: ProcessExecutor = ProcessExecutor(::runSystemProcess),
    private val allowForeground: () -> Unit = PlatformFocus::allowForegroundTransfer,
) : FolderPickerCandidate {
    override suspend fun tryPick(initialPath: String?): PickOutcome {
        val encoded = Base64.getEncoder().encodeToString(POWERSHELL_SCRIPT.toByteArray(Charsets.UTF_16LE))
        val command = listOf(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-Sta", "-WindowStyle", "Hidden", "-EncodedCommand", encoded,
        )
        val env = resolveExistingDirectory(initialPath)?.let { mapOf("MIMIR_INITIAL_PATH" to it.absolutePath) }.orEmpty()
        return try {
            runCatching { allowForeground() }
            val result = executor.execute(command, env)
            val picked = result.stdout.trim()
            when {
                result.exitCode != 0 -> PickOutcome.Unavailable("powershell exited with ${result.exitCode}")
                picked.isEmpty() -> PickOutcome.Cancelled
                else -> PickOutcome.Selected(picked)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            PickOutcome.Unavailable("powershell failed", e)
        }
    }
}
