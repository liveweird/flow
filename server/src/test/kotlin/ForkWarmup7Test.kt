package ch.nokillswit

import org.junit.BeforeClass
import kotlin.test.Test

/** Slot 7 of 8 (`ForkWarmup.kt`): the `test` task pins it first in fork 7 for 7 <= `-Pforks`. */
class ForkWarmup7Test {
    private companion object {
        @JvmStatic
        @BeforeClass
        fun warm() = ForkWarmup.warm()
    }

    @Test
    fun `the fork's database is migrated and the app has booted once`() = ForkWarmup.assertWarm()
}
