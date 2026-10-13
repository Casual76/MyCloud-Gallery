package dev.pampa.mycloudgallery.core.family.lifecycle

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.family.contract.LocalDataEraser
import dev.pampa.mycloudgallery.core.family.contract.SelfWipe
import dev.pampa.mycloudgallery.core.family.contract.TrustStateStore
import dev.pampa.mycloudgallery.core.family.contract.WipePhase
import dev.pampa.mycloudgallery.core.family.contract.WipeReason
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKeyVault
import dev.pampa.mycloudgallery.core.trust.crypto.KeyProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Cancella le credenziali del NAS (`CredentialVault.wipeAll`, adattato da `:app`): vero se e' andata. */
fun interface CredentialWiper {
  suspend fun wipeAll(): Boolean
}

/** Cancella i lavori di WorkManager dell'app (`:app`). */
fun interface WorkCanceller {
  suspend fun cancelAll()
}

/**
 * L'azzeramento di questo dispositivo (§2.3, C15, E-S2), nelle fasi di [WipePhase] **in quest'ordine**:
 * (0) segno nel [WipeJournal]; (1) chiave del dispositivo; (2) chiave di famiglia (cassaforte: sempre, e' un
 * no-op se non e' admin); (3) credenziali del NAS; (4) sessioni; (5) dati locali (prima lo stato fidato e gli
 * altri stati di [localState], poi il [LocalDataEraser] di `:app`); (6) lavori; (7) fatto.
 *
 * **Prima le chiavi, poi i dati**: senza chiave nessuno firma piu' a nome del dispositivo, e una copia dei dati
 * presa dopo non serve a impersonarlo. Una fase che fallisce (eccezione o `false`) ferma la corsa **senza
 * passare alle successive**: il diario resta all'ultima fase completata e [resumeIfInterrupted] (all'avvio) o
 * un nuovo [run] riprendono da li'. Tutte le fasi sono idempotenti.
 *
 * Unica conseguenza distruttiva voluta di un dato remoto (`docs/formato-nas.md` §9.6): la chiamano solo
 * `TrustGuard` e il motore della fiducia, e solo con lo stato `lost` in un registro **firmato e accettato** o
 * con una segnalazione valida nell'area privata dell'account (decisione 3(a)).
 */
class DeviceSelfWipe(
  private val journal: WipeJournal,
  private val keys: KeyProvider,
  private val familyKeys: FamilyKeyVault,
  private val credentials: CredentialWiper,
  private val sessions: NasSessionManager,
  private val trustState: TrustStateStore,
  private val eraser: LocalDataEraser,
  private val work: WorkCanceller,
  private val deviceClock: () -> Long,
  /** Altri stati locali di `core-family` da cancellare nella fase (5), prima dell'eraser (es. le richieste viste). */
  private val localState: List<suspend () -> Unit> = emptyList(),
) : SelfWipe {

  private val mutex = Mutex()
  private val _progress = MutableStateFlow<WipePhase?>(null)
  private val _record = MutableStateFlow<WipeRecord?>(null)

  override val progress: StateFlow<WipePhase?> = _progress.asStateFlow()

  /** Il diario completo: `finishedAt` e' l'ora di `SetupState.Wiped`. */
  val record: StateFlow<WipeRecord?> = _record.asStateFlow()

  /** Rilegge il diario (all'avvio, prima di decidere la prima schermata). */
  suspend fun load(): WipeRecord? = mutex.withLock { readJournal() }

  override suspend fun run(reason: WipeReason): WipePhase = mutex.withLock {
    var record = readJournal()
    if (record?.phase == WipePhase.DONE) return@withLock WipePhase.DONE
    if (record == null) {
      // (0) Il segno prima di tutto: se il processo muore da qui in poi, al riavvio si riprende.
      record = WipeRecord(reason, WipePhase.MARKED, deviceClock(), finishedAt = null)
      persist(record)
    }
    for (phase in WipePhase.entries.drop(record!!.phase.ordinal + 1)) {
      if (!perform(phase)) {
        GalleryLog.w(TAG) { "azzeramento fermo prima di ${phase.name}: si riprende al prossimo avvio" }
        return@withLock record!!.phase
      }
      record = record!!.copy(phase = phase, finishedAt = if (phase == WipePhase.DONE) deviceClock() else null)
      persist(record!!)
    }
    WipePhase.DONE
  }

  override suspend fun resumeIfInterrupted() {
    val record = load()
    if (record != null) {
      if (record.phase != WipePhase.DONE) run(record.reason)
      return
    }
    // Revisione 2, F7: il diario puo' mancare anche a meta' azzeramento (non si e' potuto scrivere: disco pieno, file
    // non scrivibile; [persist] va avanti lo stesso, perche' azzerare conta di piu'). Il segno che resta e' questo: la
    // chiave del dispositivo non c'e' piu' ma lo stato fidato si'. Nessun altro percorso toglie la chiave lasciando lo
    // stato (la fase 5 lo cancella), quindi e' un azzeramento interrotto: si finisce.
    if (!keys.hasDeviceKey() && hasTrustState()) {
      GalleryLog.w(TAG) { "chiave sparita con lo stato fidato ancora presente: si completa l'azzeramento" }
      run(WipeReason.MARKED_LOST)
    }
  }

  private suspend fun hasTrustState(): Boolean = try {
    trustState.load() != null
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    false
  }

  /**
   * Dopo un azzeramento finito, quando l'utente riconfigura il dispositivo da capo (P4): dimentica il diario,
   * cosi' lo stato torna "mai configurato". Non fa nulla se l'azzeramento non e' finito.
   */
  suspend fun forgetCompletedWipe(): Boolean = mutex.withLock {
    if (readJournal()?.phase != WipePhase.DONE) return@withLock false
    journal.clear()
    _record.value = null
    _progress.value = null
    true
  }

  private suspend fun readJournal(): WipeRecord? {
    val record = journal.read()
    _record.value = record
    _progress.value = record?.phase
    return record
  }

  /** Il diario si scrive sempre; se la scrittura fallisce si va avanti lo stesso (l'azzeramento conta di piu'). */
  private suspend fun persist(record: WipeRecord) {
    try {
      journal.write(record)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.e(TAG, e) { "diario dell'azzeramento non scritto" }
    }
    _record.value = record
    _progress.value = record.phase
  }

  private suspend fun perform(phase: WipePhase): Boolean = try {
    when (phase) {
      WipePhase.MARKED -> true
      WipePhase.DEVICE_KEY_DESTROYED -> {
        keys.destroyDeviceKey()
        !keys.hasDeviceKey()
      }
      WipePhase.FAMILY_KEY_DESTROYED -> {
        familyKeys.destroy()
        true
      }
      WipePhase.CREDENTIALS_WIPED -> credentials.wipeAll()
      WipePhase.SESSIONS_CLOSED -> {
        sessions.invalidate(InvalidateReason.WIPE)
        true
      }
      WipePhase.LOCAL_DATA_ERASED -> {
        trustState.clear()
        localState.forEach { it() }
        eraser.eraseAll()
      }
      WipePhase.WORK_CANCELLED -> {
        work.cancelAll()
        true
      }
      WipePhase.DONE -> true
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    GalleryLog.e(TAG, e) { "fase ${phase.name} non riuscita" }
    false
  }

  private companion object {
    const val TAG = "SelfWipe"
  }
}
