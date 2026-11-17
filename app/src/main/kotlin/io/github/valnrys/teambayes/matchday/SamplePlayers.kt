package io.github.valnrys.teambayes.matchday

import io.github.valnrys.teambayes.engine.EngineConfig
import io.github.valnrys.teambayes.engine.Prior
import io.github.valnrys.teambayes.engine.Rating

/**
 * Someone who might play on match day.
 *
 * @property id unique within one roster. The engine sorts players by id, so it also
 *   fixes the order ties are broken in. The screen never shows it.
 * @property name what the screen shows.
 * @property rating what the engine believes about this player's skill.
 */
data class Player(val id: String, val name: String, val rating: Rating)

/**
 * Made-up players, so match day has a roster worth searching before any storage exists.
 *
 * Every name is invented; none belongs to a real player. The ratings come straight from
 * the engine: [Prior.fromRankOrder] spaces the means evenly from best to worst, and every
 * player starts with the same uncertainty, [EngineConfig.SIGMA0].
 */
object SamplePlayers {

    private val firstNames = listOf(
        "Ottoline", "Bramwell", "Zephyra", "Kazimir", "Winnifred",
        "Dashiell", "Pernilla", "Quillon", "Tamsin", "Fitzgerald",
        "Ignatia", "Marisol", "Leopold", "Henrietta",
    )

    /** The fourteen players the app starts with, best-rated first. */
    val all: List<Player> = make(firstNames.size)

    /**
     * Makes [count] players, best-rated first.
     *
     * Ids are zero-padded (`p01`, `p02`, ...) so they sort the same way as the list.
     * Beyond fourteen players the names repeat with a number added ("Ottoline 2").
     * The timing test uses this to build bigger rosters than the screen shows.
     */
    fun make(count: Int): List<Player> {
        val means = Prior.fromRankOrder(count)
        return List(count) { i ->
            val round = i / firstNames.size
            val name = firstNames[i % firstNames.size] + if (round == 0) "" else " ${round + 1}"
            Player(id = idFor(i + 1), name = name, rating = Rating(mu = means[i], sigma = EngineConfig.SIGMA0))
        }
    }

    /**
     * The id for the [number]th player: `p01`, `p02`, ... `p99`, then `p100`.
     * Not String.format: its output can depend on the phone's language.
     */
    fun idFor(number: Int): String = "p" + number.toString().padStart(2, '0')
}
