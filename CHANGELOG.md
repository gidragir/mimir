# Changelog

## [0.5.0](https://github.com/gidragir/mimir/compare/v0.4.0...v0.5.0) (2026-11-07)


### Features

* **desktop:** add PlatformWindowStyler for native Win32 DWM and macOS frame styling ([344c1d0](https://github.com/gidragir/mimir/commit/344c1d02f482ae9606a53d08a4545db9c50063df))
* **desktop:** add PopoverVisibilityController and ScreenPositionCalculator ([a3e8846](https://github.com/gidragir/mimir/commit/a3e884642fb374eeb749c4710cd4ea03c36de21b))
* **desktop:** integrate TrayPopoverWindow and PlatformWindowStyler ([43dc91c](https://github.com/gidragir/mimir/commit/43dc91c155cd48a191aa6c35df0ea5b627f2b8ae))
* **tools:** add logs, snapshot and reset commands for Windows VM ([49f568e](https://github.com/gidragir/mimir/commit/49f568ef58155e60b879283251e3f738e172f2cb))
* **tools:** add win-vm host CLI skeleton with guest-agent helpers ([441d76f](https://github.com/gidragir/mimir/commit/441d76f888095d79aedac70c3cc4bcedeb05eddf))
* **tools:** build, install and uninstall MSI in Windows VM ([e90e197](https://github.com/gidragir/mimir/commit/e90e197f6516c128d7d3f39d37afd0e699fd509e))
* **tools:** provision Windows VM with mise, JDK and MimirDev task ([a24fabc](https://github.com/gidragir/mimir/commit/a24fabc0399c62fb978cb384a45ae0efff182e0f))
* **tools:** run desktop app in Windows VM user session via MimirDev task ([402194f](https://github.com/gidragir/mimir/commit/402194f31efbe1a4621e12738df7cec50fe13fbb))
* **tools:** sync working tree into Windows VM via virtiofs and robocopy ([a8753b5](https://github.com/gidragir/mimir/commit/a8753b5c4b8ec444ce0c0a803ba654a943888587))
* **ui:** add TrayPopoverScreen and popover vector icons ([dade965](https://github.com/gidragir/mimir/commit/dade96501e11bb6d0b6b59deed30ce9a56f01875))


### Bug Fixes

* **android:** prevent background network crash, false offline status, and double sync scan ([440260d](https://github.com/gidragir/mimir/commit/440260d0a96aecdd49e3f1632ecabd0339de7ada))
* **ci:** eliminate redundant release CI runs and resolve Android test race ([5623461](https://github.com/gidragir/mimir/commit/56234612ee5e3408d26f21d20052e51f45cb1cc8))
* **desktop:** build the MSI from the patched app image so win.norestart reaches the installed launcher ([7a2fc9d](https://github.com/gidragir/mimir/commit/7a2fc9d37728cdd29986aa71d83fe1322eedb970))
* **desktop:** open the main window from the tray on niri where the popover cannot be shown ([46702c6](https://github.com/gidragir/mimir/commit/46702c6d173bc1132f1ad008b87f96763ec15685))
* **desktop:** show the tray popover on niri as a floating window and dismiss it on Esc release ([e8af3ff](https://github.com/gidragir/mimir/commit/e8af3ff6bb5870280c20448e3f05c49772ec36c6))
* **desktop:** size the tray popover to its content and title it with the app name ([84abf0e](https://github.com/gidragir/mimir/commit/84abf0ebacddf68f2407df0cc0dd0f1794986cac))
* **desktop:** stop tray click crash, close popover on Esc and apply POPUP window type ([31feeaa](https://github.com/gidragir/mimir/commit/31feeaa277fd1bb767f16bf53ef9ecc2926cbd71))
* **desktop:** use IFileOpenDialog folder picker on Windows instead of legacy FolderBrowserDialog ([778dafa](https://github.com/gidragir/mimir/commit/778dafadbd28eb5151552b23f243f5872af1f62b))
* **tools:** anchor guest robocopy build exclusions to output dirs ([6b6e4c5](https://github.com/gidragir/mimir/commit/6b6e4c570a37850eb2da911e4db94c5e4a57e19d))
* **tools:** detect desktop launch by app process in guest agent ([68f0147](https://github.com/gidragir/mimir/commit/68f01473d26d0a1956c018ab5f4b7728be6c4fbc))
* **tools:** enumerate build output dirs for guest robocopy exclusion ([975841e](https://github.com/gidragir/mimir/commit/975841ef0efe0fcb5fe8f8dd0d91acf390560399))
* **tools:** find installed MSI in HKLM uninstall keys ([8d6b54b](https://github.com/gidragir/mimir/commit/8d6b54b4ab4e6baa12ba23870109b88cdfcd6743))
* **tools:** make win:run stop the previous instance, rotate logs and sync the caller's tree ([3273a36](https://github.com/gidragir/mimir/commit/3273a36010d0041a29bcb63293ff7bcb68b040e6))
* **tools:** reset win:* build logs tolerating a handle held by an exiting Gradle daemon ([2885760](https://github.com/gidragir/mimir/commit/2885760534cdd782cd404a35782e8aa7ba8898b0))
* **tools:** stop win-vm sync excluding nested source dirs named build ([b7e71d9](https://github.com/gidragir/mimir/commit/b7e71d93e92468942786249c992a994635c749bd))
* **tools:** stream the app output of win:run to run.app.log past the Gradle client ([115cb31](https://github.com/gidragir/mimir/commit/115cb31896efc4f3ba5941b658f8e3f40163ca75))
* **tools:** sync win-vm tree via git file list to honor global ignore ([14231d4](https://github.com/gidragir/mimir/commit/14231d4ba0bad9299fa0ea0a3d642c1bc1765076))
* **tools:** wait for guest build via Start-Process instead of call operator ([3e4a764](https://github.com/gidragir/mimir/commit/3e4a764bcb1e110386faa222c70b2a59bf53ffa5))
* **ui:** lock the vault step while the external folder dialog is open ([adea6d2](https://github.com/gidragir/mimir/commit/adea6d208fc2795f124e22cd5b98f05582a6cd5c))
* **ui:** show a single checkmark in the vault step note count ([d35659f](https://github.com/gidragir/mimir/commit/d35659fb6e8ff446a1288e96338da4d7126879cc))


### Documentation

* add native folder picker spec update and implementation plan ([b7f149e](https://github.com/gidragir/mimir/commit/b7f149e45b487365159fd67768105c5d5feaef9f))
* add Windows VM manual test flow design ([e965064](https://github.com/gidragir/mimir/commit/e965064f61eef6a424169bdfa72db2fa71d8e0aa))
* add Windows VM test flow implementation plan ([4250eda](https://github.com/gidragir/mimir/commit/4250eda4f227a02982634998a98c682cbf818637))
* document Windows VM test flow in AGENTS.md and build-verify skill ([4eaed8b](https://github.com/gidragir/mimir/commit/4eaed8b73c076fea19cb175a9600aa884105f5f0))
* **skills:** record niri popover, external dialog and win-vm diagnostics lessons ([570bdf8](https://github.com/gidragir/mimir/commit/570bdf8b131c69da889f78491ee6adaf106b3095))
* **skills:** update build-verify, cross-platform and ui-test skills ([cac1108](https://github.com/gidragir/mimir/commit/cac1108a6fe7423a5254dd5c6ec22b7131ca5a22))

## [0.4.0](https://github.com/gidragir/mimir/compare/v0.3.2...v0.4.0) (2026-11-06)


### Features

* **desktop:** add DesktopObsidianVaultDiscovery and bind parent window to folder picker ([f257ad8](https://github.com/gidragir/mimir/commit/f257ad8e5833023549c25d73ac019e725362bc88))
* **desktop:** add Windows, macOS and Zenity/KDialog native folder pickers ([949943e](https://github.com/gidragir/mimir/commit/949943e117eaf096fb2b1804caa834ec6d57f882))
* **ui:** add FakeFolderPicker, single-flight browse lock and Windows path display name ([f8e411b](https://github.com/gidragir/mimir/commit/f8e411b18f6e3bb8a0ac60c5861b44e1cc3532fc))


### Miscellaneous Chores

* release 0.4.0 ([e5154c8](https://github.com/gidragir/mimir/commit/e5154c8912bb2d3f5b4814cb80045540925b4c3a))


### Code Refactoring

* **desktop:** introduce PickOutcome, FallbackFolderPicker and ProcessExecutor ([6c1e945](https://github.com/gidragir/mimir/commit/6c1e9453419c8fe689dc37a8d88154797f7cf927))

## [0.3.2](https://github.com/gidragir/mimir/compare/v0.3.1...v0.3.2) (2026-11-06)


### Bug Fixes

* **drive:** configure HttpTimeout on DriveHttpClient to prevent CIO 15s request timeout ([bca5a71](https://github.com/gidragir/mimir/commit/bca5a71aa25e84ea197c3bf1e2540a4de155de19))
* **update:** stop CIO 15s timeout killing installer downloads; add error diagnostics ([#7](https://github.com/gidragir/mimir/issues/7)) ([1ae4a78](https://github.com/gidragir/mimir/commit/1ae4a785cafa17d7287e6c2b3cea3b8a7a947e02))

## [0.3.1](https://github.com/gidragir/mimir/compare/v0.3.0...v0.3.1) (2026-11-06)


### Features

* add Mimir logo across platforms ([0ccc260](https://github.com/gidragir/mimir/commit/0ccc2604f8e34ee04766cf29b297612f08ac97a0))


### Bug Fixes

* **android:** compile launcher foreground gradient stroke ([472f54b](https://github.com/gidragir/mimir/commit/472f54b76ee90c0841d387cf320effabbf0b956c))

## [0.3.0](https://github.com/gidragir/mimir/compare/v0.2.0...v0.3.0) (2026-11-05)


### Features

* **desktop:** ship macOS dmg in releases ([e8391b2](https://github.com/gidragir/mimir/commit/e8391b2a7e7b97b76dfc536bdf156cc3012a58e5))


### Bug Fixes

* **android:** sign release APK when keystore env is set ([968587d](https://github.com/gidragir/mimir/commit/968587d0996b5ddd23950af7fcfe7d1568c6ee16))
* **desktop:** make win.norestart cfg patch configuration-cache safe ([5a3289b](https://github.com/gidragir/mimir/commit/5a3289be27f0c06d30590365906f49e777b78c42))
* **desktop:** register mimir:// URI scheme on Windows and improve foreground focus ([856b7e5](https://github.com/gidragir/mimir/commit/856b7e5898276f8dc420703215f249fd8793976f))
* **runtime:** add manual return hint to browser login success page ([9f5e7ab](https://github.com/gidragir/mimir/commit/9f5e7abcf766c1e3c82e14fef947efdffdf717a9))


### Miscellaneous Chores

* release 0.3.0 ([c4d15d5](https://github.com/gidragir/mimir/commit/c4d15d5133f882d1b744714b839e702178c89e3c))


### Code Refactoring

* **build:** reorganize modules into libs/ and apps/ directories ([2c38a70](https://github.com/gidragir/mimir/commit/2c38a70aeac1e215e1e6bead26fb4d5a34a8bf2e))
