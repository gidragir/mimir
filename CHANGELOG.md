# Changelog

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
