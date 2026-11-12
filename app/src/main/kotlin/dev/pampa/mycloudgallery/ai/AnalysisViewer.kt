package dev.pampa.mycloudgallery.ai

import android.content.Context
import dev.pampa.mycloudgallery.core.ai.compute.UrgentWait
import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.AiWaitReason
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.ConfirmationKind
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.RunScope
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.feature.viewer.menu.MediaAnalysis
import dev.pampa.mycloudgallery.feature.viewer.menu.MediaAnalysisHold
import dev.pampa.mycloudgallery.feature.viewer.menu.MediaAnalysisKind
import dev.pampa.mycloudgallery.feature.viewer.menu.ViewerAnalysis
import dev.pampa.mycloudgallery.work.AiWorkScheduling
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * "Analizza adesso" del visore sopra il calcolo vero (Pacchetto X, corretto dal Pacchetto XF): lo stato del media aperto si ricava dalle
 * **stesse** fonti del motore — il criterio di "fatto" del calcolo ([AiGraph.isDone]: ricette e lettura del testo correnti), i segni "non
 * supportato", il ruolo, il media che il motore sta calcolando adesso e il registro delle richieste — e **per la chiave corrente** del media
 * (XS-6: una versione vecchia gia' analizzata non toglie il comando a quella nuova).
 *
 * Al tocco la chiave entra nel registro (passa davanti nella coda, [AiUrgentFile]) e parte "Analizza adesso" ([AiWorkScheduling.analyzeAsked]):
 * una richiesta dell'utente **non confermata** e **solo per i media chiesti** (XS-3). Se le regole chiedono una conferma (dati mobili,
 * roaming, tetto superato) **nessun foglio** si apre: il media resta primo in coda e la capsula dice la condizione vera ("Parte sul Wi-Fi");
 * il resto dell'analisi resta al lavoro in background con le sue regole.
 *
 * Il motivo di una pausa e' quello **della richiesta davvero eseguita** (la richiesta dell'utente) valutato **adesso** e riletto quando
 * cambiano ambiente o impostazioni (XS-7), mai quello di una tappa di un'ora fa.
 *
 * Che cosa si analizza non cambia: sui video si fanno i vettori dei fotogrammi (e il testo sui fotogrammi scelti, con la lettura attiva),
 * come in ogni tappa; con la lettura del testo spenta si fa solo il vettore; nascosti e album protetti seguono le regole di oggi (il visore
 * dei Nascosti non mostra ne' voce ne' capsula, come album e mappa).
 */
class AppViewerAnalysis(
  private val context: Context,
  private val catalog: suspend () -> MediaCatalog?,
  private val ai: AiGraph,
  private val indexVersion: Flow<Long>,
  private val scope: CoroutineScope,
) : ViewerAnalysis {

  /** Cio' che costa leggere (catalogo, indice): si rilegge quando e' cambiato qualcosa che lo riguarda, non a ogni media che il motore finisce. */
  private class Resolved(val current: ContentKey?, val ready: Boolean, val unsupported: Boolean)

  override fun observe(id: MediaId): Flow<MediaAnalysis> = flow {
    var resolved: Resolved? = null
    // I segnali che cambiano cio' che si legge dal catalogo e dall'indice, e quelli dell'ambiente (regole): rileggono tutto.
    val heavy = combine(ai.indexer, ai.status, ai.urgent.state, ai.urgent.waits, indexVersion) { _, _, _, _, _ -> true }
    val environment = (ai.parts.decisionTriggers ?: emptyFlow()).map { true }
    // Il media che il motore sta calcolando cambia a ogni media: basta il confronto con la chiave gia' letta, senza rileggere niente.
    val cheap = ai.live.map { it.current }.distinctUntilChanged().map { false }
    merge(heavy, environment, cheap).collect { reload ->
      val now = if (reload || resolved == null) resolve(id).also { resolved = it } else resolved!!
      emit(evaluate(now))
    }
  }.distinctUntilChanged()

  override fun analyzeNow(id: MediaId) {
    scope.launch {
      val key = ownKeys(id)?.firstOrNull() ?: return@launch
      ai.urgent.add(key, id)
      AiWorkScheduling.analyzeAsked(context)
    }
  }

  /** Le chiavi delle versioni del media (la corrente per prima), anche per nascosti e membri di gruppo nel loro visore (VT-1). */
  private suspend fun ownKeys(id: MediaId): List<ContentKey>? {
    val cat = catalog() ?: return null
    return cat.mediaContentKeysOf(listOf(id)).firstOrNull { it.mediaId == id }?.own?.takeIf { it.isNotEmpty() }
  }

  /** XS-6: lo stato si legge per la **chiave corrente** (la prima); le versioni vecchie non decidono per il comando. */
  private suspend fun resolve(id: MediaId): Resolved {
    val current = ownKeys(id)?.first() ?: return Resolved(null, ready = false, unsupported = false)
    return Resolved(current, ready = done(current), unsupported = unsupported(id, current))
  }

  /** XS-5: "gia' analizzata" = lo stesso criterio del calcolo; un guasto dell'indice (non aperto) vale "non ancora". */
  private suspend fun done(key: ContentKey): Boolean = try {
    ai.isDone(key)
  } catch (e: CancellationException) {
    throw e
  } catch (e: IOException) {
    false
  }

  private suspend fun evaluate(r: Resolved): MediaAnalysis {
    val kind = ViewerAnalysisRules.kindOf(
      ViewerAnalysisRules.Facts(
        current = r.current,
        ready = r.ready,
        unsupported = r.unsupported,
        indexer = ai.indexer.value,
        modelMissing = ai.status.value == AiStatus.ModelMissing,
        liveCurrent = ai.live.value.current,
        asked = ai.urgent.state.value,
      ),
    )
    if (kind != MediaAnalysisKind.FIRST) return MediaAnalysis(kind)
    return MediaAnalysis(kind, holdOf(r.current!!))
  }

  /** "Non supportato" per il vettore: dai propri byte, o da un'anteprima di una delle sue share (R5, come la copertura). */
  private suspend fun unsupported(id: MediaId, key: ContentKey): Boolean {
    val marks = ai.unsupported
    if (AiClass.VEC in marks.classesOf(listOf(key))[key].orEmpty()) return true
    val shares = runCatching { catalog()?.nasSharesOf(listOf(id))?.get(id) }.getOrNull().orEmpty()
    return shares.any { share -> AiClass.VEC in marks.classesOf(listOf(key), share)[key].orEmpty() }
  }

  /**
   * Perche' una richiesta dell'utente non parte (o parte solo in locale) adesso: il verdetto **vero e di adesso** delle regole **per la
   * richiesta davvero eseguita** (non confermata, XS-3, XS-7), in una riga.
   */
  private suspend fun holdOf(key: ContentKey): MediaAnalysisHold? {
    val decision = ai.decisionFor(WorkRequest.UserInitiated(BackgroundActivity.AI_COMPUTE, confirmed = false, writesToNas = true))
    return ViewerAnalysisRules.holdOf(ai.urgent.waitOf(key), decision, ai.status.value)
  }
}

