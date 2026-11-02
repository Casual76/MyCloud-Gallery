package dev.pampa.mycloudgallery.release

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.release.contract.AppVersion
import dev.pampa.mycloudgallery.core.release.contract.ManifestLimits
import dev.pampa.mycloudgallery.core.release.contract.ManifestOrigin
import dev.pampa.mycloudgallery.core.release.contract.ManifestRead
import dev.pampa.mycloudgallery.core.release.contract.ManifestSource
import dev.pampa.mycloudgallery.core.release.contract.OfferResult
import dev.pampa.mycloudgallery.core.release.contract.ReleaseDiag
import dev.pampa.mycloudgallery.core.release.contract.ReleaseState
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStatus
import dev.pampa.mycloudgallery.core.release.keys.ReleaseTrust
import dev.pampa.mycloudgallery.core.release.state.NasFirstReleaseClock
import dev.pampa.mycloudgallery.core.release.state.ReleaseStates
import dev.pampa.mycloudgallery.core.release.state.offerRead
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// IL MANIFEST FIRMATO NELL'APP (Fase 10, P13; docs/fase10-progetto.md §1.4 "ReleaseGraph", §2.8, D29-D31, E-REL1-E-REL13; "Cosa deve fare P13" di P1 in
// docs/decisioni.md). :app vede solo lo **stato** (`ReleaseStatus`), gli indirizzi aggiuntivi gia' filtrati e i numeri della Diagnostica: il verificatore, le chiavi e i
// campi del manifest restano dentro core-release (Phase10ArchitectureTest, regola 3). Lo stato vive in `noBackupFilesDir/release/` (file atomico di core-release).

/**
 * Lo stato del manifest per questa versione dell'app, uno per processo. Lo costruisce `GraphModule` **prima** di `AppGraph` (la sessione del NAS e i cancelli lo
 * leggono); [load] si chiama all'avvio, fuori dal thread principale, prima che parta qualunque lavoro. Finche' non e' letto, i cancelli lo trattano come **sola
 * lettura** e la sessione come **ferma** (fallire chiusi: un blocco salvato da un processo di prima non si perde nei primi millisecondi).
 *
 * | Stato | Effetto (§2.8) |
 * |---|---|
 * | `Normal` | niente |
 * | `ReadOnly` | ogni cancello di scrittura e' fermo (backup, indice, album, modelli, dati personali, operazioni sui file) e la radice mostra "Aggiorna" |
 * | `Stopped` | come sopra, in piu' nessuna sessione del NAS (nessun lavoro, nessun traffico) e la radice mostra solo "Questa versione e' stata fermata" |
 */
