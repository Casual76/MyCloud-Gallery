package dev.pampa.mycloudgallery.core.faces.erase

import dev.pampa.mycloudgallery.core.faces.contract.AckState
import dev.pampa.mycloudgallery.core.faces.contract.DeviceAck
import dev.pampa.mycloudgallery.core.faces.contract.EraseReport
import dev.pampa.mycloudgallery.core.faces.contract.EraseStart
import dev.pampa.mycloudgallery.core.faces.contract.EraseStatus
import dev.pampa.mycloudgallery.core.faces.contract.EraseStep
import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.contract.ErasePreview
import dev.pampa.mycloudgallery.core.faces.contract.FacesClock
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseConfirmation
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseOps
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraser
import dev.pampa.mycloudgallery.core.faces.contract.FacesLocal
import dev.pampa.mycloudgallery.core.faces.contract.FacesLocalStore
import dev.pampa.mycloudgallery.core.faces.contract.FacesRequirements
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.core.faces.contract.PeopleKey
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.contract.PrivateAreaState
import dev.pampa.mycloudgallery.core.faces.contract.SearchHistoryPurge
import dev.pampa.mycloudgallery.core.faces.contract.SmartAlbumsPeople
import dev.pampa.mycloudgallery.core.faces.contract.StepState
import dev.pampa.mycloudgallery.core.faces.switch.LocalEraseWhy
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.personal.contract.FacesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.personal.contract.PersonalHeadsView
import java.io.IOException
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Tutte le porte di "Disattiva e cancella" in un oggetto solo (come `FacesComputeParts`); le lega `:app` (P13). */
class FacesEraseParts(
  val journal: EraseJournalStore,
  val switch: FacesSwitch,
  val settings: FacesPersonalSettings,
  val localStore: FacesLocalStore,
  val requirements: FacesRequirements,
  val local: FacesLocalData,
  val fence: FacesEraseFence,
  val localErase: FacesLocalErase,
  val albums: SmartAlbumsPeople,
  val history: SearchHistoryPurge,
  val nas: FacesEraseOps,
  val announcer: EraseAnnouncer,
  val heads: PersonalHeadsView,
  val identity: FacesEraseIdentity,
  val clock: FacesClock,
  val stopWork: EraseStopWork = EraseStopWork.NONE,
) {
  override fun toString(): String = "FacesEraseParts"
}

/**
 * "DISATTIVA E CANCELLA" (Fase 8, P8; docs/fase8-progetto.md §2.8, D29-D31, D35, E-F30-E-F35). Un'azione **locale confermata** (la conferma la
 * costruisce solo il foglio di feature-people) che toglie **tutti** i dati dei volti dell'utente: la chiave per prima, i dati locali, sul NAS
 * l'intera cartella della generazione per tutti i dispositivi dell'account, i nomi, le ricerche recenti con i nomi; poi aspetta che ogni
 * dispositivo dell'account dica (con la sua testa personale) di aver cancellato.
 *
 * Il protocollo e' il diario ([EraseJournal], file `faces-erase` fuori dal database dei volti): il diario si scrive **prima** di tutto ([erase]) e
 * dopo ogni passo; una morte del processo riprende dal primo passo non segnato ([resume], il lavoro `FacesEraseWorker` di P13); ogni passo e'
 * idempotente. I passi 1-6 (diario, stop, album, cronologia, voce `off|G`, dati locali) non chiedono il NAS (E-F30: fuori casa si fanno subito);
 * 7-11 (annuncio, NAS, verifica, conferme, fine) quando il NAS c'e' e il cancello e' aperto (D35), con un motivo dichiarato quando aspettano. Con la
 * recinzione delle share vere (D34) si fanno solo i passi 1-6.
 *
 * Le regole di sicurezza non stanno qui: sul NAS cancella solo `FacesEraseOps` (core-nasops, D31) con i percorsi costruiti dalla regola; qui si
 * chiede per **generazione** e **motivo**, mai per percorso. Mai nomi, id, generazioni nei log (D37): questa classe non scrive log.
 */
