// Бюджет размера вместо стандартных 244 KiB для сайтов: бандл нужен только Compose UI tests в Wasm, самый большой файл — skiko.wasm ≈ 8.3 MiB.
// При его превышении production-сборка выдаст предупреждение; тестовые бандлы (Karma) не ограничиваются.
config.performance = config.mode === "production"
    ? { hints: "warning", maxAssetSize: 16 * 1024 * 1024, maxEntrypointSize: 16 * 1024 * 1024 }
    : { hints: false };
