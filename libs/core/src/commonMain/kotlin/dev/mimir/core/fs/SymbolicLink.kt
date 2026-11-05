package dev.mimir.core.fs

import kotlinx.io.files.Path

/** kotlinx-io следует за симлинками, поэтому проверяем их платформенно: vault не разворачивает ссылки. */
internal expect fun isSymbolicLink(path: Path): Boolean
