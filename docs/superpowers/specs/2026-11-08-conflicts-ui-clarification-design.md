# Спецификация: Уточнение терминологии и UX окна конфликтов

## 1. Контекст и проблема
В окне конфликтов (`ConflictsScreen`) текущие действия обозначены как «Оставить оригинал» и «Оставить копию».
Это вводит пользователя в заблуждение:
1. Непонятно происхождение файлов: где версия из Google Drive, а где версия, отредактированная локально на текущем устройстве.
2. Непонятно последствие действия: фраза «Оставить копию» воспринимается так, будто файл останется лежать рядом с суффиксом `(conflicted copy)`, тогда как фактически он перезаписывает основной файл и удаляет копию.

## 2. Цели и границы
- **Цели:**
  - Явно обозначить роли файлов: версия из облака (Google Drive) и локальная версия (с текущего устройства).
  - Дать понятные подписи действий и поясняющие подсказки к кнопкам.
  - Перевести карточку и кнопки `ConflictsScreen` на дизайн-систему Mimir (`MimirCard`, `MimirButton`, `MimirTokens`).
  - Вынести все тексты экрана в объект `ConflictsTexts`.
- **Не-цели:**
  - Изменение API демона или рантайма (`model.resolveConflict(originalPath, conflictPath, keepConflict)` сохраняет сигнатуру).
  - Встроенный визуальный построчный diff внутри Mimir.

## 3. Рассмотренные подходы
1. **Блочный выбор с отдельными карточками на каждую версию:** подробное описание каждого файла в две строки с крупными кнопками. Отклонено из-за чрезмерной вертикальной высоты карточки при длинных списках.
2. **Компактные кнопки с контекстными подписями действий (Выбран):** кнопки «Оставить из облака» и «Оставить локальную» с лаконичными поясняющими подписями под ними, объясняющими судьбу файлов.
3. **Радио-селектор с отдельной кнопкой подтверждения:** радиокнопки для выбора версии + кнопка «Применить». Отклонено, так как требует двух кликов вместо одного.

## 4. Детальный дизайн

### 4.1 Тексты (`ConflictsTexts`)
Создаётся объект `dev.mimir.ui.screens.ConflictsTexts`:
```kotlin
package dev.mimir.ui.screens

object ConflictsTexts {
    const val TITLE = "Конфликты"
    const val BACK = "Назад"
    const val NO_CONFLICTS = "Конфликтов не обнаружено"
    const val RESOLVE_FAILED = "Не удалось разрешить конфликт"
    const val CREATED_PREFIX = "Создан: "
    const val CONFLICT_COPY_LABEL = "Конфликтная копия: "

    const val KEEP_CLOUD_BUTTON = "Оставить из облака"
    const val KEEP_CLOUD_HINT = "Сохранит версию Drive, удалит копию"

    const val KEEP_LOCAL_BUTTON = "Оставить локальную"
    const val KEEP_LOCAL_HINT = "Заменит файл локальной версией, удалит копию"
}
```

### 4.2 Экран `ConflictsScreen`
- Контейнер карточки: `MimirCard` с отступами `MimirTokens.Space16` (16 dp).
- Заголовок: `entry.originalPath` со стилем `MaterialTheme.typography.titleMedium`.
- Информация о копии: `entry.conflictPath` и дата обнаружения конфликта.
- Блок действий:
  - Колонка 1:
    - `MimirButton(text = ConflictsTexts.KEEP_CLOUD_BUTTON, variant = MimirButtonVariant.Primary, onClick = { resolve(entry, keepConflict = false) })`
    - Подпись мелким шрифтом: `ConflictsTexts.KEEP_CLOUD_HINT`
  - Колонка 2:
    - `MimirButton(text = ConflictsTexts.KEEP_LOCAL_BUTTON, variant = MimirButtonVariant.Secondary, onClick = { resolve(entry, keepConflict = true) })`
    - Подпись мелким шрифтом: `ConflictsTexts.KEEP_LOCAL_HINT`
- На широких экранах кнопки располагаются в строку (`Row`), на узких или при нехватке места гармонично масштабируются.

## 5. Инварианты Mimir
1. **Платформы:** `libs/ui` — чистый Compose Multiplatform (Desktop, Android, Wasm). Нет платформозависимых вызовов, одинаковое поведение и верстка везде.
2. **Дизайн:** Использование токенов (`MimirTokens.Space*`), крафтовых компонентов (`MimirCard`, `MimirButton`), изоляция строк в `ConflictsTexts`. Никаких raw emoji и нестилизованных цветов.
3. **IO:** Неприменимо (UI слой; вызовы разрешения конфликтов передаются через асинхронный UDF контракт `MimirUiModel`).
4. **UX:** Все состояния:
   - *Пусто:* надпись «Конфликтов не обнаружено».
   - *Список:* карточки с понятными кнопками и подсказками.
   - *Ошибка:* вывод сообщения об ошибке разрешения.
   - *Действие в процессе:* асинхронный запуск в coroutine scope.

## 6. План тестирования
- Обновление `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/ConflictsScreenTest.kt` под новые тексты кнопок `KEEP_CLOUD_BUTTON` и `KEEP_LOCAL_BUTTON`.
- Обновление `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/MobileAdaptationTest.kt` (`assertHeightIsAtLeast(48.dp)` для touch target).
- Прогон UI-тестов: `./gradlew :libs:ui:desktopTest`.
