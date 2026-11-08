package io.github.valnrys.teambayes.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class VersionTest {
    @Test
    fun engineNameIsStable() {
        assertEquals("teambayes-engine", EngineInfo.NAME)
    }
}
