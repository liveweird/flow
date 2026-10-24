package ch.nokillswit

import org.junit.BeforeClass
import kotlin.test.Test

/** Slot 5 of 8 (`ForkWarmup.kt`): the `test` task pins it first in fork 5 for 5 <= `-Pforks`. */
class ForkWarmup5Test {
    private companion object {
        @JvmStatic
        @BeforeClass
        fun warm() = ForkWarmup.warm()
    }

    @Test
    fun `the fork's database is migrated and the app has booted once`() = ForkWarmup.assertWarm()
}
