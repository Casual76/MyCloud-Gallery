package dev.pampa.mycloudgallery.core.faces.store

import dev.pampa.mycloudgallery.core.database.faces.dao.FaceBodyDao
import dev.pampa.mycloudgallery.core.database.faces.dao.FacesHeadMarkDao
import dev.pampa.mycloudgallery.core.database.faces.dao.FacesOutboxDao
import dev.pampa.mycloudgallery.core.database.faces.dao.FacesUnitDao
import dev.pampa.mycloudgallery.core.database.faces.entity.FacesHeadMarkEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.FacesOutboxEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.FacesUnitDoneEntity
import dev.pampa.mycloudgallery.core.faces.contract.AreaKind
import dev.pampa.mycloudgallery.core.faces.contract.FacesChange
import dev.pampa.mycloudgallery.core.faces.contract.FacesResource
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.model.enumByCodeOrNull

// IL LAVORO CHE LA GENERAZIONE SI PORTA DIETRO (docs/fase8-progetto.md D15: "segni anti-rollback, outbox, unita' fatte"): tre piccoli
// magazzini nello stesso database dei volti, che P3 (pubblicazione e replica) e P4 (calcolo) usano senza nominare il database. Quando la
// generazione si cancella spariscono con lei ("la sua outbox dei volti sparisce con il database", §2.8). Mai valori nei log (D37).

/** L'ultima testa accettata di un dispositivo dell'account (`HeadMark`, §2.4): anti-rollback ed equivocazione. */
data class HeadMark(
  val deviceId: String,
  val version: Long,
  val contentHash: String,
  val registryVersion: Long,
  val createdAt: Long,
) {
  override fun toString(): String = "HeadMark"
}

/**
 * I segni delle teste. [record] non abbassa mai il numero: una testa piu' vecchia di quella gia' segnata non si registra (risponde falso), una
 * uguale o piu' nuova si'. Cosa fare di un numero uguale con un'altra impronta (equivocazione) lo decide chi sincronizza (P3).
 */
interface FacesHeadMarks {
  suspend fun get(deviceId: String): HeadMark?

  suspend fun all(): List<HeadMark>

  suspend fun record(mark: HeadMark): Boolean

  suspend fun forget(deviceId: String)
}

/** Che cosa aspetta di essere pubblicato (`FacesChange` ridotto a un riferimento: la voce vera si rilegge dall'archivio al momento di pubblicare). */
enum class OutboxKind(override val code: String) : StableCode {
  /** Il corpo `faces` di una chiave di contenuto (`ref` = la chiave). */
  FACES("faces"),

  /** L'istantanea del raggruppamento (`ref` = la `seq`). */
  SNAPSHOT("snapshot"),

  /** Lo stato unito delle voci `people` (`ref` vuoto): si ripubblica intero, come F4-D26. */
  PEOPLE("people"),
}

/** Una voce dell'outbox. [stamp] cresce a ogni messa in coda: [FacesOutbox.acknowledge] toglie solo cio' che e' stato letto. */
class OutboxEntry(val kind: OutboxKind, val ref: String, val stamp: Long) {
  override fun toString(): String = "OutboxEntry(${kind.code})"
}

/**
 * L'outbox della pubblicazione, **durevole** (sopravvive alla morte del processo). Mettere in coda due volte la stessa cosa e' una voce sola
 * (e le da' un [OutboxEntry.stamp] nuovo: chi aveva gia' letto la vecchia non toglie la nuova).
 */
interface FacesOutbox {
  suspend fun stage(kind: OutboxKind, ref: String)

  /** Le voci in coda, dalla piu' vecchia (al piu' [limit]). */
  suspend fun pending(limit: Int = DEFAULT_LIMIT): List<OutboxEntry>

  suspend fun count(): Int

  /** Toglie le voci [entries] (per [OutboxEntry.stamp]); una rimessa in coda nel frattempo resta. */
  suspend fun acknowledge(entries: Collection<OutboxEntry>)

  companion object {
    const val DEFAULT_LIMIT: Int = 1_000
  }
}

/** Mette in coda un [FacesChange] (`FacesPublisher.stage`): le voci `people` si riducono a "ripubblica lo stato unito". */
suspend fun FacesOutbox.stage(change: FacesChange) = when (change) {
  is FacesChange.Faces -> stage(OutboxKind.FACES, change.contentKey.hex)
  is FacesChange.Snapshot -> stage(OutboxKind.SNAPSHOT, change.seq.toString())
  is FacesChange.People -> stage(OutboxKind.PEOPLE, "")
}

