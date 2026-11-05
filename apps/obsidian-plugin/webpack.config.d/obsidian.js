// Obsidian сам предоставляет модуль obsidian; больше плагин ничего не требует (работает и на телефоне)
if (config.mode === "production") {
    config.externals = { obsidian: "commonjs obsidian" };
    // commonjs2-библиотека нужна только релизному бандлу; Karma-бандл тестов её не поддерживает.
    config.output = config.output || {};
    config.output.library = { type: "commonjs2" };
} else {
    // Тесты (Karma) в браузере: модуля obsidian нет, подставляем пустую заглушку с базовым классом Plugin.
    config.externals = { obsidian: "var ({ Plugin: class Plugin {}, Notice: class Notice {} })" };
}
config.target = "web";
