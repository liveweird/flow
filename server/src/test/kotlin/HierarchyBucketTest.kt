package ch.nokillswit

import ch.nokillswit.norm.EPIC_HIERARCHY_LEVEL
import ch.nokillswit.norm.HierarchyBucket
import ch.nokillswit.norm.hierarchyBucket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A31: the one epic/task bucketing of a Jira `hierarchyLevel` (`norm/Hierarchy.kt`). */
class HierarchyBucketTest {
    @Test
    fun `level 1 is an epic`() {
        assertEquals(1, EPIC_HIERARCHY_LEVEL)
        assertEquals(HierarchyBucket.EPIC, hierarchyBucket(1))
    }

    @Test
    fun `level 0 and sub-task level -1 are tasks, any lower level too`() {
        assertEquals(HierarchyBucket.TASK, hierarchyBucket(0))
        assertEquals(HierarchyBucket.TASK, hierarchyBucket(-1))
        assertEquals(HierarchyBucket.TASK, hierarchyBucket(-5))
    }

    @Test
    fun `an unknown level counts as a task`() {
        assertEquals(HierarchyBucket.TASK, hierarchyBucket(null))
    }

    @Test
    fun `a level above the epic's belongs to neither bucket`() {
        assertNull(hierarchyBucket(2))
        assertNull(hierarchyBucket(3))
        assertNull(hierarchyBucket(Int.MAX_VALUE))
    }
}
