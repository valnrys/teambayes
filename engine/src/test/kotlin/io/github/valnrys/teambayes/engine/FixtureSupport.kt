package io.github.valnrys.teambayes.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.fail

/**
 * The one tolerance rule from `tools/fixtures/README.md` (`close(a, e)` in
 * `generate.py`), where [actual] is ours and [expected] comes from the fixture.
 *
 * Passes if the difference is within 1e-9 absolute or 1e-9 relative, except when
 * `|expected| < 1e-9`, where only the relative test counts. That stops a tiny tail
 * value (like `pdf(8)`, about 5e-15) being "matched" by returning 0.0.
 */
fun close(actual: Double, expected: Double): Boolean {
    val d = abs(actual - expected)
    if (abs(expected) < 1e-9) return d <= 1e-9 * abs(expected)
    return d <= 1e-9 || d <= 1e-9 * abs(expected)
}

/** Reads a fixture from `src/test/resources/fixtures/`. */
fun loadFixture(name: String): JsonElement {
    val text = checkNotNull(object {}.javaClass.getResource("/fixtures/$name")) {
        "Missing fixture $name"
    }.readText()
    return Json.parseToJsonElement(text)
}

/**
 * Collects comparisons for one fixture file, so that one bad case doesn't hide the
 * rest. Call [check] for every value, then [assertAllPassed] once at the end: it
 * prints the largest errors seen and fails with every mismatch listed. It also fails
 * if nothing was checked at all, so an empty or misread fixture can't pass.
 */
class FixtureChecker(private val file: String) {
    private val failures = mutableListOf<String>()
    private var checks = 0
    private var maxAbsError = 0.0
    private var maxRelError = 0.0
    private var worstRelLabel = ""

    /** Compares [actual] with [expected] under [close]; [label] names the case and value. */
    fun check(label: String, actual: Double, expected: Double) {
        checks++
        if (actual.isNaN() || expected.isNaN()) {
            // Kept out of the error statistics: max() with NaN would hide every later value.
            failures += "$label: expected $expected, got $actual (NaN)"
            return
        }
        val d = abs(actual - expected)
        maxAbsError = max(maxAbsError, d)
        if (expected != 0.0 && d / abs(expected) > maxRelError) {
            maxRelError = d / abs(expected)
            worstRelLabel = label
        }
        if (!close(actual, expected)) {
            failures += "$label: expected $expected, got $actual (off by $d)"
        }
    }

    /** Fails unless a fixture section has exactly [expected] entries, so cases can't silently go missing. */
    fun checkCount(label: String, actual: Int, expected: Int) {
        if (actual != expected) recordFailure("$label: expected $expected entries, found $actual")
    }

    /** Records a failure that isn't a numeric comparison. */
    fun recordFailure(message: String) {
        checks++
        failures += message
    }

    fun assertAllPassed() {
        println(
            "$file: $checks checks, max abs error $maxAbsError, " +
                "max rel error $maxRelError (at $worstRelLabel)",
        )
        if (checks == 0) fail("$file: nothing was checked")
        if (failures.isNotEmpty()) {
            val shown = failures.take(MAX_SHOWN).joinToString("\n  ")
            val more = if (failures.size > MAX_SHOWN) "\n  ... and ${failures.size - MAX_SHOWN} more" else ""
            fail("$file: ${failures.size} of $checks checks failed:\n  $shown$more")
        }
    }

    private companion object {
        const val MAX_SHOWN = 40
    }
}

// Small JSON helpers, to keep the tests readable.

val JsonElement.asDouble: Double get() = jsonPrimitive.double
val JsonElement.asInt: Int get() = jsonPrimitive.int
val JsonElement.asString: String get() = jsonPrimitive.content
operator fun JsonElement.get(key: String): JsonElement =
    checkNotNull(jsonObject[key]) { "Missing key '$key'" }
val JsonElement.list: JsonArray get() = jsonArray

/** The two entries of a two-team list (teams, ranks or win probabilities); fails loudly otherwise. */
fun JsonElement.twoEntries(label: String): Pair<JsonElement, JsonElement> {
    val entries = list
    check(entries.size == 2) { "$label: expected 2 entries, found ${entries.size}" }
    return entries[0] to entries[1]
}

/** A fixture's `[teamA, teamB]` as ratings. */
fun JsonElement.twoTeams(label: String): Pair<List<Rating>, List<Rating>> {
    val (a, b) = twoEntries(label)
    return a.list.map { it.toRating() } to b.list.map { it.toRating() }
}

/** A fixture rating, `{"id": ..., "mu": ..., "sigma": ...}`. */
fun JsonElement.toRating(): Rating = Rating(this["mu"].asDouble, this["sigma"].asDouble)

/** Maps fixture ranks (`[0,1]`, `[1,0]`, `[0,0]`) to an [Outcome]. */
fun outcomeFromRanks(ranks: JsonElement): Outcome {
    val (rankA, rankB) = ranks.twoEntries("ranks").toList().map { it.asInt }
    return Outcome.entries.single { it.rankA == rankA && it.rankB == rankB }
}

/** The fixture's own outcome label: `"a"`, `"b"` or `"draw"`. */
fun outcomeFromLabel(label: String): Outcome = when (label) {
    "a" -> Outcome.A_WINS
    "b" -> Outcome.B_WINS
    "draw" -> Outcome.DRAW
    else -> error("Unknown outcome '$label'")
}
