package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserCreateRequest
import ch.nokillswit.users.UserPageResponse
import ch.nokillswit.users.UserResponse
import ch.nokillswit.users.UserRole
import ch.nokillswit.users.UserUpdateRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** The user-management surface: ADMIN-only CRUD, the wire roles set, and the two protections. */
class UserRoutesTest {

    private suspend fun ApplicationTestBuilder.adminClient(): Pair<HttpClient, UInt> {
        val email = uniqueEmail("mgradmin")
        val id = TestUsers.seed(email = email, password = "pw-123456789")
        return authedClient(email, "pw-123456789") to id
    }

    private fun createRequest(
        email: String,
        name: String = "New Person",
        roles: List<UserRole>? = null,
    ) = UserCreateRequest(name = name, email = email, password = "initial-pass-123", roles = roles)

    private suspend fun HttpClient.createUser(req: UserCreateRequest): UserResponse =
        post("/api/v1/users") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body()

    @Test
    fun `user management endpoints require authentication`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/users").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/users/1").status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/v1/users/1").status)
    }

    @Test
    fun `admin creates a user who can log in with the supplied password`() = testApplication {
        usePostgresTestcontainer()
        val (client, _) = adminClient()
        val email = uniqueEmail("created")

        val response = client.postJson("/api/v1/users", createRequest(email, name = "  Fresh Face  "))
        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.body<UserResponse>()
        assertEquals("Fresh Face", body.name, "single-line sanitization trims")
        assertEquals(email, body.email)
        assertEquals(emptyList(), body.roles)
        assertEquals("/api/v1/users/${body.id}", response.headers[HttpHeaders.Location])

        val login = jsonClient().postJson("/api/v1/login", LoginRequest(email, "initial-pass-123"))
        assertEquals(HttpStatusCode.OK, login.status)
    }

    @Test
    fun `emails are canonicalized on create and duplicates among active accounts are 409`() = testApplication {
        usePostgresTestcontainer()
        val (client, _) = adminClient()
        val email = uniqueEmail("canon")

        val created = client.createUser(createRequest("  ${email.uppercase()}  "))
        assertEquals(email, created.email)

        val duplicate = client.postJson("/api/v1/users", createRequest(email))
        assertEquals(HttpStatusCode.Conflict, duplicate.status)
        // The central 23505 handler names WHAT clashed (per-constraint detail, ErrorHandling.kt).
        assertEquals("A user with this email already exists", duplicate.body<ProblemDetail>().detail)

        // Soft-deleting frees the email for reuse (the V1 partial index).
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/users/${created.id}").status)
        val reused = client.postJson("/api/v1/users", createRequest(email))
        assertEquals(HttpStatusCode.Created, reused.status)
    }

    @Test
    fun `create validates name, email, and password rules`() = testApplication {
        usePostgresTestcontainer()
        val (client, _) = adminClient()

        suspend fun status(req: UserCreateRequest): HttpStatusCode = client.postJson("/api/v1/users", req).status

        assertEquals(HttpStatusCode.BadRequest, status(createRequest(uniqueEmail("v"), name = "   ")))
        assertEquals(HttpStatusCode.BadRequest, status(createRequest(uniqueEmail("v"), name = "x".repeat(51))))
        assertEquals(HttpStatusCode.BadRequest, status(createRequest("no-at-sign")))
        assertEquals(HttpStatusCode.BadRequest, status(createRequest(uniqueEmail("v")).copy(password = "short")))
        assertEquals(
            HttpStatusCode.BadRequest,
            status(createRequest(uniqueEmail("v"), name = "ctrl\u0007char")),
        )
    }

    @Test
    fun `the list is ADMIN-only and filters by name, email, and role`() = testApplication {
        usePostgresTestcontainer()
        val (client, _) = adminClient()
        val marker = UUID.randomUUID().toString().substring(0, 8)
        val zolw = client.createUser(createRequest(uniqueEmail("f1"), name = "Żółw $marker"))
        client.createUser(createRequest(uniqueEmail("f2"), name = "Zolw $marker Admin", roles = listOf(UserRole.ADMIN)))

        // Diacritics-insensitive name filter, both directions.
        val byName = client.get("/api/v1/users") {
            url.parameters.append("name", "zolw $marker")
            url.parameters.append("sort", "name")
        }.body<UserPageResponse>()
        assertEquals(2L, byName.total)
        val admins = client.get("/api/v1/users?name=$marker&role=ADMIN").body<UserPageResponse>()
        assertEquals(listOf(listOf(UserRole.ADMIN)), admins.items.map { it.roles })
        val plain = client.get("/api/v1/users?name=$marker&role=USER").body<UserPageResponse>()
        assertEquals(listOf(zolw.id), plain.items.map { it.id })
        val byEmail = client.get("/api/v1/users?email=${zolw.email}").body<UserPageResponse>()
        assertEquals(listOf(zolw.id), byEmail.items.map { it.id })
        // Unknown role and unknown sort field are clean 400s.
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/users?role=WIZARD").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/users?sort=roles").status)

        // A regular user gets a uniform 403.
        val userEmail = uniqueEmail("plainlist")
        TestUsers.seed(email = userEmail, password = "pw", role = UserRole.USER)
        val userClient = authedClient(userEmail, "pw")
        assertEquals(HttpStatusCode.Forbidden, userClient.get("/api/v1/users").status)
    }

    @Test
    fun `GET by id is guarded before the read`() = testApplication {
        usePostgresTestcontainer()
        val (client, _) = adminClient()
        val created = client.createUser(createRequest(uniqueEmail("readable")))

        val selfEmail = uniqueEmail("self")
        val selfId = TestUsers.seed(email = selfEmail, password = "pw", role = UserRole.USER)
        val selfClient = authedClient(selfEmail, "pw")

        assertEquals(HttpStatusCode.OK, client.get("/api/v1/users/${created.id}").status)
        assertEquals(HttpStatusCode.OK, selfClient.get("/api/v1/users/$selfId").status)
        // Another user's id AND a nonexistent id 403 identically (guard-before-read).
        assertEquals(HttpStatusCode.Forbidden, selfClient.get("/api/v1/users/${created.id}").status)
        assertEquals(HttpStatusCode.Forbidden, selfClient.get("/api/v1/users/999999").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/users/999999").status)
    }

    @Test
    fun `admin updates name, email, and roles - the password survives`() = testApplication {
        usePostgresTestcontainer()
        val (client, _) = adminClient()
        val email = uniqueEmail("editable")
        val created = client.createUser(createRequest(email))

        val newEmail = uniqueEmail("edited")
        val updated = client.putJson(
            "/api/v1/users/${created.id}",
            UserUpdateRequest(name = "Renamed Person", email = newEmail, roles = listOf(UserRole.ADMIN)),
        )
        assertEquals(HttpStatusCode.NoContent, updated.status)
        val fetched = client.get("/api/v1/users/${created.id}").body<UserResponse>()
        assertEquals("Renamed Person", fetched.name)
        assertEquals(newEmail, fetched.email)
        assertEquals(listOf(UserRole.ADMIN), fetched.roles)

        // The password endpoint owns passwords — the PUT must not have touched it.
        val login = jsonClient().postJson("/api/v1/login", LoginRequest(newEmail, "initial-pass-123"))
        assertEquals(HttpStatusCode.OK, login.status)

        // Non-admins cannot touch the management surface at all.
        val plainEmail = uniqueEmail("plainput")
        val plainId = TestUsers.seed(email = plainEmail, password = "pw", role = UserRole.USER)
        val plainClient = authedClient(plainEmail, "pw")
        val selfPut = plainClient.putJson(
            "/api/v1/users/$plainId",
            UserUpdateRequest(name = "Sneaky", email = plainEmail, roles = listOf(UserRole.ADMIN)),
        )
        assertEquals(HttpStatusCode.Forbidden, selfPut.status)
    }

    @Test
    fun `self-delete is forbidden even for an admin with peers`() = testApplication {
        usePostgresTestcontainer()
        val (client, adminId) = adminClient()
        val response = client.delete("/api/v1/users/$adminId")
        assertEquals(HttpStatusCode.Forbidden, response.status)
        // Still alive and functional.
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/users/$adminId").status)
    }

    @Test
    fun `the last active administrator can be neither deleted nor demoted`() = testApplication {
        usePostgresTestcontainer()
        val (client, actingAdminId) = adminClient()
        val soloEmail = uniqueEmail("solo")
        val solo = client.createUser(createRequest(soloEmail, roles = listOf(UserRole.ADMIN)))

        // With peers around, demote and delete work fine (proven at the end); the 409 branch
        // needs the target to be the FINAL active admin, so park every other admin.
        TestUsers.withSoloAdmins(setOf(solo.id, actingAdminId)) {
            // The acting admin still exists, so solo is not last yet — sanity check the count
            // logic by parking the actor too via a second nesting level is overkill; instead
            // demote the ACTOR first (allowed: solo remains), making solo the last admin.
            val demoteActor = client.putJson(
                "/api/v1/users/$actingAdminId",
                UserUpdateRequest(name = "Acting Admin", email = uniqueEmail("acting"), roles = emptyList()),
            )
            assertEquals(HttpStatusCode.NoContent, demoteActor.status)

            // solo is now the final active admin: demotion and deletion are both 409 …
            val demote = client.putJson(
                "/api/v1/users/${solo.id}",
                UserUpdateRequest(name = solo.name, email = soloEmail, roles = emptyList()),
            )
            assertEquals(HttpStatusCode.Conflict, demote.status)
            val delete = client.delete("/api/v1/users/${solo.id}")
            assertEquals(HttpStatusCode.Conflict, delete.status)

            // … until the actor is re-promoted, after which both succeed.
            val promoteActor = client.putJson(
                "/api/v1/users/$actingAdminId",
                UserUpdateRequest(name = "Acting Admin", email = uniqueEmail("acting2"), roles = listOf(UserRole.ADMIN)),
            )
            assertEquals(HttpStatusCode.NoContent, promoteActor.status)
            val demoteNow = client.putJson(
                "/api/v1/users/${solo.id}",
                UserUpdateRequest(name = solo.name, email = soloEmail, roles = emptyList()),
            )
            assertEquals(HttpStatusCode.NoContent, demoteNow.status)
            assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/users/${solo.id}").status)
        }
    }

    @Test
    fun `deleting a user removes them from the list and blocks their login`() = testApplication {
        usePostgresTestcontainer()
        val (client, _) = adminClient()
        val email = uniqueEmail("doomed")
        val created = client.createUser(createRequest(email))
        val filter = "email=$email"

        assertEquals(1L, client.get("/api/v1/users?$filter").body<UserPageResponse>().total)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/users/${created.id}").status)
        assertEquals(0L, client.get("/api/v1/users?$filter").body<UserPageResponse>().total)
        // Idempotent in effect: the second delete finds no active row.
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/users/${created.id}").status)
        val login = jsonClient().postJson("/api/v1/login", LoginRequest(email, "initial-pass-123"))
        assertEquals(HttpStatusCode.Unauthorized, login.status)
    }

    @Test
    fun `create and update also require authentication`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        val create = client.postJson("/api/v1/users", createRequest(uniqueEmail("anon")))
        assertEquals(HttpStatusCode.Unauthorized, create.status)
        val update = client.putJson(
            "/api/v1/users/1",
            UserUpdateRequest(name = "Anon", email = uniqueEmail("anon"), roles = emptyList()),
        )
        assertEquals(HttpStatusCode.Unauthorized, update.status)
    }

    @Test
    fun `a non-admin's malformed create body stays 403 - the guard runs before the body decodes`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("plainmgr")
            val response = client.post("/api/v1/users") {
                contentType(ContentType.Application.Json)
                setBody("{ this is not json")
            }
            // The documented guard-before-receive idiom: authorization is decided without
            // touching the payload, so the converter's 400 never outranks the 403.
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `update validates the payload and 404s a missing user`() = testApplication {
        usePostgresTestcontainer()
        val (client, _) = adminClient()
        val created = client.createUser(createRequest(uniqueEmail("updtgt")))

        val invalid = client.putJson(
            "/api/v1/users/${created.id}",
            UserUpdateRequest(name = "ctrl\u0007char", email = uniqueEmail("updbad"), roles = emptyList()),
        )
        assertEquals(HttpStatusCode.BadRequest, invalid.status)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/users/${created.id}").status)
        val gone = client.putJson(
            "/api/v1/users/${created.id}",
            UserUpdateRequest(name = "Valid Name", email = uniqueEmail("updgone"), roles = emptyList()),
        )
        assertEquals(HttpStatusCode.NotFound, gone.status)

        // Response order: the payload's 400 beats the 404 — validation runs before the read,
        // like the sibling handlers (create, password change).
        val invalidAndGone = client.putJson(
            "/api/v1/users/${created.id}",
            UserUpdateRequest(name = "   ", email = uniqueEmail("updbadgone"), roles = emptyList()),
        )
        assertEquals(HttpStatusCode.BadRequest, invalidAndGone.status)
    }
}
