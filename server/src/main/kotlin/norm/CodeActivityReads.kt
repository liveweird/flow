package ch.nokillswit.norm

import ch.nokillswit.infra.json.parseStringArray
import ch.nokillswit.norm.CodeActivityStore.CodeCommits
import ch.nokillswit.norm.CodeActivityStore.CodeIdentities
import ch.nokillswit.norm.CodeActivityStore.CodeMergeRequests
import ch.nokillswit.norm.CodeActivityStore.CodeMrEvents
import ch.nokillswit.norm.CodeActivityStore.CodeProjects
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The code layer's reads ([CodeActivityStore] delegates here): the data profile's aggregates - every figure is a database-side
 * `count`/`min`/`max`/`GROUP BY`, no row is loaded and no name, address or title is returned - and the row readers (the tests', and the
 * Identities page's identity rows and proposal matches). "Live" everywhere means `deleted_at IS NULL` (a tombstone is mirrored, never
 * removed). The three statements that join `norm` tables are raw SQL through `exec` (the `JiraRawProcessReads` precedent) with only
 * the connection id (an unsigned integer) in the text.
 */
internal class CodeActivityReads(private val database: R2dbcDatabase) {

    suspend fun projectCounts(connectionId: UInt): CodeProjectCounts = suspendTransaction(database) {
        suspend fun projects(extra: Op<Boolean>) =
            CodeProjects.selectAll().where { (CodeProjects.connectionId eq connectionId) and extra }.count()
        val live = CodeProjects.deletedAt.isNull() and CodeProjects.movedOutAt.isNull()
        CodeProjectCounts(
            live = projects(live),
            movedOut = projects(CodeProjects.movedOutAt.isNotNull() and CodeProjects.deletedAt.isNull()),
            deleted = projects(CodeProjects.deletedAt.isNotNull()),
            archived = projects(live and (CodeProjects.archived eq true)),
        )
    }

    suspend fun commitFigures(connectionId: UInt): CodeCommitFigures = suspendTransaction(database) {
        suspend fun commits(extra: Op<Boolean>) =
            CodeCommits.selectAll()
                .where { (CodeCommits.connectionId eq connectionId) and CodeCommits.deletedAt.isNull() and extra }.count()
        val earliest = CodeCommits.committedAt.min()
        val latest = CodeCommits.committedAt.max()
        val range = CodeCommits.select(earliest, latest)
            .where { (CodeCommits.connectionId eq connectionId) and CodeCommits.deletedAt.isNull() }.toList().single()
        val nonMerge = CodeCommits.isMerge eq false
        CodeCommitFigures(
            count = commits(nonMerge),
            merges = commits(CodeCommits.isMerge eq true),
            earliestCommittedAt = range[earliest],
            latestCommittedAt = range[latest],
            filesCapped = commits(nonMerge and (CodeCommits.filesCapped eq true)),
            commitsWithCounts = commits(nonMerge and CodeCommits.additionsCounted.isNotNull() and CodeCommits.deletionsCounted.isNotNull()),
        )
    }

    suspend fun mergeRequestFigures(connectionId: UInt): CodeMergeRequestFigures = suspendTransaction(database) {
        suspend fun mergeRequests(extra: Op<Boolean>) =
            CodeMergeRequests.selectAll()
                .where { (CodeMergeRequests.connectionId eq connectionId) and CodeMergeRequests.deletedAt.isNull() and extra }.count()
        val perState = CodeMergeRequests.state.count()
        val byState = CodeMergeRequests.select(CodeMergeRequests.state, perState)
            .where { (CodeMergeRequests.connectionId eq connectionId) and CodeMergeRequests.deletedAt.isNull() }
            .groupBy(CodeMergeRequests.state).toList().associate { it[CodeMergeRequests.state] to it[perState] }
        CodeMergeRequestFigures(
            byState = byState,
            withDiffStats = mergeRequests(CodeMergeRequests.additions.isNotNull()),
            detailsPending = mergeRequests(CodeMergeRequests.detailsState eq DetailsState.PENDING.name),
            detailsUnreadable = mergeRequests(CodeMergeRequests.detailsState eq DetailsState.UNREADABLE.name),
        )
    }

    /** Events of the LIVE merge requests by kind, every [MrEventKind] present (zero-filled). */
    suspend fun eventCounts(connectionId: UInt): Map<MrEventKind, Long> {
        val found = suspendTransaction(database) {
            val rows: Flow<Pair<String, Long>?>? = exec(
                """
                SELECT e.kind, count(*)::bigint FROM norm.code_mr_events e
                JOIN norm.code_merge_requests m ON m.connection_id = e.connection_id AND m.mr_id = e.mr_id
                WHERE e.connection_id = $connectionId AND m.deleted_at IS NULL
                GROUP BY e.kind
                """.trimIndent(),
                explicitStatementType = StatementType.SELECT,
            ) { row -> row.get(0, String::class.java)!! to row.get(1, Long::class.javaObjectType)!! }
            rows?.toList()?.filterNotNull().orEmpty().toMap()
        }
        return MrEventKind.entries.associateWith { found[it.name] ?: 0L }
    }

