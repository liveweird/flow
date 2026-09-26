package ch.nokillswit.infra.db

import ch.nokillswit.auth.TokenBlocklistService
import ch.nokillswit.auth.TokenBlocklistServiceKey
import ch.nokillswit.teams.TeamService
import ch.nokillswit.teams.TeamServiceKey
import ch.nokillswit.users.UserService
import ch.nokillswit.users.UserServiceKey
import io.ktor.server.application.*
import io.ktor.util.AttributeKey
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

/** The connected database itself — read by the readiness probe (plugins/Health.kt); services get it injected. */
val R2dbcDatabaseKey = AttributeKey<R2dbcDatabase>("R2dbcDatabase")

/**
 * The DI composition root: connects the one R2DBC database and publishes every service into
 * [Application.attributes]. Feature modules read their services back via the AttributeKey —
 * application.yaml runs this module before any route module, so the keys are always present.
 */
suspend fun Application.configureDatabase() {
    val database = R2dbcDatabase.connect(
        url = environment.config.property("postgres.r2dbcUrl").getString(),
        user = environment.config.property("postgres.user").getString(),
        password = environment.config.property("postgres.password").getString(),
    )
    val userService = UserService(database)
    attributes.put(R2dbcDatabaseKey, database)
    attributes.put(UserServiceKey, userService)
    val teamService = TeamService(database)
    attributes.put(TeamServiceKey, teamService)
    attributes.put(TokenBlocklistServiceKey, TokenBlocklistService(database))
}
