package io.github.valnrys.teambayes.engine

import kotlin.test.Test

/** Checks win and draw predictions against `predict.json`. */
class PredictFixtureTest {

    private val fixture = loadFixture("predict.json")

    @Test
    fun `win and draw probabilities match in every case`() {
        val checker = FixtureChecker("predict.json")
        checker.checkCount("cases", fixture["cases"].list.size, 72)
        for (case in fixture["cases"].list) {
            val name = case["name"].asString
            val n = case["n"].asInt
            checker.check("$name beta", EngineConfig.betaFor(n), case["beta"].asDouble)

            val (teamA, teamB) = case["teams"].twoTeams(name)
            if (teamA.size + teamB.size != n) checker.recordFailure("$name: n is $n but teams have ${teamA.size + teamB.size}")

            val (winA, winB) = WengLin.predictWin(teamA, teamB)
            val (expectedWinA, expectedWinB) = case["win"].twoEntries(name)
            checker.check("$name win A", winA, expectedWinA.asDouble)
            checker.check("$name win B", winB, expectedWinB.asDouble)
            checker.check("$name draw", WengLin.predictDraw(teamA, teamB), case["draw"].asDouble)
        }
        checker.assertAllPassed()
    }
}
