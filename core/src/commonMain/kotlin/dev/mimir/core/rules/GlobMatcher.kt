package dev.mimir.core.rules

internal object GlobMatcher {

    fun matches(pattern: String, path: String): Boolean {
        val patternSegments = normalize(pattern)
        val pathSegments = normalize(path)
        return matchSegments(patternSegments, pathSegments, 0, 0)
    }

    private fun normalize(p: String): List<String> =
        p.replace('\\', '/').split('/').filter { it.isNotEmpty() }

    private fun matchSegments(
        pSegments: List<String>,
        pathSegments: List<String>,
        pIdx: Int,
        pathIdx: Int,
    ): Boolean {
        if (pIdx == pSegments.size) {
            return pathIdx == pathSegments.size
        }

        if (pSegments[pIdx] == "**") {
            if (pIdx == pSegments.size - 1) {
                return true
            }
            for (i in pathIdx..pathSegments.size) {
                if (matchSegments(pSegments, pathSegments, pIdx + 1, i)) {
                    return true
                }
            }
            return false
        }

        if (pathIdx == pathSegments.size) {
            return false
        }

        if (matchSingleSegment(pSegments[pIdx], pathSegments[pathIdx])) {
            return matchSegments(pSegments, pathSegments, pIdx + 1, pathIdx + 1)
        }

        return false
    }

    private fun matchSingleSegment(pattern: String, text: String): Boolean {
        var pIdx = 0
        var tIdx = 0
        var starIdx = -1
        var matchIdx = 0

        while (tIdx < text.length) {
            if (pIdx < pattern.length && (pattern[pIdx] == '?' || pattern[pIdx] == text[tIdx])) {
                pIdx++
                tIdx++
            } else if (pIdx < pattern.length && pattern[pIdx] == '*') {
                starIdx = pIdx
                matchIdx = tIdx
                pIdx++
            } else if (starIdx != -1) {
                pIdx = starIdx + 1
                matchIdx++
                tIdx = matchIdx
            } else {
                return false
            }
        }

        while (pIdx < pattern.length && pattern[pIdx] == '*') {
            pIdx++
        }

        return pIdx == pattern.length
    }
}