    /** One statement: the identity table's own counts plus the live non-merge commits under an `e…` key (no join on an identity row). */
    suspend fun identityFigures(connectionId: UInt): CodeIdentityFigures = suspendTransaction(database) {
        val rows: Flow<CodeIdentityFigures?>? = exec(
            """
            SELECT
              count(*) FILTER (WHERE kind = 'USER' AND username IS NOT NULL AND deleted_at IS NULL),
              count(*) FILTER (WHERE kind = 'USER' AND username IS NOT NULL AND deleted_at IS NULL AND bot),
              count(*) FILTER (WHERE kind = 'USER' AND username IS NOT NULL AND deleted_at IS NOT NULL),
              count(*) FILTER (WHERE kind = 'USER' AND username IS NOT NULL AND deleted_at IS NULL AND jsonb_array_length(emails) > 0),
              count(*) FILTER (WHERE kind = 'USER' AND username IS NULL),
              count(*) FILTER (WHERE kind = 'EMAIL'),
              (SELECT count(*) FROM norm.code_commits c
               WHERE c.connection_id = $connectionId AND c.deleted_at IS NULL AND NOT c.is_merge AND c.identity_key LIKE 'e%')
            FROM norm.code_identities WHERE connection_id = $connectionId
            """.trimIndent(),
            explicitStatementType = StatementType.SELECT,
        ) { row ->
            fun at(index: Int) = row.get(index, Long::class.javaObjectType)!!
            CodeIdentityFigures(at(0), at(1), at(2), at(3), at(4), at(5), at(6))
        }
        rows?.toList()?.filterNotNull()?.single() ?: error("identityFigures: the aggregate returned no row")
    }

    suspend fun projectRows(connectionId: UInt): List<NormCodeProject> = suspendTransaction(database) {
        CodeProjects.selectAll().where { CodeProjects.connectionId eq connectionId }.orderBy(CodeProjects.projectId).toList().map {
            NormCodeProject(
                projectId = it[CodeProjects.projectId],
                pathWithNamespace = it[CodeProjects.pathWithNamespace],
                groupPath = it[CodeProjects.groupPath],
                defaultBranch = it[CodeProjects.defaultBranch],
                archived = it[CodeProjects.archived],
                movedOutAt = it[CodeProjects.movedOutAt],
                deletedAt = it[CodeProjects.deletedAt],
            )
        }
    }

    suspend fun identityRows(connectionId: UInt): List<NormCodeIdentity> = suspendTransaction(database) {
        CodeIdentities.selectAll().where { CodeIdentities.connectionId eq connectionId }.orderBy(CodeIdentities.identityKey).toList()
            .map(::identityOf)
    }

    /** The distinct identity keys of all of [connectionIds] (the orphan rule across one instance's sources); no statement for none. */
    suspend fun identityKeysOf(connectionIds: Collection<UInt>): Set<String> {
        if (connectionIds.isEmpty()) return emptySet()
        return suspendTransaction(database) {
            CodeIdentities.select(CodeIdentities.identityKey).withDistinct()
                .where { CodeIdentities.connectionId inList connectionIds.distinct() }
                .toList().mapTo(HashSet()) { it[CodeIdentities.identityKey] }
        }
    }

    /** One identity by its primary key, or null. */
    suspend fun identityRow(connectionId: UInt, key: String): NormCodeIdentity? = suspendTransaction(database) {
        CodeIdentities.selectAll()
            .where { (CodeIdentities.connectionId eq connectionId) and (CodeIdentities.identityKey eq key) }
            .toList().singleOrNull()?.let(::identityOf)
    }

    /**
     * The Jira people whose address is one of an identity's addresses (identity proposals): ONE statement with only the connection id in
     * its text. `norm.people.email` is stored RAW (`jira/JiraReferenceRows.kt`), so it is folded here (`LOWER(BTRIM(...))`) against the
     * identity's already canonical `emails`. People of ACTIVE connections only, the person's own `active` flag ignored (a departed
     * person keeps their history: the `displayNames` rule); the same account id on two connections is one match, named by the
     * alphabetically-first display name. Every identity is considered, ghosts and deleted users included.
     */
    suspend fun proposalMatches(connectionId: UInt): List<ProposalMatch> = suspendTransaction(database) {
        val rows: Flow<ProposalMatch?>? = exec(
            """
            SELECT i.identity_key, p.account_id, min(p.display_name)
            FROM norm.code_identities i
            JOIN norm.people p ON p.email IS NOT NULL AND LOWER(BTRIM(p.email)) IN (SELECT jsonb_array_elements_text(i.emails))
            JOIN public.source_connections c ON c.id = p.connection_id AND c.marked_as_deleted = false
            WHERE i.connection_id = $connectionId
            GROUP BY i.identity_key, p.account_id
            ORDER BY 1, 2
            """.trimIndent(),
            explicitStatementType = StatementType.SELECT,
        ) { row ->
            ProposalMatch(
                identityKey = row.get(0, String::class.java)!!,
                accountId = row.get(1, String::class.java)!!,
                displayName = row.get(2, String::class.java)!!,
            )
        }
        rows?.toList()?.filterNotNull().orEmpty()
    }

