package io.github.valnrys.teambayes.engine

import kotlin.test.Test

/**
 * Replays each chain in `chains.json` game by game, carrying our own ratings forward,
 * and checks the whole pool after every game. Errors compound here, so this catches
 * drift that a single update would hide.
 */
class ChainsFixtureTest {

    private val fixture = loadFixture("chains.json")

    @Test
    fun `ratings match after every game of every chain`() {
        val checker = FixtureChecker("chains.json")
        checker.checkCount("chains", fixture["chains"].list.size, 5)
        for (chain in fixture["chains"].list) {
            val chainName = chain["name"].asString
            checker.checkCount("$chainName games", chain["games"].list.size, 10)
            val pool = chain["initial"].list.associate { it["id"].asString to it.toRating() }.toMutableMap()

            for ((gameIndex, game) in chain["games"].list.withIndex()) {
                val label = "$chainName game ${gameIndex + 1}"
                val n = game["n"].asInt
                checker.check("$label beta", EngineConfig.betaFor(n), game["beta"].asDouble)

                val (teamIdsA, teamIdsB) = game["teams"].twoEntries(label)
                val idsA = teamIdsA.list.map { it.asString }
                val idsB = teamIdsB.list.map { it.asString }
                if (idsA.size + idsB.size != n) checker.recordFailure("$label: n is $n but teams have ${idsA.size + idsB.size}")
                val (newA, newB) = WengLin.rate(
                    teamA = idsA.map { pool.getValue(it) },
                    teamB = idsB.map { pool.getValue(it) },
                    outcome = outcomeFromRanks(game["ranks"]),
                )
                checker.checkCount("$label new team A", newA.size, idsA.size)
                checker.checkCount("$label new team B", newB.size, idsB.size)
                idsA.zip(newA).forEach { (id, rating) -> pool[id] = rating }
                idsB.zip(newB).forEach { (id, rating) -> pool[id] = rating }

                checker.checkCount("$label ratings_after", game["ratings_after"].list.size, pool.size)
                for (want in game["ratings_after"].list) {
                    val id = want["id"].asString
                    val actual = pool.getValue(id)
                    checker.check("$label $id mu", actual.mu, want["mu"].asDouble)
                    checker.check("$label $id sigma", actual.sigma, want["sigma"].asDouble)
                }
            }
        }
        checker.assertAllPassed()
    }
}
