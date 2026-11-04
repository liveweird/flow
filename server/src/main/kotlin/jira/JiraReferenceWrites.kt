package ch.nokillswit.jira

import ch.nokillswit.ingest.RawUpsertOutcome
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.ingest.isChange
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * One REFERENCE page's entities into `raw.jira_entities` (inside the caller's transaction), with the progress counters. Returns
 * whether the page CHANGED anything - any outcome but [RawUpsertOutcome.UNCHANGED] or [RawUpsertOutcome.REFUSED] (an entity over the
 * stored-size ceiling, counted `entitiesSkipped`) - which is what the page's heartbeat reports as progress ([StreamContext.heartbeat]):
 * a daily pass over unchanged reference data re-stores identical pages and is no progress.
 */
internal suspend fun JiraRawStore.storeEntities(
    context: StreamContext,
    step: JiraEntityKind,
    elements: JsonArray,
    idOf: (JsonObject) -> String = { it.requireIdText(step.name) },
): Boolean {
    val entities = elements.map { RawEntityInput(idOf(it.requireObjectElement(step.name, "an entity")), it) }
    val outcomes = upsertEntities(context.connectionId, step.name, entities, context.clock())
    val refused = context.countRefused("entitiesSkipped", outcomes)
    context.incrementProgress("entities", (elements.size - refused).toLong())
    context.incrementProgress("pages")
    return outcomes.any { it.isChange }
}
