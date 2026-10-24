package dev.mimir.core.fs

import kotlinx.io.files.Path

/** kotlinx-io не отдаёт mtime, поэтому берём его платформенно. */
internal expect fun lastModifiedMillis(path: Path): Long
