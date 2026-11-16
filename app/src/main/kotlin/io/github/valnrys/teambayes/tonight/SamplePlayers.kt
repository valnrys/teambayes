package io.github.valnrys.teambayes.tonight

import io.github.valnrys.teambayes.engine.EngineConfig
import io.github.valnrys.teambayes.engine.Prior
import io.github.valnrys.teambayes.engine.Rating

/**
 * Someone who might play tonight.
 *
 * @property id unique within one roster. The engine sorts players by id, so it also
 *   fixes the order ties are broken in.
 * @property name what the screen shows.
 * @property rating what the engine believes about this player's skill.
 */
data class TonightPlayer(val id: String, val name: String, val rating: Rating)

/**
 * Made-up players, so the first screen has something to balance before any storage exists.
 *
 * Every name is invented. The ratings come straight from the engine: [Prior.fromRankOrder]
 * spaces the means evenly from best to worst, and every player starts with the same
 * uncertainty, [EngineConfig.SIGMA0].
 */
object SamplePlayers {

    private val firstNames = listOf(
        "Ottoline", "Bramwell", "Zephyra", "Kazimir", "Winnifred",
        "Dashiell", "Pernilla", "Quillon", "Tamsin", "Fitzgerald",
    )

    /** The ten players the app starts with, best-rated first. */
    val tonight: List<TonightPlayer> = make(10)

    /**
     * Makes [count] players, best-rated first.
     *
     * Ids are zero-padded (`p01`, `p02`, ...) so they sort the same way as the list.
     * Beyond ten players the names repeat with a number added ("Ottoline 2").
     * The timing test uses this to build bigger rosters than the screen shows.
     */
    fun make(count: Int): List<TonightPlayer> {
        val means = Prior.fromRankOrder(count)
        return List(count) { i ->
            val round = i / firstNames.size
            val name = firstNames[i % firstNames.size] + if (round == 0) "" else " ${round + 1}"
            TonightPlayer(
                // Not String.format: its output can depend on the phone's language.
                id = "p" + (i + 1).toString().padStart(2, '0'),
                name = name,
                rating = Rating(mu = means[i], sigma = EngineConfig.SIGMA0),
            )
        }
    }
}
