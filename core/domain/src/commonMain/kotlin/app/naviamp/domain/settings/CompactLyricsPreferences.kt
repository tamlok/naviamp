package app.naviamp.domain.settings

fun normalizedCompactLyricsFontSize(size: Int): Int =
    when (size) {
        14, 16, 20, 24 -> size
        else -> 16
    }

fun nextCompactLyricsFontSize(size: Int): Int =
    when (normalizedCompactLyricsFontSize(size)) {
        14 -> 16
        16 -> 20
        20 -> 24
        else -> 14
    }
