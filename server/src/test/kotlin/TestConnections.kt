package ch.nokillswit

import java.util.UUID

/**
 * The ONE Jira site every test data source names (`.claude/docs/test-fixtures.md` "Data-source scope rules"): the
 * running app enforces "one Jira site across every active data source" (`ingest/DataSourceScope.kt`), and the test
 * container's `source_connections` table is shared by every class in a fork, so a random site per test would make the
 * next HTTP create a 409. A well-formed tenant label that is not reserved (`JIRA_SITE_URL_PATTERN`).
 */
const val TEST_JIRA_SITE_URL = "https://flow-test.atlassian.net"

/**
 * A project key no other test (and no stub project) shares: `U` + 8 uppercase hex digits, inside `PROJECT_KEY_PATTERN`.
 * Every data source created over HTTP (the ENFORCED app) takes its keys from here, because "no project in two active data
 * sources" is also a rule of the shared table; only the direct [SyncedStubFixture.dataSources] service skips it.
 */
fun uniqueProjectKey(): String = "U" + UUID.randomUUID().toString().replace("-", "").take(8).uppercase()

fun uniqueProjectKeys(count: Int): List<String> = List(count) { uniqueProjectKey() }
