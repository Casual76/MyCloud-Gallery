package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.ai.contract.AiDiag
import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.AiWaitReason
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisWait
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage

/**
 * La riduzione dello stato dell'analisi (Pacchetto X, 12/11/2026), gemella di `BackupStatusReducers`: una funzione pura che dai fatti
 * (ruolo, stato del motore, lavoro che resta, copertura, verdetto delle regole di adesso) ricava l'**unico** stato che testata,
 * pannello della nuvola e pagina "Analisi delle foto" mostrano, ognuno con le sue parole e una volta sola.
 *
 * I casi sono quelli veri: sta analizzando, in coda, in pausa **con il motivo vero** (telefono caldo, carica, batteria, risparmio
 * energetico, fascia, rete, NAS, tetto dati, anteprime in preparazione: dal verdetto del motore delle regole, lo stesso dei lavori e
 * della Diagnostica, mai "attesa delle regole"), tutto pronto, modello da scaricare, analisi spenta, questo dispositivo non analizza,
 * un problema con la sua azione. I numeri sono solo quelli noti: un totale che non si sa resta null.
 */
object AnalysisStatusReducers {

  /** I fatti da cui lo stato si ricava: li raccoglie `AiGraph` (eventi del motore e rilettura rada, mai a ogni fotogramma). */
  class Facts(
    /** Il ruolo Indicizzatore di questo dispositivo (D15). */
    val indexer: Boolean,
    /** Lo stato del motore (`AiGraph.status`, con la memoria dell'ultima tappa). */
    val status: AiStatus,
    /** Il lavoro che resta a questo dispositivo (foto + video); null = non ancora letto o senza NAS. */
    val remaining: Int?,
    /** La copertura della ricerca (le foto gia' pronte), per i numeri dell'anello. */
    val coverage: SearchCoverage,
    /** Il verdetto delle regole per il calcolo in questo momento; null = non valutato. */
    val decision: RuleDecision? = null,
  )

  fun reduce(facts: Facts): LibraryAnalysisStatus {
    val (ready, total) = readyTotal(facts.coverage)
    if (!facts.indexer) return LibraryAnalysisStatus.NotHere(ready, total)
    val remaining = facts.remaining
    return when (val status = facts.status) {
      is AiStatus.Running -> LibraryAnalysisStatus.Running(remaining, status.etaMinutes, ready, total)
      AiStatus.ModelMissing -> LibraryAnalysisStatus.ModelMissing
      is AiStatus.Problem -> LibraryAnalysisStatus.Problem(status.code.id)
      // Il ruolo si e' spento a tappa in corso: per chi guarda, questo dispositivo non analizza.
      AiStatus.Off -> LibraryAnalysisStatus.NotHere(ready, total)
      is AiStatus.BufferOnlyDone ->
        if (remaining == 0) LibraryAnalysisStatus.AllReady
        // Senza NAS ha fatto cio' che poteva in locale: il resto lo aspetta.
        else if (remaining != null) LibraryAnalysisStatus.Paused(LibraryAnalysisWait.NAS, remaining, ready, total)
        else fromRules(facts, ready, total)
      is AiStatus.Waiting -> when (status.reason) {
        AiWaitReason.HOT -> pausedUnlessDone(LibraryAnalysisWait.THERMAL, facts, ready, total)
        AiWaitReason.NAS_UNREACHABLE -> pausedUnlessDone(LibraryAnalysisWait.NAS, facts, ready, total)
        AiWaitReason.STORAGE_LOW -> LibraryAnalysisStatus.Problem(AiDiag.AI_STORAGE_LOW.id)
        AiWaitReason.PREPARATION -> pausedUnlessDone(LibraryAnalysisWait.PREPARATION, facts, ready, total)
        AiWaitReason.NEXT_STAGE ->
          if (remaining == 0) LibraryAnalysisStatus.AllReady else LibraryAnalysisStatus.Queued(remaining, ready, total)
        // "Regole" e "rete" detti dal motore sono generici: il motivo vero lo dicono le regole di adesso.
        AiWaitReason.RULES, AiWaitReason.NO_NETWORK -> fromRules(facts, ready, total)
      }
      is AiStatus.Idle -> when {
        remaining == 0 -> LibraryAnalysisStatus.AllReady
        remaining == null && facts.coverage == SearchCoverage.Complete -> LibraryAnalysisStatus.AllReady
        else -> fromRules(facts, ready, total)
      }
    }
  }

