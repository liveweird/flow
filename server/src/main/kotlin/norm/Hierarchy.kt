package ch.nokillswit.norm

/** Jira's epic hierarchy level (domain-model: an epic is hierarchy level 1, never "type name = Epic"). */
internal const val EPIC_HIERARCHY_LEVEL = 1

/** The two buckets of the epic/task model; an issue type above the epic's belongs to neither (A31) — [hierarchyBucket] answers `null`. */
internal enum class HierarchyBucket { EPIC, TASK }

/**
 * THE epic/task bucketing of a `hierarchyLevel` (domain-model A31), shared by the `PROJECT_FIELDS` payload split, the data profile's
 * per-level workflow statuses and DERIVE's model filter. Level 1 is an epic; level 0 a task and level -1 a sub-task (which D2 rolls
 * up into its task, so it counts with tasks); an unknown level (`null`) counts as a task (DERIVE flags those,
 * `row_counts.unknownLevelItems`); a level above 1 (a Premium "Initiative" or "Program") belongs to neither bucket (`null`) —
 * outside the model, nothing walks above the epic.
 */
internal fun hierarchyBucket(level: Int?): HierarchyBucket? = when {
    level == null -> HierarchyBucket.TASK
    level == EPIC_HIERARCHY_LEVEL -> HierarchyBucket.EPIC
    level < EPIC_HIERARCHY_LEVEL -> HierarchyBucket.TASK
    else -> null
}
