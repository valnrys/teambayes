package io.github.valnrys.teambayes.engine

import kotlin.test.Test

/** Checks single rating updates, and the per-game beta, against `rate.json`. */
class RateFixtureTest {

    private val fixture = loadFixture("rate.json")

    @Test
    fun `every player's new mu and sigma match in every case`() {
        val checker = FixtureChecker("rate.json")
        checker.checkCount("cases", fixture["cases"].list.size, 117)
        for (case in fixture["cases"].list) {
            val name = case["name"].asString
            val n = case["n"].asInt
            checker.check("$name beta", EngineConfig.betaFor(n), case["beta"].asDouble)

            val outcome = outcomeFromRanks(case["ranks"])
            if (outcome != outcomeFromLabel(case["outcome"].asString)) {
                checker.recordFailure("$name: ranks and outcome label disagree")
            }

            val (teamA, teamB) = case["teams"].twoTeams(name)
            if (teamA.size + teamB.size != n) checker.recordFailure("$name: n is $n but teams have ${teamA.size + teamB.size}")
            val (newA, newB) = WengLin.rate(teamA, teamB, outcome)

            val (expectedA, expectedB) = case["result"].twoEntries(name)
            for ((teamIndex, actualTeam) in listOf(newA, newB).withIndex()) {
                val expectedTeam = listOf(expectedA, expectedB)[teamIndex].list
                if (actualTeam.size != expectedTeam.size) {
                    checker.recordFailure("$name team $teamIndex: ${actualTeam.size} players, expected ${expectedTeam.size}")
                    continue
                }
                for ((actual, want) in actualTeam.zip(expectedTeam)) {
                    val id = want["id"].asString
                    checker.check("$name $id mu", actual.mu, want["mu"].asDouble)
                    checker.check("$name $id sigma", actual.sigma, want["sigma"].asDouble)
                }
            }
        }
        checker.assertAllPassed()
    }
}
