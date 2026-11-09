package io.github.valnrys.teambayes.engine

import java.util.PriorityQueue
import kotlin.random.Random

/** A player to be placed on a team: an id (unique within one roster) and their current rating. */
data class BalancerPlayer(val id: String, val rating: Rating)

/** Which team a player is pinned to. */
enum class Side { A, B }

/**
 * One way to split the players into two teams.
 *
 * @property drawProbability chance of a draw; higher means a more even game.
 * @property winProbabilityA chance that team A wins.
 */
data class Split(
    val teamA: List<String>,
    val teamB: List<String>,
    val drawProbability: Double,
    val winProbabilityA: Double,
)

/** What [Balancer.balance] gives back: either the best splits, or a reason it can't. */
sealed interface BalanceResult {

    /**
     * The best splits, most even first.
     *
     * @property sampled `true` if there were too many splits to try them all, so these
     *   are the best of a random sample and a better split may exist.
     */
    data class Splits(val splits: List<Split>, val sampled: Boolean) : BalanceResult

    /** No splits could be made, for the given [reason]. */
    data class Impossible(val reason: Reason) : BalanceResult

    /** Why balancing isn't possible. */
    enum class Reason {
        /** Fewer than four players. */
        TOO_FEW_PLAYERS,

        /** More than [Balancer.MAX_PLAYERS] players. */
        TOO_MANY_PLAYERS,

        /** The locks can't all be met, for example more locked to A than fit on a team. */
        LOCKS_UNSATISFIABLE,

        /** A lock names a player who isn't in the list. */
        UNKNOWN_LOCKED_PLAYER,

        /** The same player id appears more than once. */
        DUPLICATE_PLAYER,
    }
}

/**
 * Splits players into two even teams.
 *
 * Teams have `n / 2` (rounded down) and `n / 2` (rounded up) players. For an odd
 * number of players the bigger team may be either side. A split and its mirror (the
 * same two teams with the labels swapped) count as one, unless locks fix which team
 * is which. Every split is scored by [ThurstoneMostellerFull.predictDraw]: the higher
 * the draw probability, the more even the teams.
 *
 * **Exhaustive or sampled.** First the balancer counts the splits the locks allow.
 * Up to [EXHAUSTIVE_LIMIT] splits, every one is tried. That always covers rosters of up
 * to [EXHAUSTIVE_MAX] players (20 players is 92,378 splits, which takes a few tens of
 * milliseconds on a laptop), and bigger rosters whose locks leave few choices. Above
 * the limit (22 players with no locks is about 350,000 splits) a random sample of
 * distinct splits is tried instead and the result is marked `sampled`. The sample is
 * uniform: every allowed split is equally likely to be drawn.
 *
 * **Deterministic.** The same players, locks, `k` and seed always give the same splits
 * in the same order, with the same team labels. The order the players are passed in
 * makes no difference either, because they are sorted by id first (see [balance]).
 */
object Balancer {

    /**
     * A guarantee, not a setting: rosters of this size or fewer are always tried
     * exhaustively, because they have at most 92,378 splits, under [EXHAUSTIVE_LIMIT].
     * The code doesn't read it; [EXHAUSTIVE_LIMIT] is the real switch.
     */
    const val EXHAUSTIVE_MAX: Int = 20

    /** The most splits that are all tried; above this the balancer samples. */
    const val EXHAUSTIVE_LIMIT: Int = 100_000

    /**
     * The most players accepted. Each split is tracked as one 64-bit mask while
     * sampling, and no real five-a-side group is anywhere near this size.
     */
    const val MAX_PLAYERS: Int = 64

    private const val SAMPLE_ATTEMPTS = 4 * EXHAUSTIVE_LIMIT
    private const val DEFAULT_SEED = 20_241_109L

