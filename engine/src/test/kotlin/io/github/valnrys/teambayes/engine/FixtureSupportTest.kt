package io.github.valnrys.teambayes.engine

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Guards the test harness itself: a checker that can't fail would make every fixture test meaningless. */
class FixtureSupportTest {

    @Test
    fun `close uses absolute or relative 1e-9 for normal values`() {
        assertTrue(close(1.0 + 9e-10, 1.0))
        assertFalse(close(1.0 + 2e-9, 1.0))
        assertTrue(close(1e6 + 5e-4, 1e6)) // relative passes where absolute doesn't
    }

    @Test
    fun `close is relative only for tiny expected values`() {
        assertFalse(close(0.0, 5e-15)) // returning 0.0 for a tail value is not a match
        assertTrue(close(5e-15 * (1 + 5e-10), 5e-15))
        assertTrue(close(0.0, 0.0))
        assertFalse(close(1e-300, 0.0))
    }

    @Test
    fun `checker fails when any value is off`() {
        val checker = FixtureChecker("self-test")
        checker.check("good", 1.0, 1.0)
        checker.check("bad", 2.0, 1.0)
        assertFailsWith<AssertionError> { checker.assertAllPassed() }
    }

    @Test
    fun `checker fails on a NaN actual value`() {
        val checker = FixtureChecker("self-test")
        checker.check("nan", Double.NaN, 1.0)
        checker.check("good", 1.0, 1.0) // a later good value must not hide the NaN
        assertFailsWith<AssertionError> { checker.assertAllPassed() }
    }

    @Test
    fun `checker fails on a count mismatch`() {
        val checker = FixtureChecker("self-test")
        checker.check("good", 1.0, 1.0)
        checker.checkCount("cases", actual = 116, expected = 117)
        assertFailsWith<AssertionError> { checker.assertAllPassed() }
    }

    @Test
    fun `checker fails when nothing was checked`() {
        assertFailsWith<AssertionError> { FixtureChecker("self-test").assertAllPassed() }
    }

    @Test
    fun `checker passes when everything matches`() {
        val checker = FixtureChecker("self-test")
        checker.check("good", 1.0, 1.0)
        checker.checkCount("cases", actual = 3, expected = 3)
        checker.assertAllPassed()
    }

    @Test
    fun `checker fails on a recorded failure`() {
        val checker = FixtureChecker("self-test")
        checker.recordFailure("something structural")
        assertFailsWith<AssertionError> { checker.assertAllPassed() }
    }
}
