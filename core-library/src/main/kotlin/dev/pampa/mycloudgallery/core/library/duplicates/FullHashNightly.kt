package dev.pampa.mycloudgallery.core.library.duplicates

import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.WorkStage
import dev.pampa.mycloudgallery.core.model.WorkStatus
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeSource

// Fase 7, P8 (docs/fase7-progetto.md D27, E-U4; Q10 della Fase 4): l'HASH COMPLETO DEI VIDEO DEL NAS "PER TUTTI, DI NOTTE".
//
// Di serie l'attivita' `FULL_HASH` lavora solo dove decide qualcosa: un gruppo `PROBABLE_DUPLICATE` (stesso video su telefono e NAS) o la verifica
// "al sicuro" (446 GB di video sarebbero 8 ore di letture, Fase 4 D21). L'interruttore di Utility -> Doppioni (`full_hash_nightly`, spento di
// serie, impostazione personale sincronizzata) la estende a **tutti** i video del NAS senza hash completo: serve a trovare i doppioni identici
// dei video grandi (D25), che finche' non c'e' l'hash sono "da verificare".
//
// **Questa classe non legge nessun file.** Mette in coda (o toglie dalla coda) la fase `FULL_HASH` nel catalogo, con le stesse righe di lavoro
// che l'attivita' gia' consuma (`MediaCatalog.verifyCandidates(FullHash)`): la rilettura vera la fa il lavoro notturno esistente, con le sue regole
// dei video (attivita' pesante, Wi-Fi di casa) e i suoi cancelli. Ogni passo e' idempotente: se il NAS si spegne a meta' (E-U4) le righe gia' in
// coda restano e la notte dopo si riprende dal video successivo, senza rifare niente.

/** Il resoconto di un giro: soli numeri. */
class NightlyReport(val enabled: Boolean, val scanned: Int, val queued: Int, val withdrawn: Int, val finished: Boolean) {
  override fun toString(): String = "NightlyReport(enabled=$enabled, scanned=$scanned, queued=$queued, withdrawn=$withdrawn, finished=$finished)"
}

class FullHashNightly(
  private val catalog: MediaCatalog,
  private val visible: VisibleMedia,
  private val timeSource: TimeSource = TimeSource.Monotonic,
  private val batch: Int = BATCH,
) {

  init {
    require(batch > 0) { "Il lotto ha almeno un elemento." }
  }

  /**
   * Applica l'interruttore: acceso mette in coda i video del NAS senza hash completo; spento toglie dalla coda quelli messi per questo
   * (non quelli dei gruppi "da verificare", che il catalogo mette in coda da solo).
   */
  suspend fun apply(on: Boolean, budget: Duration): NightlyReport = if (on) enqueue(budget) else withdraw(budget)

  /** Mette in coda `FULL_HASH` per i video con una copia sul NAS e senza hash completo, al piu' per [budget]. */
  suspend fun enqueue(budget: Duration): NightlyReport {
    val started = timeSource.markNow()
    val snapshot = visible.snapshot.value ?: return NightlyReport(true, 0, 0, 0, finished = true)
    var scanned = 0
    var queued = 0
    try {
      val pending = ArrayList<MediaId>(batch)
      suspend fun flush() {
        if (pending.isNotEmpty()) queued += catalog.requestWork(pending, WorkStage.FULL_HASH)
        pending.clear()
      }
      for (i in 0 until snapshot.size) {
        if (!isCandidate(snapshot, i, includeToVerify = true)) continue
        if (scanned > 0 && started.elapsedNow() >= budget) {
          flush()
          return NightlyReport(true, scanned, queued, 0, finished = false)
        }
        scanned++
        val id = snapshot.idAt(i)
        val media = catalog.media(id) ?: continue
        if (media.currentVersion.fingerprint.fullHash != null) continue
        pending += id
        if (pending.size >= batch) flush()
      }
      flush()
      return NightlyReport(true, scanned, queued, 0, finished = true)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      // Quello che era gia' in coda resta in coda: la notte dopo si riprende.
      return NightlyReport(true, scanned, queued, 0, finished = false)
    }
  }

  /** Toglie dalla coda le fasi `FULL_HASH` ancora da fare dei video che non sono in un gruppo "da verificare". */
  suspend fun withdraw(budget: Duration): NightlyReport {
    val started = timeSource.markNow()
    val snapshot = visible.snapshot.value ?: return NightlyReport(false, 0, 0, 0, finished = true)
    var scanned = 0
    var withdrawn = 0
    try {
      for (i in 0 until snapshot.size) {
        if (!isCandidate(snapshot, i, includeToVerify = false)) continue
        if (scanned > 0 && started.elapsedNow() >= budget) return NightlyReport(false, scanned, 0, withdrawn, finished = false)
        scanned++
        val id = snapshot.idAt(i)
        for (state in catalog.workStates(id)) {
          if (state.stage == WorkStage.FULL_HASH && state.status == WorkStatus.PENDING) {
            catalog.updateWork(id, state.copy(status = WorkStatus.GAVE_UP))
            withdrawn++
          }
        }
      }
      return NightlyReport(false, scanned, 0, withdrawn, finished = true)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      return NightlyReport(false, scanned, 0, withdrawn, finished = false)
    }
  }

  /** Un video con una copia sul NAS e senza la verifica "al sicuro" (che l'hash completo ce l'ha gia'). */
  private fun isCandidate(snapshot: TimelineSnapshot, i: Int, includeToVerify: Boolean): Boolean {
    if (!snapshot.kindAt(i).isVideo) return false
    val flags = snapshot.flagsAt(i)
    if (!CellFlags.has(flags, CellFlags.ON_NAS) || CellFlags.has(flags, CellFlags.SAFE)) return false
    return includeToVerify || !CellFlags.has(flags, CellFlags.TO_VERIFY)
  }

  private companion object {
    const val BATCH = 100
  }
}