    /**
     * Finds the [k] most even splits, best first.
     *
     * Players are sorted by id before anything else, so the result doesn't depend on
     * the order of [players]. Each team lists its players in id order. When no locks
     * are given, the player with the lowest id is always on `teamA`: otherwise every
     * split would appear twice, once with the team labels swapped.
     *
     * Splits with exactly equal scores keep the order they were found in. Exhaustively,
     * that is: for odd `n`, every split where `teamA` is the smaller team, then every
     * split where it is the larger one; within each, in dictionary order of the
     * free players picked for team A (by id). When sampling, it is the order of the
     * random draws, which the seed fixes.
     *
     * @param players everyone playing; ids must be unique, and at most [MAX_PLAYERS].
     * @param locks players pinned to a side. A player locked to [Side.A] ends up on the
     *   returned `teamA`.
     * @param k how many splits to return (fewer if fewer exist).
     * @param random where sampling randomness comes from, used only when there are more
     *   than [EXHAUSTIVE_LIMIT] splits. The default is a fixed seed, so results are
     *   repeatable; pass your own seeded [Random] to vary the sample.
     * @return [BalanceResult.Splits], or [BalanceResult.Impossible] with the reason
     *   (this function doesn't throw for bad rosters or locks).
     * @throws IllegalArgumentException if [k] is less than 1.
     */
    fun balance(
        players: List<BalancerPlayer>,
        locks: Map<String, Side> = emptyMap(),
        k: Int = 5,
        random: Random = Random(DEFAULT_SEED),
    ): BalanceResult {
        require(k >= 1) { "k must be at least 1, was $k" }
        val n = players.size
        if (n < 4) return impossible(BalanceResult.Reason.TOO_FEW_PLAYERS)
        if (n > MAX_PLAYERS) return impossible(BalanceResult.Reason.TOO_MANY_PLAYERS)
        if (players.map { it.id }.toSet().size != n) return impossible(BalanceResult.Reason.DUPLICATE_PLAYER)
        val sorted = players.sortedBy { it.id }
        val indexOf = sorted.withIndex().associate { (i, p) -> p.id to i }
        if (locks.keys.any { it !in indexOf }) return impossible(BalanceResult.Reason.UNKNOWN_LOCKED_PLAYER)

        val problem = Problem(sorted, locks.mapKeys { indexOf.getValue(it.key) }, k)
        if (problem.needs.isEmpty()) return impossible(BalanceResult.Reason.LOCKS_UNSATISFIABLE)

        val sampled = problem.totalSplits > EXHAUSTIVE_LIMIT
        if (sampled) problem.sample(random) else problem.enumerate()
        return BalanceResult.Splits(problem.finish(), sampled)
    }

    private fun impossible(reason: BalanceResult.Reason) = BalanceResult.Impossible(reason)

    /** A candidate that made the top k. [order] is when it was found, for stable tie-breaks. */
    private class Candidate(val score: Double, val order: Long, val teamA: IntArray)

