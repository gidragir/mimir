// Obsidian сам предоставляет модуль obsidian; больше плагин ничего не требует (работает и на телефоне)
config.externals = { obsidian: "commonjs obsidian" };
config.output = config.output || {};
config.output.library = { type: "commonjs2" };
config.target = "web";
