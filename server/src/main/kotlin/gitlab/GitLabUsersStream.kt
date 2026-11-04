package ch.nokillswit.gitlab

import ch.nokillswit.fetch.FetchException
import ch.nokillswit.fetch.RunBudget
import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.ingest.isChange
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(GitLabUsersStream::class.java)

private const val USERS_ENDPOINT = "/api/v4/users"
private const val FORBIDDEN = 403

/**
 * The USERS stream (`gitlab-integration.md` "Streams"): the instance's users into `raw.gitlab_users`, hash-diffed. With an ADMIN token
 * each user's confirmed secondary emails come from `/users/:id/emails` (the first [MAX_EMAILS_PER_USER]); a 403 there flips the rest
 * of the run to "no emails" (the token turned out not to be an admin), a 404 skips that user's emails only. Without an admin token
 * the emails are the list's own `email`/`commit_email`/`public_email`, which GitLab shows to admins alone, and the run says so once.
 * Either way a user's `emails` is the sorted, distinct, lowercased union ([gitLabUserEmails]) and is part of the hashed payload.
 *
 * Users change rarely and the stream is the bulk of a routine run's calls, so it is skipped while every live user row was seen within
 * [usersRefreshMillis] (`gitlab.usersRefreshHours`; "Sync now" passes 0): `usersSkipped`, no sweep, so a user it never fetched is not
 * marked deleted. A pass is resumable like PROJECTS (cursor: pass start and next page) and ends with the users not seen during it
 * marked deleted. Progress: `pages`, `users`, `usersEmailsSkipped`, `usersSkipped`.
 */
internal class GitLabUsersStream(
    private val client: GitLabClient,
    private val rawStore: GitLabRawStore,
    private val isAdmin: Boolean,
    private val usersRefreshMillis: Long,
    private val ceilings: GitLabCeilings = GitLabCeilings(),
) : Stream {
    override val name: String = "users"

    /** Whether this run may still ask for secondary emails; an admin token that is refused once is not asked again. */
    private var canReadEmails = isAdmin

    override suspend fun run(context: StreamContext) {
        val resumed = context.cursor(name)?.cursor?.let { decodeCursor<UsersCursor>(it) }
        if (resumed == null && usersAreFresh(context)) {
            context.incrementProgress("usersSkipped")
            context.heartbeat(changed = false) // the last stream of the run: flush the counter, and the lease and the clock are checked
            return
        }
        val passStartedAt = resumed?.passStartedAt ?: context.clock()
        if (!isAdmin) log.warn("The GitLab token's owner is not an admin: user emails are limited to what the user list shows")
        val budget = RunBudget(GITLAB_SOURCE, USERS_ENDPOINT, ceilings.users, "users")
        var page = resumed?.page ?: 1
        while (true) {
            budget.nextPage()
            val answer = client.users(page)
            budget.add(answer.users.size)
            val users = answer.users.map { toUser(context, it) }
            val nextPage = answer.nextPage
            val changed = context.transaction {
                val outcomes = rawStore.upsertUsers(context.connectionId, users, context.clock())
                context.incrementProgress("pages")
                context.incrementProgress("users", users.size.toLong())
                context.putCursor(name, encodeCursor(UsersCursor(passStartedAt, nextPage ?: page + 1)))
                outcomes.any { it.isChange }
            }
            context.heartbeat(changed)
            page = nextPage ?: break
        }
        context.transaction {
            rawStore.markUsersDeletedNotSeenSince(context.connectionId, passStartedAt, context.clock())
            context.clearCursor(name)
        }
    }

    /** Every live user row was seen within [usersRefreshMillis]; never with the refresh off (0) or no user stored yet. */
    private suspend fun usersAreFresh(context: StreamContext): Boolean {
        if (usersRefreshMillis <= 0) return false
        val oldest = rawStore.oldestLiveUserSeenAt(context.connectionId) ?: return false
        return context.clock() - oldest < usersRefreshMillis
    }

    /** The raw row of [summary] (already slim) with its merged emails; fetching the secondary ones parses only a small answer. */
    private suspend fun toUser(context: StreamContext, summary: GitLabUserSummary): RawGitLabUser = RawGitLabUser(
        userId = summary.id,
        username = summary.username,
        name = summary.name,
        state = summary.state,
        bot = summary.bot,
        emails = mergeEmails(summary.ownEmails, secondaryEmailsOf(context, summary.id)),
        payload = summary.slim,
    )

    /** The user's confirmed secondary emails, or none when the token may not read them (counted in `usersEmailsSkipped`). */
    private suspend fun secondaryEmailsOf(context: StreamContext, userId: Long): List<String> {
        if (!canReadEmails) {
            context.incrementProgress("usersEmailsSkipped")
            return emptyList()
        }
        return try {
            client.userEmails(userId)
        } catch (failure: FetchException) {
            when {
                failure.status == FORBIDDEN -> canReadEmails = false
                failure.code == "NOT_FOUND" -> Unit
                else -> throw failure
            }
            context.incrementProgress("usersEmailsSkipped")
            emptyList()
        }
    }
}
