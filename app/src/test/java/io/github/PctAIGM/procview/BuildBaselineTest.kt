package io.github.PctAIGM.procview

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildBaselineTest {
    @Test
    fun applicationIdContractRemainsStable() {
        assertEquals("io.github.PctAIGM.procview", BuildConfig.APPLICATION_ID.removeSuffix(".debug"))
    }
}
