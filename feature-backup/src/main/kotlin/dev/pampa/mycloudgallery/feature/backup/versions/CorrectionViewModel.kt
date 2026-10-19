package dev.pampa.mycloudgallery.feature.backup.versions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionFailure
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionOutcome
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionPlan
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionReadiness
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionRequest
import dev.pampa.mycloudgallery.core.backup.contract.Corrections
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmation
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationCheck
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.PhoneWriteGrant
import dev.pampa.mycloudgallery.core.backup.contract.VersionKeeper
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataChange
import dev.pampa.mycloudgallery.core.model.ExifOrientation
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.feature.backup.contract.BackupActions
import dev.pampa.mycloudgallery.feature.backup.free.DialogAnswer
import dev.pampa.mycloudgallery.feature.backup.free.DialogBridge
import dev.pampa.mycloudgallery.feature.backup.free.NoPhoneSystemDialogs
import dev.pampa.mycloudgallery.feature.backup.free.PhoneSystemDialogs
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Fase 5B (P12, docs/fase5-progetto.md §1.5 `CorrectionState`, §2.4 "Correzioni dall'app", D19, E-L8, E-E8, E-E9, Q6, L40; contratto `Corrections`
// di P0-B, logica di P8): "Correggi data e luogo" e "Ruota". Il ViewModel **non decide niente**: traduce cio' che l'utente scrive in una richiesta
// del contratto, chiede il piano (che dice se si puo' fare e se serve il permesso del telefono) e passa la conferma nata dal tocco. Un media del
// telefono si corregge solo se e' al sicuro (`NeedsSafeFirst`: niente letto ne' scritto); la riga del telefono si riscrive solo con il permesso del
// dialogo del sistema; un formato che non si scrive nel file (HEIC, RAW, video) resta corretto solo nell'app. Niente scrive sul NAS da qui.

@HiltViewModel
class CorrectionViewModel @Inject constructor(
  corrections: Optional<Corrections>,
  keeper: Optional<VersionKeeper>,
  dialogs: Optional<PhoneSystemDialogs>,
  clock: Optional<BackupClock>,
  places: Optional<CorrectionPlaces>,
  backup: Optional<BackupActions>,
) : ViewModel() {

  private val service: Corrections? = corrections.orElse(null)
  private val versions: VersionKeeper? = keeper.orElse(null)
  private val systemDialogs: PhoneSystemDialogs = dialogs.orElse(NoPhoneSystemDialogs)
  private val monotonic: BackupClock? = clock.orElse(null)
  private val finder: CorrectionPlaces? = places.orElse(null)
  private val backupActions: BackupActions? = backup.orElse(null)

  /** Il ponte verso il dialogo del sistema: la schermata lo collega (`BindDialogBridge`). */
  internal val bridge = DialogBridge()

  private val flow = MutableStateFlow<CorrectionUi>(initial())

  internal val state: StateFlow<CorrectionUi> = flow

  private var subject: CorrectionSubject? = null
  private var mode: CorrectionMode = CorrectionMode.ALL
  private var job: Job? = null
  private var planJob: Job? = null
  private var searchJob: Job? = null

  private fun initial(): CorrectionUi = if (service == null || monotonic == null) CorrectionUi.Unavailable else CorrectionUi.Loading

  /** L'orologio **monotono** con cui il foglio data i tocchi di conferma (lo stesso con cui il servizio li controlla). */
  fun monotonicMs(): Long = monotonic?.monotonicMs() ?: 0L

  /** Apre il foglio per [subject]. Chiamarla di nuovo per lo stesso media non rifa' niente. */
  fun bind(subject: CorrectionSubject, mode: CorrectionMode) {
    if (service == null || monotonic == null) return
    if (this.subject?.mediaId == subject.mediaId && this.mode == mode) return
    this.subject = subject
    this.mode = mode
    flow.value = CorrectionUi.Editing(
      subject = subject,
      mode = mode,
      draft = CorrectionDraft.startingFrom(subject),
      errors = emptySet(),
      plan = null,
      canSearchPlaces = finder != null,
    )
    probe(subject)
  }

  /** Chiude il foglio: niente resta in giro (il prossimo `bind` riparte da capo). */
  fun reset() {
    job?.cancel()
    planJob?.cancel()
    searchJob?.cancel()
    subject = null
    flow.value = initial()
  }

  /**
   * Prima che l'utente scriva niente: la correzione si puo' fare? Il piano dipende dal media (al sicuro, area, formato, NAS), non da cosa si
   * cambia, quindi una richiesta qualunque lo dice. Cosi' un foglio che non puo' funzionare lo dice subito, non dopo aver scritto la data.
   */
  private fun probe(subject: CorrectionSubject) {
    val corrections = service ?: return
    planJob?.cancel()
    planJob = viewModelScope.launch {
      val plan = corrections.plan(subject.mediaId, CorrectionRequest(MetadataChange(orientation = ExifOrientation.ROTATE_90)))
      flow.update { current ->
        if (current is CorrectionUi.Editing && current.subject.mediaId == subject.mediaId) route(plan.readiness, subject, current) else current
      }
    }
  }

  /** Dove porta una prontezza: la correzione prosegue (il foglio si scrive), o si ferma con la sua ragione. */
  private fun route(readiness: CorrectionReadiness, subject: CorrectionSubject, current: CorrectionUi.Editing): CorrectionUi = when (readiness) {
    CorrectionReadiness.READY, CorrectionReadiness.FORMAT_NOT_WRITABLE -> current.copy(readiness = readiness)
    CorrectionReadiness.NEEDS_SAFE_FIRST -> CorrectionUi.NeedsSafeFirst(subject)
    else -> CorrectionUi.Blocked(readiness)
  }

  /** L'utente cambia la bozza (un campo, una scelta): si ricalcola la richiesta e, se vale, il piano. */
  fun edit(change: (CorrectionDraft) -> CorrectionDraft) {
    val editing = flow.value as? CorrectionUi.Editing ?: return
    val draft = change(editing.draft)
    updateDraft(editing, draft)
  }

  private fun updateDraft(editing: CorrectionUi.Editing, draft: CorrectionDraft, message: CorrectionMessage? = null) {
    val corrections = service ?: return
    val result = draft.build(editing.subject, editing.mode)
    // "Niente da correggere" non e' un errore da mostrare: il tasto resta spento e basta.
    flow.value = editing.copy(draft = draft, errors = result.errors - DraftError.NOTHING_TO_DO, plan = null, message = message)
    planJob?.cancel()
    val request = result.request ?: return
    planJob = viewModelScope.launch {
      val plan = corrections.plan(editing.subject.mediaId, request)
      flow.update { current ->
        if (current is CorrectionUi.Editing && current.draft == draft) route(plan.readiness, editing.subject, current.copy(plan = plan)) else current
      }
    }
  }

  /** L'utente cerca un luogo per nome: i risultati restano finche' non cambia il testo. */
  fun searchPlaces(text: String) {
    val searcher = finder ?: return
    edit { it.copy(placeQuery = text, chosenPlace = null) }
    searchJob?.cancel()
    if (text.isBlank()) {
      flow.update { if (it is CorrectionUi.Editing) it.copy(places = emptyList()) else it }
      return
    }
    searchJob = viewModelScope.launch {
      val found = searcher.search(text).take(MAX_PLACES)
      flow.update { current -> if (current is CorrectionUi.Editing && current.draft.placeQuery == text) current.copy(places = found) else current }
    }
  }

  /** Il luogo scelto fra i risultati. */
  fun choosePlace(choice: PlaceChoice) = edit { it.copy(chosenPlace = choice, coordinatesText = "") }

  /** "Metti al sicuro adesso": parte il backup (lo fa `:app`); poi si riapre il foglio. */
  fun safeguardNow() {
    backupActions?.backupNow()
  }

  /**
   * Il tocco su "Correggi": [confirmation] e' nata dal foglio. Il servizio la controlla contro il piano ricalcolato adesso. Per un media del
   * telefono serve il permesso del sistema: se l'utente lo nega, **non si tocca niente** e si torna a scrivere.
   */
  fun apply(confirmation: PhoneConfirmation) {
    val corrections = service ?: return
    val editing = flow.value as? CorrectionUi.Editing ?: return
    val plan = editing.plan ?: return
    job?.cancel()
    job = viewModelScope.launch {
      flow.value = CorrectionUi.Writing
      var outcome = corrections.apply(plan, confirmation, null)
      if (outcome is CorrectionOutcome.NeedsWriteGrant) {
        val rows = outcome.rows
        flow.value = CorrectionUi.AwaitingSystemDialog(rows.size)
        when (askWrite(rows)) {
          DialogAnswer.ALLOWED -> outcome = corrections.apply(plan, confirmation, PhoneWriteGrant(rows.toSet()))
          DialogAnswer.REFUSED -> return@launch backToEditing(editing, CorrectionMessage.PERMISSION_REFUSED)
          DialogAnswer.NOT_AVAILABLE -> return@launch backToEditing(editing, CorrectionMessage.NO_SYSTEM_DIALOG)
        }
      }
      when (val result = outcome) {
        is CorrectionOutcome.Done -> uploading(plan, result.version)
        CorrectionOutcome.NeedsSafeFirst -> flow.value = CorrectionUi.NeedsSafeFirst(editing.subject)
        CorrectionOutcome.FormatNotWritable -> flow.value = CorrectionUi.Done(onlyInApp = true)
        is CorrectionOutcome.Rejected -> backToEditing(
          editing,
          if (result.check == PhoneConfirmationCheck.Expired) CorrectionMessage.CONFIRMATION_EXPIRED else CorrectionMessage.PLAN_CHANGED,
        )
        CorrectionOutcome.PlanChanged -> backToEditing(editing, CorrectionMessage.PLAN_CHANGED)
        is CorrectionOutcome.Unavailable -> flow.value = CorrectionUi.Blocked(result.reason)
        // Il permesso c'e' gia' e il servizio lo chiede ancora: qualcosa non torna, niente e' stato toccato.
        is CorrectionOutcome.NeedsWriteGrant -> flow.value = CorrectionUi.Failed(CorrectionFailure.PHONE_WRITE_FAILED)
        is CorrectionOutcome.Failed -> flow.value = CorrectionUi.Failed(result.reason)
      }
    }
  }

  private fun backToEditing(editing: CorrectionUi.Editing, message: CorrectionMessage) {
    flow.value = editing.copy(plan = null, message = message)
    updateDraft(editing, editing.draft, message)
  }

  /** La correzione e' nel diario: il backup la porta sul NAS come versione. Finche' non e' al sicuro il foglio dice "si sta caricando". */
  private suspend fun uploading(plan: CorrectionPlan, version: VersionId) {
    flow.value = CorrectionUi.Uploading(onlyInApp = false)
    val keeper = versions ?: return
    repeat(MAX_POLLS) {
      delay(POLL_MS)
      val safe = keeper.plan(plan.mediaId).versions.firstOrNull { it.versionId == version }?.safe == true
      if (safe) {
        flow.value = CorrectionUi.Done(onlyInApp = false)
        return
      }
    }
  }

  private suspend fun askWrite(rows: List<PhoneRef>): DialogAnswer {
    val sender = systemDialogs.writeRequest(rows) ?: return DialogAnswer.NOT_AVAILABLE
    return bridge.ask(sender)
  }

  private companion object {
    const val MAX_PLACES = 5

    /** Ogni quanto si guarda se la versione corretta e' arrivata sul NAS e al sicuro. */
    const val POLL_MS = 3_000L

    /** Quanto si aspetta (con il foglio aperto): cinque minuti. Oltre, il foglio dice che il backup continua da solo. */
    const val MAX_POLLS = 100
  }
}
