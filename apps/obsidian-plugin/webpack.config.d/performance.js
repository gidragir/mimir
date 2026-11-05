// Бюджет размера вместо стандартных 244 KiB для сайтов: плагин загружается с диска, сейчас main.js ≈ 270 KiB.
// При его превышении production-сборка выдаст предупреждение; тестовые бандлы (Karma) не ограничиваются.
config.performance = config.mode === "production"
    ? { hints: "warning", maxAssetSize: 512 * 1024, maxEntrypointSize: 512 * 1024 }
    : { hints: false };