class ReleaseGraph(
  private val dir: File,
  val version: AppVersion,
  /** Lo stato (in produzione quello di core-release su file, [create]; nelle prove uno finto). */
  val state: ReleaseState,
  /** L'ora del telefono per il ritmo delle letture (non per la scadenza: quella e' dell'ora del NAS, E-REL9). */
  private val wallNowMs: () -> Long = { System.currentTimeMillis() },
) {

  private val loaded = CompletableDeferred<Unit>()
  private val reading = Mutex()

  /** Le offerte di questo processo per esito (Diagnostica: `RELEASE_STATE`), solo numeri. */
  private val offers = EnumMap<OfferResult, AtomicInteger>(OfferResult::class.java).apply { OfferResult.entries.forEach { put(it, AtomicInteger()) } }

  val status: StateFlow<ReleaseStatus> get() = state.status

  val diag: StateFlow<ReleaseDiag> get() = state.diag

  /** Lo stato salvato e' stato letto (anche se il file non c'era o era rovinato: allora vale `Normal`, E-REL1). */
  val isLoaded: Boolean get() = loaded.isCompleted

  /** Le scritture sono ferme: `ReadOnly`, `Stopped`, o lo stato non ancora letto. */
  val writesStopped: Boolean get() = !isLoaded || status.value !is ReleaseStatus.Normal

  /** La versione e' fermata (`Stopped`, E-REL6), o lo stato non e' ancora letto: nessuna sessione del NAS. */
  val stopped: Boolean get() = !isLoaded || status.value is ReleaseStatus.Stopped

  /** All'avvio, una volta: legge lo stato salvato. Non lancia mai (un errore vale "niente salvato": la prima lettura del NAS rimette il blocco, L561). */
  suspend fun load() {
    if (isLoaded) return
    try {
      withContext(Dispatchers.IO) { state.load() }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "stato del manifest non letto (${e.javaClass.simpleName}): vale quello di serie" }
    } finally {
      loaded.complete(Unit)
    }
  }

  /** Aspetta [load] (le prove, e chi deve decidere su uno stato certo). */
  suspend fun awaitLoaded() = loaded.await()

  /** Gli indirizzi aggiuntivi di un modello (D29): solo da un manifest valido e non scaduto, per lo stesso id e la stessa impronta. `CatalogExtraModelSources` filtra ancora. */
  fun extraUrls(id: String, sha256: String): List<String> = try {
    state.extraUrls(id, sha256)
  } catch (e: RuntimeException) {
    emptyList()
  }

  /**
   * Una lettura del manifest dal NAS (§2.8 "Lettura"): al piu' una volta ogni 24 ore (`ManifestLimits.READ_EVERY`), salvo [force]. Si segna l'ora solo di una lettura
   * che ha avuto una risposta (trovato, assente, troppo grande): un NAS che non risponde si riprova al giro dopo. Mai un'eccezione verso il giro della sincronizzazione.
   */
  suspend fun readIfDue(source: ManifestSource, force: Boolean = false): OfferResult? = reading.withLock {
    if (!isLoaded) load()
    if (!force && !due()) return@withLock null
    val read = try {
      source.read(ManifestLimits.MAX_BYTES)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      ManifestRead.Unavailable
    }
    if (read == ManifestRead.Unavailable) return@withLock null
    markRead()
    val result = try {
      state.offerRead(read, ManifestOrigin.NAS)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "manifest non offerto (${e.javaClass.simpleName})" }
      null
    }
    result?.let { offers.getValue(it).incrementAndGet() }
    result
  }

  /** Le offerte di questo processo per esito (codice → numero), per la Diagnostica. */
  fun offerCounts(): Map<OfferResult, Int> = OfferResult.entries.associateWith { offers.getValue(it).get() }

  /** L'azzeramento (D42): lo stato torna `Normal` e il file sparisce (la prima lettura dopo rimette il blocco); anche l'ora dell'ultima lettura. */
  suspend fun clear() {
    state.clear()
    withContext(Dispatchers.IO) { readMark.delete() }
    offers.values.forEach { it.set(0) }
  }

  private val readMark = File(dir, READ_MARK)

  private suspend fun due(): Boolean = withContext(Dispatchers.IO) {
    val last = try {
      readMark.takeIf { it.isFile }?.readText(Charsets.US_ASCII)?.trim()?.toLongOrNull()
    } catch (e: IOException) {
      null
    } ?: return@withContext true
    val now = wallNowMs()
    // Un orologio tornato indietro non rimanda la lettura (al piu' una lettura in piu').
    now < last || now - last >= ManifestLimits.READ_EVERY.toMillis()
  }

  private suspend fun markRead() = withContext(Dispatchers.IO) {
    try {
      dir.mkdirs()
      val tmp = File(dir, "$READ_MARK.tmp")
      tmp.writeText(wallNowMs().toString(), Charsets.US_ASCII)
      if (!tmp.renameTo(readMark)) {
        readMark.delete()
        tmp.renameTo(readMark)
      }
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "ora della lettura del manifest non salvata" }
    }
  }

  companion object {
    private const val TAG = "Release"

    /** Il grafo vero: lo stato di core-release in [dir] (file atomico), con l'ora del NAS quando c'e' e la fiducia della variante. Nessun I/O. */
    fun create(
      dir: File,
      version: AppVersion,
      trust: ReleaseTrust,
      nasNow: () -> Instant?,
      phoneNow: () -> Instant = { Instant.now() },
    ): ReleaseGraph = ReleaseGraph(dir, version, ReleaseStates.create(ReleaseStates.fileStore(dir), version, NasFirstReleaseClock(nasNow, phoneNow), trust))

    /** La cartella in `noBackupFilesDir` (lo stato di core-release e l'ora dell'ultima lettura). L'azzeramento la toglie. */
    const val DIR = "release"

    /** L'ora (del telefono, millisecondi) dell'ultima lettura del NAS che ha avuto una risposta. */
    const val READ_MARK = "last-read"
  }
}
