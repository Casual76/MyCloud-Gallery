package dev.pampa.mycloudgallery.ai

import android.content.Context
import dev.pampa.mycloudgallery.core.ai.compute.UrgentWait
import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.AiWaitReason
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
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

  private val resolver = ViewerAnalysisResolver(
    currentKey = { id -> ownKeys(id)?.first() },
    isDone = ::done,
    isUnsupported = ::unsupported,
  )

  /** XS3-3: i media la cui ultima richiesta "Analizza adesso" non e' stata accodata (si toglie a ogni nuova richiesta di quel media). */
  private val askFailures = MutableStateFlow<Set<MediaId>>(emptySet())

  override fun observe(id: MediaId): Flow<MediaAnalysis> = flow {
    var resolved: ViewerResolved? = null
    // I segnali che cambiano cio' che si legge dal catalogo e dall'indice, e quelli dell'ambiente (regole): rileggono tutto.
    val heavy = combine(ai.indexer, ai.status, ai.urgent.state, ai.urgent.waits, indexVersion) { _, _, _, _, _ -> true }
    // XS3-3: un errore degli ascoltatori di sistema (la registrazione rifiutata) non fa cadere lo stato del visore ne' esce senza gestore: una riga nel log (solo il tipo),
    // gli eventi dell'ambiente finiscono e il resto dei segnali continua.
    val environment = (ai.parts.decisionTriggers ?: emptyFlow()).map { true }.catch { e ->
      if (e is CancellationException) throw e
      GalleryLog.e(TAG, e) { "visore: eventi dell'ambiente: errore (${e.javaClass.simpleName}), ignorati" }
    }
    // Una richiesta non accodata (o tolta) riscrive lo stato senza rileggere niente.
    val failures = askFailures.map { false }
    // XS2-5: la **versione** del media. Il catalogo dice quando un media cambia (una modifica salvata, una nuova versione): senza questo segnale il visore
    // teneva la lettura della versione di prima finche' non cambiava altro.
    val versions = (catalog()?.changes?.filter { id in it.ids } ?: emptyFlow()).map { true }
    // Il media che il motore sta calcolando cambia a ogni media: basta il confronto con la chiave gia' letta, senza rileggere niente.
    val cheap = ai.live.map { it.current }.distinctUntilChanged().map { false }
    merge(heavy, environment, versions, cheap, failures).collect { reload ->
      val now = if (reload || resolved == null) resolver.resolve(id).also { resolved = it } else resolved!!
      val state = evaluate(now)
      // XS3-3: l'esito della richiesta nello stato (non un crash): vale finche' il media non e' pronto.
      emit(if (id in askFailures.value && state.kind != MediaAnalysisKind.READY) state.copy(askFailed = true) else state)
    }
  }.distinctUntilChanged()

  override fun analyzeNow(id: MediaId) {
    scope.launch {
      askFailures.update { it - id }
      try {
        val key = ownKeys(id)?.firstOrNull() ?: throw AskNotQueued()
        ai.urgent.add(key, id)
        AiWorkScheduling.analyzeAsked(context)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // XS3-3: il catalogo che non ha il media, il lavoro che non si accoda: l'esito e' osservabile da chi ha toccato ([MediaAnalysis.askFailed]) e c'e' una riga nel log.
        GalleryLog.e(TAG, e) { "analisi chiesta: non accodata (${e.javaClass.simpleName})" }
        askFailures.update { it + id }
      }
    }
  }

  /** Il catalogo non ha (ancora) una chiave per il media: la richiesta non si puo' registrare. */
  private class AskNotQueued : Exception()

  private companion object {
    const val TAG = "AppViewerAnalysis"
  }

  /** Le chiavi delle versioni del media (la corrente per prima), anche per nascosti e membri di gruppo nel loro visore (VT-1). */
  private suspend fun ownKeys(id: MediaId): List<ContentKey>? {
    val cat = catalog() ?: return null
    return cat.mediaContentKeysOf(listOf(id)).firstOrNull { it.mediaId == id }?.own?.takeIf { it.isNotEmpty() }
  }

  /** XS-5: "gia' analizzata" = lo stesso criterio del calcolo; un guasto dell'indice (non aperto) vale "non ancora". */
  private suspend fun done(key: ContentKey): Boolean = try {
    ai.isDone(key)
  } catch (e: CancellationException) {
    throw e
  } catch (e: IOException) {
    false
  }

  private suspend fun evaluate(r: ViewerResolved): MediaAnalysis {
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
    // XS2-5: ogni stato porta la versione (la chiave corrente) per cui e' stato letto.
    val version = r.current?.short
    if (kind != MediaAnalysisKind.FIRST) return MediaAnalysis(kind, version = version)
    return MediaAnalysis(kind, holdOf(r.current!!), version)
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

/** Cio' che costa leggere (catalogo, indice) per una versione del media: la chiave corrente e cio' che l'indice dice di lei. */
internal class ViewerResolved(val current: ContentKey?, val ready: Boolean, val unsupported: Boolean)

/**
 * XS2-5: la lettura dello stato di un media **legata alla sua versione**. Si legge la chiave corrente, poi cio' che costa (l'indice, i segni di "non
 * supportato"), e infine si **rilegge la chiave corrente**: se la versione e' cambiata mentre si leggeva, il risultato e' di una versione di prima e **si
 * rifiuta** (si rifa' per la versione nuova, al piu' [MAX_TRIES] volte; se non si stabilizza vale "non si sa"). Cosi' una vecchia lettura "pronta" in ritardo non
 * togliera' mai il comando alla versione nuova. Provata con barriere in `ViewerAnalysisResolverTest`.
 */
internal class ViewerAnalysisResolver(
  /** La chiave della versione corrente del media (null = il catalogo non la sa). */
  private val currentKey: suspend (MediaId) -> ContentKey?,
  private val isDone: suspend (ContentKey) -> Boolean,
  private val isUnsupported: suspend (MediaId, ContentKey) -> Boolean,
) {
  suspend fun resolve(id: MediaId): ViewerResolved {
    repeat(MAX_TRIES) {
      val key = currentKey(id) ?: return ViewerResolved(null, ready = false, unsupported = false)
      val ready = isDone(key)
      val unsupported = isUnsupported(id, key)
      if (currentKey(id) == key) return ViewerResolved(key, ready, unsupported)
    }
    return ViewerResolved(null, ready = false, unsupported = false)
  }

  private companion object {
    const val MAX_TRIES = 3
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
    // Il verdetto di adesso che ferma la richiesta (caldo, rete, conferma...) vince sull'attesa **vecchia** vista nell'ultima tappa (anteprime non pronte,
    // unita' di un altro dispositivo): quelle sono "l'ultimo tentativo" e valgono solo se le regole lasciano partire la richiesta adesso.
    val blocked = decision != null && decision !is RuleDecision.Run
    if (!blocked) {
      when (wait) {
        UrgentWait.PREVIEW -> return MediaAnalysisHold.PREPARATION
        UrgentWait.OTHER_DEVICE -> return MediaAnalysisHold.RULES
        UrgentWait.RETRY, null -> Unit
      }
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
