package ch.nokillswit.gitlab

import ch.nokillswit.ingest.GitLabCommitsProfile
import ch.nokillswit.ingest.GitLabDataProfile
import ch.nokillswit.ingest.GitLabEventsProfile
import ch.nokillswit.ingest.GitLabIdentitiesProfile
import ch.nokillswit.ingest.GitLabMergeRequestsProfile
import ch.nokillswit.ingest.GitLabProjectsProfile
import ch.nokillswit.ingest.percentOfRounded
import ch.nokillswit.norm.CodeActivityStore

/**
 * The GitLab data profile (`ingest/GitLabDataProfile.kt`): facts about what the code layer holds, from ~20 database-side aggregates
 * (`CodeActivityStore`'s figures, plus the two raw counts the norm layer cannot know: the COMMIT FILES backlog and the terminal diff
 * errors). Facts only: no person, name, address, title or path leaves this function.
 */
internal object GitLabProfile {
    suspend fun compute(
        connectionId: UInt,
        rawStore: GitLabRawStore,
        processStore: GitLabRawProcessStore,
        codeStore: CodeActivityStore,
    ): GitLabDataProfile {
        val projects = codeStore.projectCounts(connectionId)
        val commits = codeStore.commitFigures(connectionId)
        val mergeRequests = codeStore.mergeRequestFigures(connectionId)
        val identities = codeStore.identityFigures(connectionId)
        return GitLabDataProfile(
            projects = GitLabProjectsProfile(projects.live, projects.movedOut, projects.deleted, projects.archived),
            commits = GitLabCommitsProfile(
                count = commits.count,
                merges = commits.merges,
                earliestCommittedAt = commits.earliestCommittedAt,
                latestCommittedAt = commits.latestCommittedAt,
                filesPending = rawStore.countPendingCommitFiles(connectionId),
                filesUnavailable = processStore.countFilesUnavailable(connectionId),
                filesCapped = commits.filesCapped,
                commitsWithCounts = commits.commitsWithCounts,
            ),
            mergeRequests = GitLabMergeRequestsProfile(
                byState = mergeRequests.byState,
                withDiffStats = mergeRequests.withDiffStats,
                withDiffStatsPercent = percentOfRounded(mergeRequests.withDiffStats, mergeRequests.byState.values.sum()),
                detailsPending = mergeRequests.detailsPending,
                detailsUnreadable = mergeRequests.detailsUnreadable,
                withIssueKeyCandidates = mergeRequests.withIssueKeyCandidates,
                issueKeyCandidates = mergeRequests.issueKeyCandidates,
            ),
            events = GitLabEventsProfile(codeStore.eventCounts(connectionId).mapKeys { it.key.name }),
            identities = GitLabIdentitiesProfile(
                users = identities.users,
                bots = identities.bots,
                usersDeleted = identities.usersDeleted,
                usersWithEmail = identities.usersWithEmail,
                usersWithEmailPercent = percentOfRounded(identities.usersWithEmail, identities.users),
                ghosts = identities.ghosts,
                emailOnly = identities.emailOnly,
                commitsUnmatched = identities.commitsUnmatched,
            ),
        )
    }
}
