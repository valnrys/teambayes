package io.github.valnrys.teambayes.matchday

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.valnrys.teambayes.engine.BalanceResult
import io.github.valnrys.teambayes.engine.Balancer
import io.github.valnrys.teambayes.engine.BalancerPlayer
import android.os.Build
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures how long the balancer takes on a real phone.
 *
 * 18 players is 24,310 splits and 20 players is 92,378 (every one of them is tried), so
 * these are the heaviest rosters a five-a-side group is likely to use. Results go to
 * logcat under the tag `TeamBayesTiming`:
 * `adb logcat -d -s TeamBayesTiming`.
 *
 * The 200 ms limit is for physical phones. An emulator's speed depends on the laptop
 * running it, so there the numbers are logged but the limit is not enforced.
 */
@RunWith(AndroidJUnit4::class)
class BalancerTimingTest {

    @Test
    fun eighteenPlayers_medianUnder200ms() {
        val median = measure(playerCount = 18)
        assumeFalse("Timing limits only apply to physical devices", isEmulator())
        assertTrue("Median for 18 players was $median ms, expected under 200 ms", median < 200)
    }

    @Test
    fun twentyPlayers_logsTiming() {
        // No limit asserted: this one is for information.
        measure(playerCount = 20)
    }

    /** True on an emulator: FINGERPRINT starts with "generic" or contains "emulator", HARDWARE is "ranchu" or "goldfish", or PRODUCT contains "sdk_gphone". */
    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.HARDWARE in setOf("ranchu", "goldfish") ||
            Build.PRODUCT.contains("sdk_gphone") ||
            Build.FINGERPRINT.contains("emulator")

    /** Runs the balancer a few times after a warm-up, logs the median and max, and returns the median in ms. */
    private fun measure(playerCount: Int): Long {
        val roster = SamplePlayers.make(playerCount).map { BalancerPlayer(it.id, it.rating) }

        // Warm-up runs let the JIT compiler optimise the code, like a phone that's been used for a while.
        repeat(WARM_UP_RUNS) { check(Balancer.balance(roster, k = 5) is BalanceResult.Splits) }

        val millis = List(MEASURED_RUNS) {
            val start = System.nanoTime()
            val result = Balancer.balance(roster, k = 5)
            val elapsed = (System.nanoTime() - start) / 1_000_000
            check(result is BalanceResult.Splits)
            elapsed
        }.sorted()

        val median = millis[millis.size / 2]
        Log.i("TeamBayesTiming", "$playerCount players: median=$median ms, max=${millis.last()} ms, runs=$millis")
        return median
    }

    private companion object {
        const val WARM_UP_RUNS = 2
        const val MEASURED_RUNS = 7
    }
}
