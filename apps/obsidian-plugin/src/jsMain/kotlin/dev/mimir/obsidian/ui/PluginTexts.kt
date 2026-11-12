package dev.mimir.obsidian.ui

data class PluginTexts(
    val locale: String,
    val statusNotConnected: String,
    val statusOffline: String,
    val statusYieldsToDaemon: String,
    val statusPausedForDaemon: String,
    val statusNeedsSignIn: String,
    val statusError: String,
    val statusUpToDate: String,
    val abortedNothingDeleted: String,
    val relativeJustNow: String,
    val relativeMinAgo: (Long) -> String,
    val relativeHrAgo: (Long) -> String,
    val relativeDaysAgo: (Long) -> String,
    val statusSyncing: (Int, Int) -> String,
    val statusConflicts: (Int) -> String,
    // UI Settings
    val settingsTitle: String,
    val accountSection: String,
    val signInButton: String,
    val signOutButton: String,
    val signedInAs: (String) -> String,
    val vaultSection: String,
    val rootFolderLabel: String,
    val rootFolderDesc: String,
    val passwordLabel: String,
    val passwordDesc: String,
    val verifyPasswordButton: String,
    val verifyingPassword: String,
    val passwordValidFirstDevice: String,
    val passwordValidExisting: String,
    val syncSection: String,
    val parallelismLabel: String,
    val parallelismDesc: String,
    val syncNowButton: String,
    val forceSyncWithDaemonLabel: String,
    val forceSyncWithDaemonDesc: String,
    val showConflictsButton: String,
    // Modals
    val deviceCodeModalTitle: String,
    val deviceCodeInstructions: (String, String) -> String,
    val openVerificationUrlButton: String,
    val copyCodeButton: String,
    val codeCopiedNotice: String,
    val cancelSignInButton: String,
    val conflictsModalTitle: String,
    val conflictsDesc: String,
    val conflictsCount: (Int) -> String,
    val pleaseConfigureNotice: String,
) {
    companion object {
        fun detect(): PluginTexts {
            val isRu = try {
                val loc = js("typeof window !== 'undefined' && window.moment ? window.moment.locale() : 'en'").unsafeCast<String>()
                loc.startsWith("ru")
            } catch (_: Throwable) {
                false
            }
            return if (isRu) ru() else en()
        }

        fun forLocale(locale: String): PluginTexts =
            if (locale.startsWith("ru")) ru() else en()

        fun en(): PluginTexts = PluginTexts(
            locale = "en",
            statusNotConnected = "Mimir: not connected",
            statusOffline = "Mimir: offline",
            statusYieldsToDaemon = "Mimir: handled by Mimir Desktop",
            statusPausedForDaemon = "Mimir: paused — Mimir Desktop is running",
            statusNeedsSignIn = "Mimir: sign in required",
            statusError = "Mimir: error",
            statusUpToDate = "up to date",
            abortedNothingDeleted = "Drive is empty but the sync index is not — sync stopped, nothing was deleted",
            relativeJustNow = "just now",
            relativeMinAgo = { "$it min ago" },
            relativeHrAgo = { "$it hr ago" },
            relativeDaysAgo = { "$it d ago" },
            statusSyncing = { done, total -> "Mimir: syncing $done/$total" },
            statusConflicts = { "Mimir: $it conflicts" },
            settingsTitle = "Mimir Sync Settings",
            accountSection = "Google Account",
            signInButton = "Sign in to Google",
            signOutButton = "Sign out",
            signedInAs = { "Signed in as $it" },
            vaultSection = "Vault & Encryption",
            rootFolderLabel = "Google Drive folder",
            rootFolderDesc = "Folder name on Google Drive where your encrypted vault is stored",
            passwordLabel = "Master Password",
            passwordDesc = "Password used to encrypt notes with Rclone-compatible crypt",
            verifyPasswordButton = "Verify / Save Password",
            verifyingPassword = "Verifying password...",
            passwordValidFirstDevice = "Vault initialized (first device)",
            passwordValidExisting = "Password verified",
            syncSection = "Synchronization",
            parallelismLabel = "Parallelism",
            parallelismDesc = "Number of parallel file transfers (1 to 4)",
            syncNowButton = "Sync now",
            forceSyncWithDaemonLabel = "Force sync with desktop daemon",
            forceSyncWithDaemonDesc = "Sync directly even when local Mimir Desktop daemon is running",
            showConflictsButton = "View conflicts",
            deviceCodeModalTitle = "Sign in to Google",
            deviceCodeInstructions = { code, url ->
                "To sign in, open $url on your device and enter the code: $code"
            },
            openVerificationUrlButton = "Open Google Sign-In",
            copyCodeButton = "Copy Code",
            codeCopiedNotice = "Code copied to clipboard",
            cancelSignInButton = "Cancel",
            conflictsModalTitle = "Sync Conflicts",
            conflictsDesc = "The following files had simultaneous edits and were saved with conflict suffixes:",
            conflictsCount = { "$it conflicting files" },
            pleaseConfigureNotice = "Mimir: please configure sync",
        )

        fun ru(): PluginTexts = PluginTexts(
            locale = "ru",
            statusNotConnected = "Mimir: not connected",
            statusOffline = "Mimir: offline",
            statusYieldsToDaemon = "Mimir: handled by Mimir Desktop",
            statusPausedForDaemon = "Mimir: приостановлено — запущен Mimir Desktop",
            statusNeedsSignIn = "Mimir: требуется вход",
            statusError = "Mimir: ошибка",
            statusUpToDate = "синхронизировано",
            abortedNothingDeleted = "Drive пуст, а индекс нет — синхронизация остановлена, ничего не удалено",
            relativeJustNow = "только что",
            relativeMinAgo = { "$it мин назад" },
            relativeHrAgo = { "$it ч назад" },
            relativeDaysAgo = { "$it дн назад" },
            statusSyncing = { done, total -> "Mimir: синхронизация $done/$total" },
            statusConflicts = {
                val mod10 = it % 10
                val mod100 = it % 100
                val word = when {
                    mod100 in 11..19 -> "конфликтов"
                    mod10 == 1 -> "конфликт"
                    mod10 in 2..4 -> "конфликта"
                    else -> "конфликтов"
                }
                "Mimir: $it $word"
            },
            settingsTitle = "Настройки Mimir Sync",
            accountSection = "Аккаунт Google",
            signInButton = "Войти через Google",
            signOutButton = "Выйти из аккаунта",
            signedInAs = { "Вы вошли как $it" },
            vaultSection = "Хранилище и шифрование",
            rootFolderLabel = "Папка Google Drive",
            rootFolderDesc = "Имя папки на Google Drive, где сохраняется зашифрованное хранилище",
            passwordLabel = "Мастер-пароль",
            passwordDesc = "Пароль шифрования заметок (совместим с rclone crypt)",
            verifyPasswordButton = "Проверить / Сохранить пароль",
            verifyingPassword = "Проверка пароля...",
            passwordValidFirstDevice = "Хранилище инициализировано (первое устройство)",
            passwordValidExisting = "Пароль подтверждён",
            syncSection = "Синхронизация",
            parallelismLabel = "Параллельность",
            parallelismDesc = "Количество одновременных потоков передачи (1–4)",
            syncNowButton = "Синхронизировать сейчас",
            forceSyncWithDaemonLabel = "Принудительная синхронизация при запущенном десктопном демоне",
            forceSyncWithDaemonDesc = "Синхронизировать напрямую даже когда запущен локальный демон Mimir Desktop",
            showConflictsButton = "Список конфликтов",
            deviceCodeModalTitle = "Вход через Google",
            deviceCodeInstructions = { code, url ->
                "Для входа перейдите по адресу $url и введите код: $code"
            },
            openVerificationUrlButton = "Открыть страницу входа",
            copyCodeButton = "Скопировать код",
            codeCopiedNotice = "Код скопирован в буфер обмена",
            cancelSignInButton = "Отмена",
            conflictsModalTitle = "Конфликты синхронизации",
            conflictsDesc = "Следующие файлы были отредактированы одновременно и сохранены с суффиксом конфликта:",
            conflictsCount = { "$it конфликтов" },
            pleaseConfigureNotice = "Mimir: настройте синхронизацию",
        )
    }
}
