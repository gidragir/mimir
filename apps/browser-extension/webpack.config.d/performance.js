// Бюджет размера вместо стандартных 244 KiB для сайтов: расширение загружается с диска, сейчас popup.js ≈ 1 MiB.
// При его превышении production-сборка выдаст предупреждение; тестовые бандлы (Karma) не ограничиваются.
config.performance = config.mode === "production"
    ? { hints: "warning", maxAssetSize: 1536 * 1024, maxEntrypointSize: 1536 * 1024 }
    : { hints: false };
