package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.AiWaitReason
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
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
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * "Analizza adesso" del visore sopra il calcolo vero (Pacchetto X): lo stato del media aperto si ricava dalle **stesse** fonti del
 * motore — cio' che e' salvato qui con la ricetta corrente (`AiResults.saved`, come la ripresa D22), i segni "non supportato", il ruolo,
 * il media che il motore sta calcolando adesso e il registro delle urgenze. Al tocco la chiave entra nel registro (passa davanti nella
 * coda, [AiUrgentFile]) e parte "Indicizza ora" come richiesta dell'utente con le regole di sempre: nessun percorso nuovo, nessuna
 * scorciatoia su prenotazioni, cancelli o pubblicazione. Se le regole lo fermano (telefono troppo caldo, NAS irraggiungibile) la
 * capsula lo dice subito con il motivo vero e il media **resta primo** per quando si puo'.
 *
 * Che cosa si analizza non cambia: sui video si fanno i vettori dei fotogrammi (e il testo sui fotogrammi scelti, con la lettura attiva),
 * come in ogni tappa; con la lettura del testo spenta si fa solo il vettore; nascosti e album protetti seguono le regole di oggi (il
 * visore dei Nascosti non mostra ne' voce ne' capsula, come album e mappa).
 */
class AppViewerAnalysis(
  private val context: Context,
  private val catalog: suspend () -> MediaCatalog?,
  private val ai: AiGraph,
  private val indexVersion: Flow<Long>,
  private val scope: CoroutineScope,
  /** Un vettore scelto nell'indice locale, anche calcolato da un altro dispositivo e arrivato dal NAS. */
  private val hasVectors: (ContentKey) -> Boolean = { false },
) : ViewerAnalysis {

  override fun observe(id: MediaId): Flow<MediaAnalysis> = flow {
    combine(ai.indexer, ai.status, ai.live, ai.urgent.state, indexVersion) { _, _, _, _, _ -> Unit }
      .collect { emit(evaluate(id)) }
  }.distinctUntilChanged()

  override fun analyzeNow(id: MediaId) {
    scope.launch {
      val key = ownKeys(id)?.firstOrNull() ?: return@launch
      ai.urgent.add(key)
      AiWorkScheduling.indexNow(context)
    }
  }

  /** Le chiavi delle versioni del media (la corrente per prima), anche per nascosti e membri di gruppo nel loro visore (VT-1). */
  private suspend fun ownKeys(id: MediaId): List<ContentKey>? {
    val cat = catalog() ?: return null
    return cat.mediaContentKeysOf(listOf(id)).firstOrNull { it.mediaId == id }?.own?.takeIf { it.isNotEmpty() }
  }

  private suspend fun evaluate(id: MediaId): MediaAnalysis {
    val keys = ownKeys(id) ?: return MediaAnalysis.UNKNOWN
    if (keys.any { ready(it) }) return MediaAnalysis(MediaAnalysisKind.READY)
    if (unsupported(id, keys)) return MediaAnalysis(MediaAnalysisKind.UNSUPPORTED)
    if (!ai.indexer.value) return MediaAnalysis(MediaAnalysisKind.NOT_HERE)
    if (ai.status.value == AiStatus.ModelMissing) return MediaAnalysis(MediaAnalysisKind.MODEL_MISSING)
    if (keys.any { it == ai.live.value.current }) return MediaAnalysis(MediaAnalysisKind.ANALYZING)
    if (keys.any { it in ai.urgent.state.value }) return MediaAnalysis(MediaAnalysisKind.FIRST, holdOf())
    return MediaAnalysis(MediaAnalysisKind.QUEUED)
  }

  /**
   * "Gia' analizzata" per chi guarda: un vettore nell'indice locale (calcolato qui o arrivato da un altro dispositivo: in entrambi i casi
   * la ricerca la trova), e con la lettura del testo attiva anche il testo salvato qui con la ricetta corrente. Il motore salta comunque
   * cio' che altri hanno gia' pubblicato: niente si analizza due volte.
   */
  private suspend fun ready(key: ContentKey): Boolean = runCatching {
    val recipe = ai.recipe
    val forVec = ai.results.saved(key, recipe.version)
    if (AiClass.VEC !in forVec && !hasVectors(key)) return@runCatching false
    if (!ai.textReadingHere.value) return@runCatching true
    val forOcr = if (recipe.ocrVersion == recipe.version) forVec else ai.results.saved(key, recipe.ocrVersion)
    AiClass.OCR in forOcr
  }.getOrDefault(false)

  /** "Non supportato" per il vettore: dai propri byte, o da un'anteprima di una delle sue share (R5, come la copertura). */
  private suspend fun unsupported(id: MediaId, keys: List<ContentKey>): Boolean {
    val marks = ai.unsupported
    if (marks.classesOf(keys).values.any { AiClass.VEC in it }) return true
    val shares = runCatching { catalog()?.nasSharesOf(listOf(id))?.get(id) }.getOrNull().orEmpty()
    return shares.any { share -> marks.classesOf(keys, share).values.any { AiClass.VEC in it } }
  }

  /** Perche' una richiesta dell'utente non parte (o parte solo in locale) adesso: il verdetto vero, in una riga. */
  private suspend fun holdOf(): MediaAnalysisHold? {
    (ai.status.value as? AiStatus.Waiting)?.let { waiting ->
      return when (waiting.reason) {
        AiWaitReason.HOT -> MediaAnalysisHold.HOT
        AiWaitReason.PREPARATION -> MediaAnalysisHold.PREPARATION
        AiWaitReason.NAS_UNREACHABLE, AiWaitReason.NO_NETWORK -> MediaAnalysisHold.NAS
        else -> null
      }
    }
    val decision = runCatching {
      ai.parts.decide?.invoke(WorkRequest.UserInitiated(BackgroundActivity.AI_COMPUTE, confirmed = true, writesToNas = true))
    }.getOrNull() ?: return null
    return when (decision) {
      // Solo il buffer locale: senza NAS un media che sta solo li' aspetta.
      is RuleDecision.Run -> if (decision.scope == RunScope.LOCAL_BUFFER_ONLY) MediaAnalysisHold.NAS else null
      is RuleDecision.Wait -> holdOf(decision.reason)
      is RuleDecision.Stop -> holdOf(decision.reason)
      is RuleDecision.NeedsConfirmation -> MediaAnalysisHold.RULES
    }
  }

  private fun holdOf(reason: WaitReason): MediaAnalysisHold = when (reason) {
    is WaitReason.Thermal -> MediaAnalysisHold.HOT
    WaitReason.NasUnreachable, WaitReason.Offline, WaitReason.NetworkNotTrusted, WaitReason.PoorNetwork,
    WaitReason.Roaming, WaitReason.HeavyNeedsHomeWifi, is WaitReason.NetworkNotAllowed,
    -> MediaAnalysisHold.NAS
    else -> MediaAnalysisHold.RULES
  }
}
