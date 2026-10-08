package ch.nokillswit.jira

/**
 * The epic/task bucketing of Jira issue types by `hierarchyLevel` (domain-model: an epic is hierarchy level 1, never "type name =
 * Epic"), shared by the `PROJECT_FIELDS` payload split and the data profile's per-level workflow statuses. Level 1 is an epic type;
 * level 0 a task type and level -1 a sub-task (which D2 rolls up into its task, so it counts with tasks); an issue type missing from
 * the hierarchy counts as a task type; a level above 1 (a Premium "Initiative") belongs to neither bucket.
 */
internal object JiraHierarchy {
    const val EPIC_LEVEL = 1

    enum class Bucket { EPIC, TASK }

    /** The bucket of an issue type whose `hierarchyLevel` is [level] (`null` = unknown), or `null` for a level above the epic's. */
    fun bucket(level: Int?): Bucket? = when {
        level == null -> Bucket.TASK
        level == EPIC_LEVEL -> Bucket.EPIC
        level < EPIC_LEVEL -> Bucket.TASK
        else -> null
    }
}