    private fun identityOf(row: ResultRow) = NormCodeIdentity(
        key = row[CodeIdentities.identityKey],
        kind = IdentityKind.valueOf(row[CodeIdentities.kind]),
        userId = row[CodeIdentities.userId],
        username = row[CodeIdentities.username],
        displayName = row[CodeIdentities.displayName],
        state = row[CodeIdentities.state],
        bot = row[CodeIdentities.bot],
        emails = parseStringArray(row[CodeIdentities.emails]),
        deletedAt = row[CodeIdentities.deletedAt],
    )

    suspend fun commitRows(connectionId: UInt, projectId: Long?): List<NormCodeCommit> = suspendTransaction(database) {
        CodeCommits.selectAll()
            .where { (CodeCommits.connectionId eq connectionId) and (projectId?.let { CodeCommits.projectId eq it } ?: Op.TRUE) }
            .orderBy(CodeCommits.projectId).orderBy(CodeCommits.sha).toList().map(::commitOf)
    }

    suspend fun mergeRequestRows(connectionId: UInt): List<NormCodeMergeRequest> = suspendTransaction(database) {
        CodeMergeRequests.selectAll().where { CodeMergeRequests.connectionId eq connectionId }.orderBy(CodeMergeRequests.mrId).toList()
            .map(::mergeRequestOf)
    }

    /** The events by merge request id, each list in `seq` order; one merge request's when [mrId] is given. */
    suspend fun mrEventRows(connectionId: UInt, mrId: Long?): Map<Long, List<NormCodeMrEvent>> = suspendTransaction(database) {
        CodeMrEvents.selectAll()
            .where { (CodeMrEvents.connectionId eq connectionId) and (mrId?.let { CodeMrEvents.mrId eq it } ?: Op.TRUE) }
            .orderBy(CodeMrEvents.mrId).orderBy(CodeMrEvents.seq).toList()
            .groupBy({ it[CodeMrEvents.mrId] }) {
                NormCodeMrEvent(
                    seq = it[CodeMrEvents.seq],
                    kind = MrEventKind.valueOf(it[CodeMrEvents.kind]),
                    actorKey = it[CodeMrEvents.actorKey],
                    eventAt = it[CodeMrEvents.eventAt],
                    targetKeys = it[CodeMrEvents.targetKeys]?.let(::parseStringArray).orEmpty(),
                )
            }
    }

    private fun commitOf(row: ResultRow) = NormCodeCommit(
        projectId = row[CodeCommits.projectId],
        sha = row[CodeCommits.sha],
        identityKey = row[CodeCommits.identityKey],
        title = row[CodeCommits.title],
        authoredAt = row[CodeCommits.authoredAt],
        committedAt = row[CodeCommits.committedAt],
        isMerge = row[CodeCommits.isMerge],
        additions = row[CodeCommits.additions],
        deletions = row[CodeCommits.deletions],
        files = row[CodeCommits.files],
        additionsCounted = row[CodeCommits.additionsCounted],
        deletionsCounted = row[CodeCommits.deletionsCounted],
        filesCounted = row[CodeCommits.filesCounted],
        filesCapped = row[CodeCommits.filesCapped],
        deletedAt = row[CodeCommits.deletedAt],
    )

    private fun mergeRequestOf(row: ResultRow) = NormCodeMergeRequest(
        mrId = row[CodeMergeRequests.mrId],
        projectId = row[CodeMergeRequests.projectId],
        iid = row[CodeMergeRequests.iid],
        state = row[CodeMergeRequests.state],
        draft = row[CodeMergeRequests.draft],
        authorKey = row[CodeMergeRequests.authorKey],
        mergeUserKey = row[CodeMergeRequests.mergeUserKey],
        createdAt = row[CodeMergeRequests.createdAt],
        updatedAt = row[CodeMergeRequests.updatedAt],
        mergedAt = row[CodeMergeRequests.mergedAt],
        closedAt = row[CodeMergeRequests.closedAt],
        sourceBranch = row[CodeMergeRequests.sourceBranch],
        targetBranch = row[CodeMergeRequests.targetBranch],
        title = row[CodeMergeRequests.title],
        headSha = row[CodeMergeRequests.headSha],
        mergeCommitSha = row[CodeMergeRequests.mergeCommitSha],
        squashCommitSha = row[CodeMergeRequests.squashCommitSha],
        reviewerKeys = parseStringArray(row[CodeMergeRequests.reviewerKeys]),
        assigneeKeys = parseStringArray(row[CodeMergeRequests.assigneeKeys]),
        additions = row[CodeMergeRequests.additions],
        deletions = row[CodeMergeRequests.deletions],
        fileCount = row[CodeMergeRequests.fileCount],
        notesCapped = row[CodeMergeRequests.notesCapped],
        detailsState = DetailsState.valueOf(row[CodeMergeRequests.detailsState]),
        deletedAt = row[CodeMergeRequests.deletedAt],
        events = emptyList(),
    )
}
