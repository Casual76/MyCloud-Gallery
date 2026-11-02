package dev.pampa.mycloudgallery.release

import dev.pampa.mycloudgallery.core.backup.contract.BackupSummary
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpace
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpaceState
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.feature.diagnostics.release.StepArea
import dev.pampa.mycloudgallery.feature.diagnostics.release.StepCertainty
import dev.pampa.mycloudgallery.feature.diagnostics.release.StepFolder
import dev.pampa.mycloudgallery.feature.diagnostics.release.StepForecast
import dev.pampa.mycloudgallery.feature.diagnostics.release.StepIdle
import dev.pampa.mycloudgallery.feature.diagnostics.release.StepPlace
import dev.pampa.mycloudgallery.feature.diagnostics.release.StepSources
import dev.pampa.mycloudgallery.feature.diagnostics.release.StepSpace
import dev.pampa.mycloudgallery.feature.diagnostics.release.StepUnavailable
import dev.pampa.mycloudgallery.feature.diagnostics.release.StepWrite

// La "Simulazione del passo" nell'app (Fase 10 P10, docs/fase10-progetto.md §2.10): da dove arrivano i numeri delle quattro aree. SOLA LETTURA: questo
// file non nomina nessuna API di scrittura del NAS (`StepSimulationReadOnlyTest` lo controlla sul sorgente); legge dal catalogo locale, dal
// piano del backup e dallo spazio libero (un'interrogazione).

/**
 * I numeri dell'indice: quanti media del NAS il catalogo conosce, per area. Con D31 spenta il dispositivo pubblica l'indice di tutto quello
 * che il catalogo sa di una share (segmenti, teste, prenotazioni): [bytesPerRecord] viene dalla stima della replica (il livello piu' grande),
 * null = la regola di serie.
 */
data class StepIndexFacts(val privateRecords: Long, val commonRecords: Long, val bytesPerRecord: Long?)

/** I numeri dei volti: se Persone e' attiva qui e quanti media ha la Libreria (telefono e NAS), da cui si stima. */
data class StepFacesFacts(val activeHere: Boolean, val mediaCount: Long)

/** Le due share del passo, per ruolo (mai mostrate: il resoconto dice solo "area privata" e "area comune"). */
data class StepShares(val privateShare: String, val commonShare: String?)

/** Lo spazio libero **per l'account** di una share, o null se non si e' letto. Una sola interrogazione; nessuna scrittura. */
fun interface StepNasSpace {
  suspend fun freeBytes(share: String): Long?
}

object StepNasSpaces {
  /** Sopra lo spazio del NAS di `core-nasops` (la stessa pagina "Spazio NAS" delle Impostazioni): il minore fra disco e quota dell'account. */
  fun over(space: NasSpace): StepNasSpace = StepNasSpace { share ->
    (space.load(share) as? NasSpaceState.Ready)?.details?.let { it.quotaBytes ?: it.freeBytes }
  }
}

/**
 * I numeri di ogni area dalle fonti vere, in sola lettura. Funzioni e non oggetti: ogni lettura e' una porta stretta e un test mette i suoi numeri.
 * Una fonte che non c'e' (backup non montato, NAS non pronto) da' null e l'area dice "numeri non disponibili": il passo non parte (E-SV1).
 */
class LiveStepSources(
  private val backup: suspend () -> BackupSummary?,
  private val index: suspend () -> StepIndexFacts?,
  private val faces: suspend () -> StepFacesFacts?,
  private val shares: () -> StepShares?,
  private val nasSpace: StepNasSpace,
) : StepSources {

  override suspend fun forecast(area: StepArea): StepForecast = when (area) {
    StepArea.BACKUP -> StepForecasts.backup(backup())
    StepArea.INDEX -> StepForecasts.index(index())
    StepArea.FACES -> StepForecasts.faces(faces())
    // Gli album condivisi scrivono solo quando l'utente condivide un album (F7-D30): niente al passo, "3 foto di prova" la prima volta.
    StepArea.ALBUMS -> StepForecast.Idle(StepArea.ALBUMS, StepIdle.ON_USER_ACTION)
  }

  override suspend fun space(): StepSpace? {
    val s = shares() ?: return null
    val names = listOfNotNull(s.privateShare, s.commonShare).distinctBy { it.lowercase() }
    val free = names.map { nasSpace.freeBytes(it) ?: return null }
    return StepSpace(free.min())
  }
}