/**
 * Le regole **pure** dello stato del visore (provate con unita' vere in `ViewerAnalysisRulesTest`): quale caso, e perche' una richiesta non parte.
 * Nessuna lettura qui: i fatti arrivano gia' letti.
 */
internal object ViewerAnalysisRules {

  /** I fatti di un media aperto: la sua chiave **corrente** (XS-6) e cio' che il motore e il registro dicono di lei. */
  class Facts(
    val current: ContentKey?,
    val ready: Boolean,
    val unsupported: Boolean,
    val indexer: Boolean,
    val modelMissing: Boolean,
    val liveCurrent: ContentKey?,
    val asked: Set<ContentKey>,
  )

  fun kindOf(f: Facts): MediaAnalysisKind {
    val current = f.current ?: return MediaAnalysisKind.UNKNOWN
    if (f.ready) return MediaAnalysisKind.READY
    if (f.unsupported) return MediaAnalysisKind.UNSUPPORTED
    if (!f.indexer) return MediaAnalysisKind.NOT_HERE
    if (f.modelMissing) return MediaAnalysisKind.MODEL_MISSING
    if (current == f.liveCurrent) return MediaAnalysisKind.ANALYZING
    if (current in f.asked) return MediaAnalysisKind.FIRST
    return MediaAnalysisKind.QUEUED
  }

  /**
   * Il motivo di una richiesta ferma. Prima cio' che il motore ha visto della richiesta stessa (anteprime non pronte; unita' di un altro
   * dispositivo: "parte appena si puo'"), poi il verdetto **di adesso** delle regole per la richiesta dell'utente non confermata (XS-3, XS-7):
   * una conferma richiesta dai dati mobili o dal roaming vale "parte sul Wi-Fi". Solo senza un verdetto (montaggi di prova) si ripiega
   * sull'ultimo stato del motore, e solo per i suoi motivi specifici: "regole" generico non dice niente.
   */
  fun holdOf(wait: UrgentWait?, decision: RuleDecision?, status: AiStatus): MediaAnalysisHold? {
    when (wait) {
      UrgentWait.PREVIEW -> return MediaAnalysisHold.PREPARATION
      UrgentWait.OTHER_DEVICE -> return MediaAnalysisHold.RULES
      UrgentWait.RETRY, null -> Unit
    }
    if (decision != null) {
      return when (decision) {
        // Solo il buffer locale: senza NAS un media che sta solo li' aspetta.
        is RuleDecision.Run -> if (decision.scope == RunScope.LOCAL_BUFFER_ONLY) MediaAnalysisHold.NAS else null
        is RuleDecision.Wait -> holdOf(decision.reason)
        is RuleDecision.Stop -> holdOf(decision.reason)
        is RuleDecision.NeedsConfirmation -> if (ConfirmationKind.OVER_CAP in decision.kinds) MediaAnalysisHold.RULES else MediaAnalysisHold.WIFI
      }
    }
    val waiting = status as? AiStatus.Waiting ?: return null
    return when (waiting.reason) {
      AiWaitReason.HOT -> MediaAnalysisHold.HOT
      AiWaitReason.PREPARATION -> MediaAnalysisHold.PREPARATION
      AiWaitReason.NAS_UNREACHABLE, AiWaitReason.NO_NETWORK -> MediaAnalysisHold.NAS
      else -> null
    }
  }

  fun holdOf(reason: WaitReason): MediaAnalysisHold = when (reason) {
    is WaitReason.Thermal -> MediaAnalysisHold.HOT
    WaitReason.NasUnreachable, WaitReason.Offline, WaitReason.NetworkNotTrusted, WaitReason.PoorNetwork,
    WaitReason.Roaming, WaitReason.HeavyNeedsHomeWifi, is WaitReason.NetworkNotAllowed,
    -> MediaAnalysisHold.NAS
    else -> MediaAnalysisHold.RULES
  }
}
