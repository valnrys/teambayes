package io.github.valnrys.teambayes.data

import java.text.Normalizer

private val whitespaceRun = Regex("[\\s\\p{Z}]+")

/**
 * Puts a player's name in the one form we store and compare.
 *
 * - Unicode NFC: "e" followed by a combining accent becomes the single letter "\u00e9", so
 *   the same name typed on different keyboards is the same name.
 * - Leading and trailing spaces are removed.
 * - Any run of spaces (including tabs and non-breaking spaces) inside becomes one space: "Sam  Smith" is "Sam Smith".
 *
 * Case is kept as typed; comparing ignores it. Repositories call this for you, so UI code
 * only needs it to check a name (for example, to warn about a duplicate) before saving.
 */
fun normaliseName(name: String): String =
    Normalizer.normalize(name, Normalizer.Form.NFC).trim().replace(whitespaceRun, " ")
