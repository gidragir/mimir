// Obsidian сам предоставляет модуль obsidian; больше плагин ничего не требует (работает и на телефоне)
if (config.mode === "production") {
    config.externals = { obsidian: "commonjs obsidian" };
    // commonjs2-библиотека нужна только релизному бандлу; Karma-бандл тестов её не поддерживает.
    config.output = config.output || {};
    config.output.library = { type: "commonjs2" };
} else {
    // Тесты (Karma) в браузере: модуля obsidian нет, подставляем пустую заглушку.
    config.externals = {
        obsidian: "var ({ Plugin: class Plugin { addStatusBarItem() { return { textContent: '' }; } addCommand() {} }, Notice: class Notice {}, Modal: class Modal { constructor() { this.contentEl = { empty() {}, createEl() { return {}; }, createDiv() { return {}; } }; } open() {} close() {} }, Setting: class Setting { constructor() { this.setName = () => this; this.setDesc = () => this; this.setClass = () => this; this.addText = () => this; this.addDropdown = () => this; this.addToggle = () => this; this.addButton = () => this; } }, PluginSettingTab: class PluginSettingTab { constructor() { this.containerEl = { empty() {}, createEl() { return {}; }, createDiv() { return {}; } }; } display() {} hide() {} } })"
    };
}
config.target = "web";