class DefaultFacesEraser(
  private val parts: FacesEraseParts,
  /** Quanto resta il diario dopo la fine (per la pagina), in ora del NAS. */
  private val retentionMillis: Long = RETENTION_MILLIS,
) : FacesEraser {

  private val mutex = Mutex()
  private val state = MutableStateFlow<EraseStatus?>(null)
  override val status: StateFlow<EraseStatus?> = state.asStateFlow()

  @Volatile private var journal: EraseJournal? = null
  private var unreadable = false
  private var acks: List<DeviceAck> = emptyList()
  private var wait: EraseWait? = null

  init {
    when (val read = parts.journal.load()) {
      EraseJournalRead.Absent -> Unit
      is EraseJournalRead.Present -> journal = read.journal
      EraseJournalRead.Unreadable -> unreadable = true
    }
    // Un segno di cancellazione rimasto senza diario: la generazione resta chiusa finche' la ripresa non ricostruisce il diario.
    block()
    publish()
  }

  /** Le generazioni che la cancellazione in corso (o finita da meno di 30 giorni) nomina. */
  fun erasedGenerations(): List<FaceGeneration> = journal?.erased.orEmpty()

  override suspend fun preview(): ErasePreview {
    val generation = targetOf(parts.switch.currentState()) ?: return EMPTY_PREVIEW
    val records = attempt { parts.fence.exclusive { parts.local.people(generation) } }.orEmpty()
    val named = EraseRules.namedPeople(records.values)
    val albums = attempt { parts.albums.albumsWithPeople() } ?: 0
    val localOnly = localOnlyNow()
    val devices = if (localOnly) emptyList() else acksFor(listOf(generation), offAtNas = null)
    val historyWithNames = EraseRules.names(records.values).isNotEmpty()
    return ErasePreview(named, albums, historyWithNames, devices, localOnly, EraseRules.digest(generation, named, albums, historyWithNames, localOnly, devices))
  }

  override suspend fun erase(confirmation: FacesEraseConfirmation, albums: PeopleAlbumsChoice): EraseStart = mutex.withLock {
    if (!confirmation.isValidAt(parts.clock.monotonicNanos())) return@withLock EraseStart.Expired
    val existing = journal
    // La parte locale di una cancellazione in corso finisce prima: due cancellazioni non si sovrappongono sui dati locali.
    if (existing != null && !existing.done && !existing.localFinished) return@withLock EraseStart.AlreadyRunning
    val now = parts.switch.currentState()
    if (now is FacesState.Erasing) return@withLock EraseStart.AlreadyRunning
    val generation = targetOf(now) ?: return@withLock EraseStart.NothingToErase
    // E-F12: una voce che questa app non capisce non si sovrascrive (e non si cancella niente per lei).
    if (attempt { parts.settings.current() } == FacesSetting.Unknown) return@withLock EraseStart.NothingToErase
    if (existing != null && !existing.done && generation in existing.erased) return@withLock EraseStart.AlreadyRunning
    val preview = preview()
    if (preview.digest != confirmation.previewDigest) return@withLock EraseStart.PreviewChanged
    // Una cancellazione precedente ancora in attesa sul NAS o dei dispositivi si assorbe: le sue generazioni si tolgono con questa.
    val earlier = if (existing != null && !existing.done) existing.erased else emptyList()
    val started = EraseJournal(generation, earlier, albums, preview.digest, preview.localOnly)
    // Passo 1: il diario, prima di qualunque altra cosa. Senza diario non parte niente.
    if (!save(started)) return@withLock EraseStart.Unavailable
    wait = null
    acks = preview.devices
    runLocal(started)
    publish()
    EraseStart.Started
  }

  override suspend fun resume(budget: Duration): EraseReport = mutex.withLock {
    recover()
    var j = journal ?: return@withLock EraseReport(EraseStep.DONE, 0, null, done = true)
    val deadline = parts.clock.monotonicNanos() + budget.inWholeNanoseconds.coerceAtLeast(0)
    val removed = Counter()
    if (!j.done) {
      j = runLocal(j)
      if (j.localFinished) {
        j = if (j.localOnly) finishLocalOnly(j) else EraseRemote(this, parts, deadline, removed).run(j)
      }
    }
    if (j.done) housekeeping(j)
    publish()
    val current = journal
    EraseReport(current?.step ?: EraseStep.DONE, removed.files, state.value?.waiting, done = current?.done ?: true)
  }

  // --- passi 2-6: la parte locale -------------------------------------------------------------------------------------------------

  private suspend fun runLocal(start: EraseJournal): EraseJournal {
    var j = start
    // 2. Stop: il segno `erasing` (lo stato diventa `Erasing`: calcolo e pubblicazione si fermano da soli, P4 e P3) prima di toccare il negozio.
    if (!j.local) ensureMarker(j.generation)
    if (!j.stopped) {
      attempt { parts.stopWork.stop() }
      j = saved(j.copy(stopped = true)) ?: return j
    }
    // 3. Album: servono i dati dei volti, si fa prima di cancellarli. Tre tentativi, poi si va avanti (i dati biometrici non aspettano).
    if (j.albums == ErasePart.PENDING) {
      val ok = (1..ATTEMPTS).any { attempt { parts.albums.release(j.choice) } != null }
      j = saved(j.copy(albums = if (ok) ErasePart.DONE else ErasePart.SKIPPED)) ?: return j
    }
    // 4. Cronologia: i nomi noti in queste generazioni (anche quelli vecchi ancora nelle voci).
    if (j.history == ErasePart.PENDING) {
      val names = namesOf(j.erased)
      val ok = names != null && (1..ATTEMPTS).any { attempt { parts.history.purge(names) } != null }
      j = saved(j.copy(history = if (ok) ErasePart.DONE else ErasePart.SKIPPED)) ?: return j
    }
    // 5. La voce `faces = off|G` (vale subito, anche senza l'ora del NAS: "da datare").
    if (!j.setting) j = writeSetting(j)
    // 6. I dati locali: la chiave per prima, poi i file; tutte le generazioni che restano sul disco (l'account e' spento).
    if (!j.local && eraseLocalData(j)) j = saved(j.copy(local = true)) ?: return j
    if (j.local && (j.setting || attempt { parts.settings.current() } == FacesSetting.Unknown)) clearMarker(j)
    return j
  }

  /** La voce `off|G` nei dati personali. Non si riscrive una voce sconosciuta (E-F12) ne' una generazione nuova attivata da un altro (E-F9). */
  internal suspend fun writeSetting(j: EraseJournal): EraseJournal {
    val current = attempt { parts.settings.current() }
    val already = when (current) {
      is FacesSetting.On -> current.generation !in j.erased
      is FacesSetting.Off -> current.generation == j.generation
      FacesSetting.Unknown -> return j
      null -> false
    }
    if (!already && attempt { parts.settings.set(FacesSetting.Off(j.generation)) } != true) return j
    return saved(j.copy(setting = true)) ?: j
  }

  private suspend fun eraseLocalData(j: EraseJournal): Boolean = parts.fence.exclusive {
    val onDisk = attempt { parts.local.generationsOnDisk() } ?: return@exclusive false
    val keep = (attempt { parts.settings.current() } as? FacesSetting.On)?.generation?.takeIf { it !in j.erased }
    val victims = (j.erased + onDisk).distinct().filter { it != keep }
    var ok = true
    for (generation in victims) {
      if (!parts.localErase.eraseUnlocked(generation, LocalEraseWhy.ACCOUNT_OFF)) ok = false
    }
    ok
  }

  private suspend fun namesOf(generations: List<FaceGeneration>): Set<String>? {
    val names = HashSet<String>()
    for (generation in generations) {
      var records: Map<PeopleKey, PeopleRecord>? = null
      for (i in 1..ATTEMPTS) {
        records = attempt { parts.fence.exclusive { parts.local.people(generation) } }
        if (records != null) break
      }
      names += EraseRules.names((records ?: return null).values)
    }
    return names
  }

  private suspend fun ensureMarker(generation: FaceGeneration) {
    if (parts.localStore.local.value.erasing == generation) return
    attempt { parts.localStore.update { if (it.erasing == generation) it else it.copy(erasing = generation) } }
  }

  /** Fine della parte locale: il segno si toglie (e con lui l'esclusione: P7), ma solo se e' ancora quello di questa cancellazione. */
  private suspend fun clearMarker(j: EraseJournal) {
    val local = parts.localStore.local.value
    if (local.erasing == null || local.erasing !in j.erased) return
    attempt { parts.localStore.update { if (it.erasing != null && it.erasing in j.erased) FacesLocal.EMPTY else it } }
  }

  private suspend fun finishLocalOnly(j: EraseJournal): EraseJournal {
    if (!j.setting && attempt { parts.settings.current() } != FacesSetting.Unknown) return j
    return saved(j.copy(done = true, doneAtNas = parts.clock.nasNowMillis())) ?: j
  }

  // --- dopo la fine -------------------------------------------------------------------------------------------------------------

  private suspend fun housekeeping(start: EraseJournal) {
    var j = start
    val now = parts.clock.nasNowMillis()
    if (j.doneAtNas == null && now != null) j = saved(j.copy(doneAtNas = now)) ?: j
    // E-F34: finche' il cestino di rete ne tiene una copia la pagina lo dice; quando l'utente lo svuota il messaggio sparisce.
    if (j.recycleBin && !j.localOnly) {
      val checks = j.erased.map { attempt { parts.nas.verify(it) } }
      if (checks.all { it != null } && checks.none { it!!.recycleBinCopy }) j = saved(j.copy(recycleBin = false)) ?: j
    }
    val doneAt = j.doneAtNas
    if (now != null && doneAt != null && now - doneAt >= retentionMillis && attempt { parts.journal.clear() } == true) {
      journal = null
      acks = emptyList()
      wait = null
      block()
    }
  }

  /** Il diario illeggibile o sparito con il segno `erasing` ancora acceso: si ricostruisce (la scelta sugli album torna quella preselezionata). */
  private suspend fun recover() {
    if (journal != null) return
    // Un diario scritto ma non ricordato (la scrittura ha risposto con un errore dopo aver scritto): si rilegge.
    (attemptSync { parts.journal.load() } as? EraseJournalRead.Present)?.let { read ->
      journal = read.journal
      block()
      return
    }
    val marker = parts.localStore.local.value.erasing
    if (marker != null) {
      save(EraseJournal(marker, emptyList(), PeopleAlbumsChoice.KEEP_PHOTOS, digest = 0L, localOnly = localOnlyNow(), stopped = true))
    } else if (unreadable) {
      if (attempt { parts.journal.clear() } == true) unreadable = false
    }
  }

  // --- servizi per i passi ------------------------------------------------------------------------------------------------------

  internal fun current(): EraseJournal? = journal

  internal fun setWait(reason: EraseWait?) {
    wait = reason
  }

  internal fun setAcks(list: List<DeviceAck>) {
    acks = list
  }

  /** Il diario salvato e in memoria (e il recinto aggiornato), o null se il file non si e' scritto (si riprova alla ripresa dopo). */
  internal fun saved(next: EraseJournal): EraseJournal? = if (save(next)) next else null

  private fun save(next: EraseJournal): Boolean {
    val written = try {
      parts.journal.save(next)
      true
    } catch (e: IOException) {
      // L'errore puo' arrivare dopo la scrittura (la rinomina fatta, la risposta no): se il file dice gia' cio' che si voleva, e' fatto.
      (attemptSync { parts.journal.load() } as? EraseJournalRead.Present)?.journal == next
    }
    if (!written) return false
    journal = next
    unreadable = false
    block()
    publish()
    return true
  }

  private fun <T> attemptSync(block: () -> T): T? = try {
    block()
  } catch (e: Exception) {
    null
  }

  internal suspend fun acksFor(erased: List<FaceGeneration>, offAtNas: Long?): List<DeviceAck> {
    val registry = attempt { parts.identity.registry() } ?: return acks
    val heads = attempt { parts.heads.latestValueOf(FacesSettingCodec.KEY) }.orEmpty()
    return EraseRules.acks(registry, parts.identity.deviceId, heads, erased, offAtNas)
  }

  private fun block() {
    val marker = parts.localStore.local.value.erasing
    parts.fence.block(journal?.erased.orEmpty() + listOfNotNull(marker))
  }

  private suspend fun localOnlyNow(): Boolean = attempt {
    when (val area = parts.requirements.privateArea()) {
      is PrivateAreaState.Declared -> parts.requirements.localOnly(area.share)
      PrivateAreaState.Missing, PrivateAreaState.SameAsCommon -> true
    }
  } ?: true

  private fun publish() {
    val j = journal
    if (j == null) {
      state.value = null
      return
    }
    val local = when {
      j.local -> StepState.DONE
      j.stopped -> StepState.RUNNING
      else -> StepState.NOT_STARTED
    }
    val nas = when {
      j.localOnly -> StepState.NOT_NEEDED
      !j.local -> StepState.NOT_STARTED
      j.done || j.nasDone && j.verified -> StepState.DONE
      wait != null && wait != EraseWait.DEVICES -> StepState.WAITING
      else -> StepState.RUNNING
    }
    val waiting = when {
      j.done -> if (j.recycleBin) EraseWait.RECYCLE_BIN else null
      else -> wait ?: if (j.recycleBin) EraseWait.RECYCLE_BIN else null
    }
    val devices = if (j.localOnly) emptyList() else acks
    state.value = EraseStatus(j.generation, j.step, local, nas, devices, waiting)
  }

  internal fun publishStatus() = publish()

  internal class Counter {
    var files: Int = 0
  }

  internal suspend fun <T> attempt(block: suspend () -> T): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    null
  }

  override fun toString(): String = "DefaultFacesEraser"

  companion object {
    /** Il diario resta 30 giorni (ora del NAS) dopo la fine, per la pagina (§2.8 passo 11). */
    const val RETENTION_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

    /** Tentativi per un passo che si puo' saltare (album, cronologia) e per la lettura dei nomi. */
    internal const val ATTEMPTS = 3

    private val EMPTY_PREVIEW = ErasePreview(0, 0, historyWithNames = false, devices = emptyList(), localOnly = true, digest = 0L)

    /** La generazione che "Disattiva e cancella" toglie adesso: quella dell'account, accesa (anche esclusa qui) o in attesa dell'avviso. */
    internal fun targetOf(state: FacesState): FaceGeneration? = when (state) {
      is FacesState.On -> state.generation
      is FacesState.AwaitingNotice -> state.generation
      is FacesState.Erasing, FacesState.Off, FacesState.NeedsUpdate -> null
    }

    /** Vero se nessun dispositivo atteso manca piu'. */
    internal fun allConfirmed(acks: List<DeviceAck>): Boolean = acks.isNotEmpty() && acks.none { it.state == AckState.WAITING }
  }
}