/** Dai fatti alle previsioni: funzioni pure, con le regole di stima scritte qui e in `docs/decisioni.md` ("Fase 10 P10"). */
object StepForecasts {

  /** Byte per voce dell'indice quando la replica non da' una stima: ~380 MB per 13 mila media a livello Standard (docs/formato-nas.md). */
  const val DEFAULT_INDEX_BYTES_PER_RECORD: Long = 32L * 1024

  /** Un corpo `faces` con vettore, riquadro e punteggio: circa 700 byte; ~1,5 volti per media come tetto di stima. */
  const val FACE_BYTES: Long = 700
  const val FACES_PER_MEDIA_X10: Long = 15

  /** Backup: i numeri del piano (`BackupSummary.toUpload`): contati; stimati finche' il confronto con le copie esistenti non e' chiuso. */
  fun backup(summary: BackupSummary?): StepForecast {
    if (summary == null) return StepForecast.Unavailable(StepArea.BACKUP, StepUnavailable.NOT_READY)
    val upload = summary.toUpload
    if (upload.items <= 0) return StepForecast.Idle(StepArea.BACKUP, StepIdle.NOTHING_TO_DO)
    val certainty = if (summary.comparing) StepCertainty.ESTIMATE else StepCertainty.EXACT
    return StepForecast.Writes(StepArea.BACKUP, certainty, listOf(StepWrite(StepPlace.PRIVATE_AREA, StepFolder.BACKUP, upload.items.toLong(), upload.bytes)))
  }

  /**
   * Indice e dati personali: per ogni area con media nel catalogo, i segmenti (almeno uno ogni [TrustLimits]`.MAX_SEGMENT_ENTRIES` voci o
   * `SEGMENT_TARGET_BYTES`) piu' una testa. Stima: la stima della replica per voce, non un numero contato.
   */
  fun index(facts: StepIndexFacts?): StepForecast {
    if (facts == null) return StepForecast.Unavailable(StepArea.INDEX, StepUnavailable.NOT_READY)
    val perRecord = facts.bytesPerRecord?.takeIf { it > 0 } ?: DEFAULT_INDEX_BYTES_PER_RECORD
    val writes = listOf(StepPlace.PRIVATE_AREA to facts.privateRecords, StepPlace.COMMON_AREA to facts.commonRecords)
      .filter { it.second > 0 }
      .map { (place, records) ->
        val bytes = records * perRecord
        val bySize = (bytes + TrustLimits.SEGMENT_TARGET_BYTES - 1) / TrustLimits.SEGMENT_TARGET_BYTES
        val byEntries = (records + TrustLimits.MAX_SEGMENT_ENTRIES - 1) / TrustLimits.MAX_SEGMENT_ENTRIES
        StepWrite(place, StepFolder.APP_FOLDER, files = maxOf(bySize, byEntries) + 1, bytes = bytes)
      }
    if (writes.isEmpty()) return StepForecast.Idle(StepArea.INDEX, StepIdle.NOTHING_TO_DO)
    return StepForecast.Writes(StepArea.INDEX, StepCertainty.ESTIMATE, writes)
  }

  /** Volti: solo se Persone e' attiva qui; stima per eccesso dai media della Libreria (volti per media x byte per volto). */
  fun faces(facts: StepFacesFacts?): StepForecast {
    if (facts == null) return StepForecast.Unavailable(StepArea.FACES, StepUnavailable.NOT_READY)
    if (!facts.activeHere) return StepForecast.Idle(StepArea.FACES, StepIdle.FEATURE_OFF)
    val faces = facts.mediaCount * FACES_PER_MEDIA_X10 / 10
    if (faces <= 0) return StepForecast.Idle(StepArea.FACES, StepIdle.NOTHING_TO_DO)
    val files = (faces + TrustLimits.MAX_SEGMENT_ENTRIES - 1) / TrustLimits.MAX_SEGMENT_ENTRIES + 1
    return StepForecast.Writes(StepArea.FACES, StepCertainty.ESTIMATE, listOf(StepWrite(StepPlace.PRIVATE_AREA, StepFolder.APP_FOLDER, files, faces * FACE_BYTES)))
  }
}