  private fun pausedUnlessDone(reason: LibraryAnalysisWait, facts: Facts, ready: Int?, total: Int?): LibraryAnalysisStatus =
    if (facts.remaining == 0) LibraryAnalysisStatus.AllReady
    else LibraryAnalysisStatus.Paused(reason, facts.remaining, ready, total)

  /** Fermo con (forse) lavoro da fare: lo dice il verdetto delle regole di adesso; senza verdetto, "in coda" senza inventare motivi. */
  private fun fromRules(facts: Facts, ready: Int?, total: Int?): LibraryAnalysisStatus = when (val decision = facts.decision) {
    null, is RuleDecision.Run, is RuleDecision.NeedsConfirmation -> LibraryAnalysisStatus.Queued(facts.remaining, ready, total)
    is RuleDecision.Wait -> waitOf(decision.reason, facts, ready, total)
    is RuleDecision.Stop -> waitOf(decision.reason, facts, ready, total)
  }

  private fun waitOf(reason: WaitReason, facts: Facts, ready: Int?, total: Int?): LibraryAnalysisStatus = when (reason) {
    WaitReason.ActivityDisabled -> LibraryAnalysisStatus.Disabled
    is WaitReason.OutsideWindow -> pausedUnlessDone(LibraryAnalysisWait.NIGHT_WINDOW, facts, ready, total)
    WaitReason.NeedsPower -> pausedUnlessDone(LibraryAnalysisWait.CHARGING, facts, ready, total)
    is WaitReason.BatteryLow -> pausedUnlessDone(LibraryAnalysisWait.BATTERY, facts, ready, total)
    WaitReason.PowerSaver -> pausedUnlessDone(LibraryAnalysisWait.POWER_SAVER, facts, ready, total)
    is WaitReason.Thermal -> pausedUnlessDone(LibraryAnalysisWait.THERMAL, facts, ready, total)
    is WaitReason.CapReached -> pausedUnlessDone(LibraryAnalysisWait.DATA_CAP, facts, ready, total)
    WaitReason.HeavyNeedsHomeWifi -> pausedUnlessDone(LibraryAnalysisWait.HOME_WIFI, facts, ready, total)
    is WaitReason.NetworkNotAllowed ->
      pausedUnlessDone(if (reason.current == NetworkClass.MOBILE) LibraryAnalysisWait.WIFI else LibraryAnalysisWait.HOME_WIFI, facts, ready, total)
    WaitReason.Offline, WaitReason.NetworkNotTrusted, WaitReason.PoorNetwork, WaitReason.Roaming ->
      pausedUnlessDone(LibraryAnalysisWait.WIFI, facts, ready, total)
    WaitReason.NasUnreachable -> pausedUnlessDone(LibraryAnalysisWait.NAS, facts, ready, total)
    is WaitReason.AndroidLimit -> pausedUnlessDone(LibraryAnalysisWait.ANDROID_LIMIT, facts, ready, total)
    WaitReason.ProfileInactive -> pausedUnlessDone(LibraryAnalysisWait.PROFILE, facts, ready, total)
    // Il congelamento delle scritture e gli altri motivi rari li dice la Diagnostica per esteso: qui nessuna frase inventata.
    is WaitReason.Frozen, is WaitReason.RoleDisabled, WaitReason.AppNotVisible, is WaitReason.ConfirmationRequired ->
      pausedUnlessDone(LibraryAnalysisWait.OTHER, facts, ready, total)
  }

  /** (pronte, totale) veri dalla copertura; null dove la copertura non li dice (aggiornamento in corso, modello assente). */
  private fun readyTotal(coverage: SearchCoverage): Pair<Int?, Int?> = when (coverage) {
    is SearchCoverage.Partial -> coverage.covered to coverage.total
    else -> null to null
  }
}