    /** The working state for one call to [balance]. Indices refer to positions in [players]. */
    private class Problem(
        private val players: List<BalancerPlayer>,
        locks: Map<Int, Side>,
        private val k: Int,
    ) {
        private val n = players.size

        /** Built once: every split of this roster has the same number of players. */
        private val model = WengLin.forGame(n)

        private val lockedA = locks.filterValues { it == Side.A }.keys.toIntArray()
        private val lockedB = locks.filterValues { it == Side.B }.keys.toIntArray()

        /**
         * With no locks, player 0 (the lowest id) is always put on team A. Every split
         * has exactly one orientation with that, which is how a split and its mirror
         * count once. With locks, orientation is already fixed.
         */
        private val forcedA = if (locks.isEmpty()) intArrayOf(0) else lockedA

        /** The players who may go on either team, in index order. */
        private val free = (0 until n).filter { it !in forcedA && it !in lockedB }.toIntArray()

        /**
         * For each team-size choice the locks allow, how many free players join team A.
         * Even `n` has one size choice; odd `n` has two (the bigger team on either side).
         * Empty if the locks can't be met. The free players left over fill team B, and
         * the sizes always add up, so every entry is between 0 and `free.size`.
         */
        val needs: List<Int> = listOf(n / 2, n - n / 2).distinct()
            .filter { sizeA -> sizeA >= forcedA.size && n - sizeA >= lockedB.size }
            .map { sizeA -> sizeA - forcedA.size }

        /** How many splits each entry of [needs] gives, as doubles so large counts can't overflow. */
        private val counts = needs.map { choose(free.size, it) }

        /** All allowed splits. Exact while small, which is the only range that matters for the limit. */
        val totalSplits: Double = counts.sum()

        /** The k best so far, with the worst on top so it can be dropped when beaten. */
        private val best = PriorityQueue<Candidate>(
            compareBy<Candidate> { it.score }.thenByDescending { it.order },
        )

        /** Which players are on team A in the split being scored. */
        private val onA = BooleanArray(n)

        // Reused for every candidate, so scoring doesn't allocate two lists each time.
        private val ratingsA = ArrayList<Rating>(n)
        private val ratingsB = ArrayList<Rating>(n)
        private var order = 0L

        /** Tries every split: choose the free players who join team A, in dictionary order. */
        fun enumerate() {
            for (need in needs) {
                onA.fill(false)
                forcedA.forEach { onA[it] = true }
                val pick = IntArray(need) { it }
                do {
                    pick.forEach { onA[free[it]] = true }
                    offer()
                    pick.forEach { onA[free[it]] = false }
                } while (nextCombination(pick, free.size))
            }
        }

        /**
         * Tries up to [EXHAUSTIVE_LIMIT] distinct random splits, giving up after
         * [SAMPLE_ATTEMPTS] draws. Each team-size choice is picked in proportion to how
         * many splits it has, then the free players for team A are a uniform pick, so
         * every allowed split is equally likely.
         */
        fun sample(random: Random) {
            val seen = HashSet<Long>()
            val shuffled = free.copyOf()
            onA.fill(false)
            forcedA.forEach { onA[it] = true }
            var attempts = 0
            while (seen.size < EXHAUSTIVE_LIMIT && attempts < SAMPLE_ATTEMPTS) {
                attempts++
                val need = needs[chooseIndex(random)]
                // Partial Fisher-Yates shuffle: the first `need` entries are a uniform pick.
                for (i in 0 until need) {
                    val j = i + random.nextInt(shuffled.size - i)
                    val tmp = shuffled[i]; shuffled[i] = shuffled[j]; shuffled[j] = tmp
                }
                for (i in 0 until need) onA[shuffled[i]] = true
                if (seen.add(teamMask())) offer()
                for (i in 0 until need) onA[shuffled[i]] = false
            }
        }

        /** An index into [needs], chosen with probability proportional to its split count. */
        private fun chooseIndex(random: Random): Int {
            if (needs.size == 1) return 0
            var point = random.nextDouble() * totalSplits
            for (i in counts.indices) {
                point -= counts[i]
                if (point < 0) return i
            }
            return counts.lastIndex
        }

        /** The team A players as one number, one bit per player (up to [MAX_PLAYERS]). */
        private fun teamMask(): Long {
            var mask = 0L
            for (i in 0 until n) if (onA[i]) mask = mask or (1L shl i)
            return mask
        }

        /** Scores the split currently marked in [onA] and keeps it if it is in the top k. */
        private fun offer() {
            ratingsA.clear()
            ratingsB.clear()
            for (i in 0 until n) (if (onA[i]) ratingsA else ratingsB) += players[i].rating
            val score = model.predictDraw(ratingsA, ratingsB)
            val thisOrder = order++

            if (best.size >= k) {
                // Later finds lose ties, so only a strictly better score gets in.
                if (score <= best.peek().score) return
                best.poll()
            }
            best += Candidate(score, thisOrder, (0 until n).filter { onA[it] }.toIntArray())
        }

        /** The survivors, best first, with win probabilities filled in. */
        fun finish(): List<Split> =
            best.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.order }).map { c ->
                val inA = c.teamA.toSet()
                val a = players.indices.filter { it in inA }
                val b = players.indices.filter { it !in inA }
                val ratingsOfA = a.map { players[it].rating }
                val ratingsOfB = b.map { players[it].rating }
                Split(
                    teamA = a.map { players[it].id },
                    teamB = b.map { players[it].id },
                    drawProbability = c.score,
                    winProbabilityA = model.predictWin(ratingsOfA, ratingsOfB).teamA,
                )
            }
    }

    /** "n choose r" as a Double: exact while small, and can't overflow for rosters of up to [MAX_PLAYERS]. */
    private fun choose(n: Int, r: Int): Double {
        var result = 1.0
        for (i in 0 until minOf(r, n - r)) result = result * (n - i) / (i + 1)
        return Math.rint(result)
    }

    /**
     * Moves [pick], an increasing list of positions in `0 until size`, to the next
     * combination in dictionary order. Returns false when it was already the last.
     */
    private fun nextCombination(pick: IntArray, size: Int): Boolean {
        var i = pick.size - 1
        while (i >= 0 && pick[i] == i + size - pick.size) i--
        if (i < 0) return false
        pick[i]++
        for (j in i + 1 until pick.size) pick[j] = pick[j - 1] + 1
        return true
    }
}
