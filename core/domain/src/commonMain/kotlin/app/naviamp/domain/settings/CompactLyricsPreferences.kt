package app.naviamp.domain.settings

fun normalizedCompactLyricsFontSize(size: Int): Int =
    when (size) {
        14, 20, 24, 48, 64 -> size
        else -> 24
    }

fun nextCompactLyricsFontSize(size: Int): Int =
    when (normalizedCompactLyricsFontSize(size)) {
        14 -> 20
        20 -> 24
        24 -> 48
        48 -> 64
        else -> 14
    }
