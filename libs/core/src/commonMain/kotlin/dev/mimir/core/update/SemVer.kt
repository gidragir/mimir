package dev.mimir.core.update

/** Семантическая версия `major.minor.patch[-preRelease]` (build-метаданные не поддерживаются). */
class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
) : Comparable<SemVer> {

    fun isNewerThan(other: SemVer): Boolean = compareTo(other) > 0

    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        val a = preRelease
        val b = other.preRelease
        return when {
            a == null && b == null -> 0
            a == null -> 1
            b == null -> -1
            else -> comparePreRelease(a, b)
        }
    }

    override fun equals(other: Any?): Boolean = other is SemVer && compareTo(other) == 0

    override fun hashCode(): Int = listOf(major, minor, patch, preRelease).hashCode()

    override fun toString(): String =
        if (preRelease == null) "$major.$minor.$patch" else "$major.$minor.$patch-$preRelease"

    companion object {
        private val PATTERN = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?$")

        fun parse(value: String): SemVer {
            val m = requireNotNull(PATTERN.matchEntire(value.trim())) { "Invalid semantic version: '$value'" }
            fun num(i: Int): Int =
                requireNotNull(m.groupValues[i].toIntOrNull()) { "Version component out of range: '$value'" }
            return SemVer(num(1), num(2), num(3), m.groups[4]?.value)
        }

        private fun comparePreRelease(a: String, b: String): Int {
            val x = a.split('.')
            val y = b.split('.')
            for (i in 0 until minOf(x.size, y.size)) {
                val c = compareIdentifier(x[i], y[i])
                if (c != 0) return c
            }
            return x.size.compareTo(y.size)
        }

        private fun compareIdentifier(a: String, b: String): Int {
            val na = a.isNumeric()
            val nb = b.isNumeric()
            return when {
                na && nb -> {
                    // Числа сравниваются численно без ограничения разрядности: длина, затем лексикографически.
                    val ta = a.trimStart('0')
                    val tb = b.trimStart('0')
                    if (ta.length != tb.length) ta.length.compareTo(tb.length) else ta.compareTo(tb)
                }
                na -> -1
                nb -> 1
                else -> a.compareTo(b)
            }
        }

        private fun String.isNumeric(): Boolean = isNotEmpty() && all { it in '0'..'9' }
    }
}
