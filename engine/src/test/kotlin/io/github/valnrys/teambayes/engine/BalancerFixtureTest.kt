package io.github.valnrys.teambayes.engine

import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertIs

/** Checks the balancer against `balancer.json`: scores and counts, not split identity. */
class BalancerFixtureTest {

    @Test
    fun `top five distinct scores and split counts match`() {
        val checker = FixtureChecker("balancer.json")
        val cases = loadFixture("balancer.json")["cases"].list
        checker.checkCount("cases", cases.size, 70)
        for (case in cases) {
            val name = case["name"].asString
            val players = case["players"].list.map { BalancerPlayer(it["id"].asString, it.toRating()) }
            val locks = case["locks"].jsonObject.mapValues { (_, side) ->
                if (side.asString == "a") Side.A else Side.B
            }

            val result = Balancer.balance(players, locks, k = 1_000_000)
            val splits = assertIs<BalanceResult.Splits>(result, name).splits

            checker.checkCount("$name splits_considered", splits.size, case["splits_considered"].asInt)
            if (splits.map { canonicalKey(it) }.toSet().size != splits.size) {
                checker.recordFailure("$name: duplicate or mirrored splits returned")
            }

            val top5 = distinctTop(splits.map { it.drawProbability }, 5)
            val want = case["top5_draw"].list.map { it.asDouble }
            checker.checkCount("$name top5 count", top5.size, want.size)
            top5.zip(want).forEachIndexed { i, (got, expected) -> checker.check("$name top5[$i]", got, expected) }

            for (split in splits) {
                for ((id, side) in locks) {
                    val team = if (side == Side.A) split.teamA else split.teamB
                    if (id !in team) checker.recordFailure("$name: a split breaks lock $id")
                }
            }
        }
        checker.assertAllPassed()
    }

    /** The README's greedy dedupe: keep a value only if it is more than 1e-9 below the last kept one. */
    private fun distinctTop(sortedDescending: List<Double>, count: Int): List<Double> {
        val kept = mutableListOf<Double>()
        for (value in sortedDescending) {
            if (kept.isEmpty() || kept.last() - value > 1e-9) kept += value
            if (kept.size == count) break
        }
        return kept
    }
}
