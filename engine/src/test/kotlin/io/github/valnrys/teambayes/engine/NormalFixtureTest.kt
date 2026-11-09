package io.github.valnrys.teambayes.engine

import io.github.valnrys.teambayes.engine.ThurstoneMostellerFull.Companion.v
import io.github.valnrys.teambayes.engine.ThurstoneMostellerFull.Companion.vt
import io.github.valnrys.teambayes.engine.ThurstoneMostellerFull.Companion.w
import io.github.valnrys.teambayes.engine.ThurstoneMostellerFull.Companion.wt
import kotlin.test.Test

/** Checks the normal-distribution primitives and openskill's helpers against `normal.json`. */
class NormalFixtureTest {

    private val fixture = loadFixture("normal.json")

    @Test
    fun `erfc, cdf and pdf match at every point`() {
        val checker = FixtureChecker("normal.json points")
        checker.checkCount("points", fixture["points"].list.size, 87)
        for (point in fixture["points"].list) {
            val x = point["x"].asDouble
            checker.check("erfc($x)", NormalDist.erfc(x), point["erfc"].asDouble)
            checker.check("cdf($x)", NormalDist.cdf(x), point["cdf"].asDouble)
            checker.check("pdf($x)", NormalDist.pdf(x), point["pdf"].asDouble)
            // vt(0, t) relies on pdf being exactly symmetric.
            if (NormalDist.pdf(x) != NormalDist.pdf(-x)) {
                checker.recordFailure("pdf($x) != pdf(${-x}) bit for bit")
            }
        }
        checker.assertAllPassed()
    }

    @Test
    fun `invCdf matches, including at the draw probabilities`() {
        val checker = FixtureChecker("normal.json inv_cdf")
        checker.checkCount("inv_cdf", fixture["inv_cdf"].list.size, 17)
        checker.checkCount("inv_cdf_draw", fixture["inv_cdf_draw"].list.size, 17)
        for (row in fixture["inv_cdf"].list) {
            val p = row["p"].asDouble
            checker.check("invCdf($p)", NormalDist.invCdf(p), row["value"].asDouble)
        }
        for (row in fixture["inv_cdf_draw"].list) {
            val n = row["n"].asInt
            val p = (1 + 1.0 / n) / 2
            checker.check("draw p for n=$n", p, row["p"].asDouble)
            checker.check("invCdf(draw p, n=$n)", NormalDist.invCdf(p), row["value"].asDouble)
        }
        checker.assertAllPassed()
    }

    @Test
    fun `v, w, vt and wt match on every branch`() {
        val functions = mapOf<String, (Double, Double) -> Double>(
            "v" to ::v,
            "w" to ::w,
            "vt" to ::vt,
            "wt" to ::wt,
        )
        val expectedBranches = mapOf(
            "v" to setOf("main", "fallback"),
            "w" to setOf("main", "fallback_x_neg", "fallback_x_nonneg"),
            "vt" to setOf("main", "fallback_x_neg", "fallback_x_nonneg"),
            "wt" to setOf("main", "fallback"),
        )
        val expectedRows = mapOf("v" to 558, "w" to 558, "vt" to 564, "wt" to 564)
        val checker = FixtureChecker("normal.json v/w/vt/wt")
        for ((name, f) in functions) {
            checker.checkCount(name, fixture[name].list.size, expectedRows.getValue(name))
            val branchesSeen = mutableSetOf<String>()
            for (row in fixture[name].list) {
                val x = row["x"].asDouble
                val t = row["t"].asDouble
                val branch = row["branch"].asString
                branchesSeen += branch
                checker.check("$name($x, $t) [$branch]", f(x, t), row["value"].asDouble)
            }
            val missing = expectedBranches.getValue(name) - branchesSeen
            if (missing.isNotEmpty()) checker.recordFailure("$name: fixture has no rows for branches $missing")
        }
        checker.assertAllPassed()
    }
}
