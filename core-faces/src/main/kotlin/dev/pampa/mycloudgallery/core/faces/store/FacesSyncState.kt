package dev.pampa.mycloudgallery.core.faces.store

import dev.pampa.mycloudgallery.core.database.faces.dao.FacesUnitDao
import dev.pampa.mycloudgallery.core.database.faces.entity.FacesUnitDoneEntity
import dev.pampa.mycloudgallery.core.faces.contract.AreaKind
import dev.pampa.mycloudgallery.core.faces.contract.FacesResource
import dev.pampa.mycloudgallery.core.model.enumByCodeOrNull

// IL LAVORO CHE LA GENERAZIONE SI PORTA DIETRO (docs/fase8-progetto.md D15: "unita' fatte"): un piccolo magazzino nello stesso database dei volti,
// che P4 (calcolo) usa senza nominare il database. Quando la generazione si cancella sparisce con lei. Mai valori nei log (D37).
//
// Fase 10, P12 (promessa 15 di docs/fase10-progetto.md): qui c'erano anche i segni delle teste (`FacesHeadMarks`, tabella `faces_head_mark`) e
// l'outbox (`FacesOutbox`, tabella `faces_outbox`) della P6 originale. Erano ridondanti con lo stato di P3 (`replica_device.mark_*`, le chiavi
// e il diario del pubblicatore) e nessun codice di produzione li ha mai usati: si sono tolti, con la migrazione 2 -> 3 del database dei volti
// (`FacesMigrations.MIGRATION_2_3`, mai distruttiva per altro).

/** Le unita' di lavoro finite (§2.3): non si rifanno finche' la ricetta non cresce. */
interface FacesUnitsDone {
  /** Segna [unit] finita con la [recipe] data; [doneAtNas] = l'ora del NAS, se la si sa. Idempotente. */
  suspend fun markDone(unit: FacesResource.WorkUnit, recipe: Int, doneAtNas: Long?)

  /** [unit] e' finita con la ricetta [recipe] o una piu' nuova? */
  suspend fun isDone(unit: FacesResource.WorkUnit, recipe: Int): Boolean

  /** Tutte le unita' finite con la ricetta [recipe] o piu' nuova. */
  suspend fun done(recipe: Int): Set<FacesResource.WorkUnit>
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