/** Le unita' di lavoro finite (§2.3): non si rifanno finche' la ricetta non cresce. */
interface FacesUnitsDone {
  /** Segna [unit] finita con la [recipe] data; [doneAtNas] = l'ora del NAS, se la si sa. Idempotente. */
  suspend fun markDone(unit: FacesResource.WorkUnit, recipe: Int, doneAtNas: Long?)

  /** [unit] e' finita con la ricetta [recipe] o una piu' nuova? */
  suspend fun isDone(unit: FacesResource.WorkUnit, recipe: Int): Boolean

  /** Tutte le unita' finite con la ricetta [recipe] o piu' nuova. */
  suspend fun done(recipe: Int): Set<FacesResource.WorkUnit>
}

internal class DatabaseHeadMarks(private val dao: FacesHeadMarkDao) : FacesHeadMarks {
  override suspend fun get(deviceId: String): HeadMark? = dao.get(deviceId)?.toMark()

  override suspend fun all(): List<HeadMark> = dao.all().map { it.toMark() }

  override suspend fun record(mark: HeadMark): Boolean {
    val current = dao.get(mark.deviceId)
    if (current != null && mark.version < current.version) return false
    dao.upsert(FacesHeadMarkEntity(mark.deviceId, mark.version, mark.contentHash, mark.registryVersion, mark.createdAt))
    return true
  }

  override suspend fun forget(deviceId: String) = dao.remove(deviceId)

  private fun FacesHeadMarkEntity.toMark() = HeadMark(deviceId, version, contentHash, registryVersion, createdAt)
}

internal class DatabaseOutbox(private val dao: FacesOutboxDao) : FacesOutbox {
  override suspend fun stage(kind: OutboxKind, ref: String) {
    dao.stage(FacesOutboxEntity(kind = kind.code, ref = ref))
  }

  override suspend fun pending(limit: Int): List<OutboxEntry> {
    require(limit > 0) { "Il tetto deve essere positivo." }
    return dao.pending(limit).mapNotNull { row ->
      // Un tipo che questa versione non conosce (scritto da un'app piu' nuova) resta li' e non si pubblica.
      enumByCodeOrNull<OutboxKind>(row.kind)?.let { OutboxEntry(it, row.ref, row.entryId) }
    }
  }

  override suspend fun count(): Int = dao.count()

  override suspend fun acknowledge(entries: Collection<OutboxEntry>) {
    entries.map { it.stamp }.distinct().chunked(FaceBodyDao.IN_LIMIT).forEach { dao.remove(it) }
  }
}

internal class DatabaseUnitsDone(private val dao: FacesUnitDao) : FacesUnitsDone {
  override suspend fun markDone(unit: FacesResource.WorkUnit, recipe: Int, doneAtNas: Long?) {
    require(recipe in 1..255) { "Ricetta fuori intervallo." }
    val current = dao.get(unit.area.code, unit.month, unit.part)
    // Una ricetta piu' vecchia non abbassa quella che c'e': "fatta con la 3" non torna "fatta con la 2".
    if (current != null && current.recipe > recipe) return
    dao.upsert(FacesUnitDoneEntity(unit.area.code, unit.month, unit.part, recipe, doneAtNas))
  }

  override suspend fun isDone(unit: FacesResource.WorkUnit, recipe: Int): Boolean =
    (dao.get(unit.area.code, unit.month, unit.part)?.recipe ?: 0) >= recipe

  override suspend fun done(recipe: Int): Set<FacesResource.WorkUnit> = dao.doneWith(recipe).mapNotNullTo(LinkedHashSet()) { row ->
    val area = enumByCodeOrNull<AreaKind>(row.area) ?: return@mapNotNullTo null
    try {
      FacesResource.WorkUnit(area, row.month, row.part)
    } catch (_: IllegalArgumentException) {
      null
    }
  }
}

/** Una chiave di contenuto messa in coda per la pubblicazione (comodita' per `compute/`). */
suspend fun FacesOutbox.stageFaces(contentKey: ContentKey) = stage(OutboxKind.FACES, contentKey.hex)
