package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.integration.ServiceTokenCreateRequest
import ch.nokillswit.integration.ServiceTokenCreateResponse
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The service-token surface is a structural allow-list (`.claude/docs/security.md` "Service tokens"), swept from the SPEC like
 * `AnonymousAccessTest`: exactly the operations declaring `serviceToken` accept a token, every other secured operation answers 401 to a
 * VALID one (the JWT provider fails it before any lookup), and `IntegrationRoutes.kt` holds only GETs.
 */
class ServiceTokenSurfaceTest {

    private val integrationSurface = setOf("GET /api/v1/integration/me", "GET /api/v1/integration/teams")

    private val secured by lazy {
        OpenApiSpec.parsed.paths.flatMap { (path, item) ->
            item.readOperationsMap().filter { (_, op) -> op.security?.isEmpty() != true }.map { (method, op) -> Triple(path, method, op) }
        }
    }

    private fun declaresServiceToken(op: io.swagger.v3.oas.models.Operation) = op.security.orEmpty().any { "serviceToken" in it.keys }

    /** The source without comment lines, so the explanatory KDoc may name the forbidden calls. */
    private fun codeOf(source: String) = source.lines()
        .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") || it.trimStart().startsWith("//") }
        .joinToString("\n")

    @Test
    fun `the operations declaring the serviceToken scheme are exactly the integration reads`() {
        val declared = secured.filter { (_, _, op) -> declaresServiceToken(op) }.map { (path, method, _) -> "${method.name} $path" }.toSet()
        assertEquals(integrationSurface, declared)
        // A service-token operation never also accepts the JWT, and no operation outside /integration names the scheme.
        val tokenOps = secured.filter { (_, _, op) -> declaresServiceToken(op) }
        assertTrue(tokenOps.all { (_, _, op) -> op.security.all { "bearerAuth" !in it.keys } })
    }

    @Test
    fun `every other secured operation answers 401 to a valid service token`() = testApplication {
        usePostgresTestcontainer()
        val secret = seededClient("st-surface", FlowUserRole.ADMIN)
            .postJson("/api/v1/service-tokens", ServiceTokenCreateRequest("surface-${java.util.UUID.randomUUID()}"))
            .body<ServiceTokenCreateResponse>().secret
        val client = jsonClient()
        val others = secured.filterNot { (_, _, op) -> declaresServiceToken(op) }
        assertTrue(others.size >= MIN_SWEPT_OPERATIONS, "the sweep covers ${others.size} operations - the spec shrank?")
        val leaks = mutableListOf<String>()
        for ((path, method, op) in others) {
            val httpMethod = HttpMethod.parse(method.name)
            val response = client.request(path.replace(Regex("\\{[^}]+}"), "1")) {
                this.method = httpMethod
                header(HttpHeaders.Authorization, "Bearer $secret")
                if (httpMethod in setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch)) {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            }
            if (response.status != HttpStatusCode.Unauthorized) leaks += "${method.name} $path (${op.operationId}) -> ${response.status}"
        }
        assertEquals(emptyList(), leaks, "operations a service token can reach")
    }

    @Test
    fun `IntegrationRoutes holds only GETs, no body is ever read`() {
        val source = File(System.getProperty("repo.root") ?: "..", "server/src/main/kotlin/integration/IntegrationRoutes.kt").readText()
        val code = codeOf(source)
        assertTrue("get<" in code, "the read route is there")
        for (forbidden in listOf("post<", "put<", "delete<", "patch<", "receive(", "receiveText", "receiveNullable", "receiveParameters")) {
            assertTrue(forbidden !in code, "IntegrationRoutes.kt must not contain $forbidden")
        }
    }

    @Test
    fun `an authenticate call naming the service-token provider appears in no main source but IntegrationRoutes`() {
        val root = File(System.getProperty("repo.root") ?: "..", "server/src/main/kotlin")
        val users = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { guardedBlock.containsMatchIn(codeOf(it.readText())) }.map { it.name }.toList()
        assertEquals(listOf("IntegrationRoutes.kt"), users)
    }

    private companion object {
        /** Any `authenticate(` call whose arguments (any position, any qualification) name the provider by constant or by literal. */
        private val guardedBlock = Regex("""authenticate\([^)]*(SERVICE_TOKEN|"service-token")""")

        /** A tripwire like `AnonymousAccessTest`'s floor: the sweep covers every secured operation, this only catches a shrunken spec. */
        const val MIN_SWEPT_OPERATIONS = 80
    }
}
