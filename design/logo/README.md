# Логотип Mimir

Исходники — SVG в этом каталоге; всё остальное получено из них растеризацией и правится только через исходник.

| Исходник | Назначение |
|---|---|
| `logo.svg` | основной знак, от 48 px и крупнее |
| `logo-small.svg` | 16–32 px: без паутины графа, утолщённые рёбра |
| `logo-mono.svg` | одноцветный силуэт (`currentColor`): уведомления Android, тематическая иконка |
| `social-preview.svg` | карточка GitHub 1280×640 (шрифты Inter и JetBrains Mono) |

| Файл | Из чего | Размеры |
|---|---|---|
| `apps/desktop/src/main/resources/icon.ico` (MSI, ярлыки) | small 16/24/32, logo 48/64/128/256 | ICO с PNG внутри |
| `apps/desktop/src/main/resources/icon.icns` (dmg) | logo в сетке macOS (тело 824 из 1024, тень) | 16…1024 |
| `apps/desktop/src/main/resources/icon.png` (окно) | logo | 256 |
| `apps/desktop/src/main/resources/tray.png` (трей AWT и D-Bus) | small | 64 |
| `apps/browser-extension/src/jsMain/resources/icon-{16,32,48,128}.png` | small 16/32, logo 48/128 | |
| `apps/web-ui/src/wasmJsMain/resources/favicon.svg`, `favicon-32.png` | small | SVG, 32 |
| `packaging/arch/mimir.svg` | копия logo | SVG |
| `apps/android/src/main/res/drawable/ic_launcher_{background,foreground,monochrome}.xml`, `ic_stat_mimir.xml` | logo / mono, перенесены в VectorDrawable | 108 dp (знак в круге ⌀66 dp), 24 dp |
| `export/oauth-consent-120.png` | logo | 120 — загружается вручную в Google Cloud → OAuth consent screen |
| `export/social-preview-1280x640.png` | social-preview | 1280×640 — загружается вручную в Settings → Social preview |
