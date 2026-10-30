package dev.pampa.mycloudgallery.faces

import dev.pampa.mycloudgallery.core.faces.compute.FacesEstimate
import dev.pampa.mycloudgallery.core.faces.compute.FacesRates
import dev.pampa.mycloudgallery.core.faces.compute.FacesTally
import dev.pampa.mycloudgallery.core.faces.compute.FacesWork
import dev.pampa.mycloudgallery.core.faces.contract.FacesComputeLimits
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.estimate.DeviceMeasures
import dev.pampa.mycloudgallery.feature.people.activation.FacesAnalyzeNow
import dev.pampa.mycloudgallery.feature.people.contract.FacesAnalyzeEstimate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * «ANALIZZA ORA» DELL'APP (Fase 8, correzioni C, CA-2; risposta dell'utente del 30/10): la stima di cio' che resta e il tocco confermato che chiede il calcolo adesso.
 *
 * - **La stima** viene da [FacesBacklog][dev.pampa.mycloudgallery.core.faces.compute.FacesBacklog] (le stesse porte e le stesse regole del calcolo: cosa si analizza e
 *   cosa e' gia' fatto) e dai ritmi di adesso ([rates]: la misura del lavoro vero se il calcolo ha lavorato in questo processo, altrimenti le **8,8 foto al secondo** misurate
 *   sull'S25 da `DeviceMeasures.facesPerSecond`). Contare il lavoro rilegge il catalogo (L242): gira su [io], mai sul thread principale e mai sul percorso dell'avvio; chi lo
 *   chiede (la pagina) l'ha gia' disegnata.
 * - **I dati mobili**: [decide] e' il verdetto delle regole per un lavoro dell'utente con la stima dei dati; se chiede conferma (dati mobili, roaming, tetto) la frase
 *   delle regole, con la stima, entra nella conferma della pagina, come per gli altri «Fai ora» dell'app.
 * - **Il tocco** ([request]) e' l'unico modo di scavalcare le regole di sottofondo; non scarica originali dal NAS (un media senza anteprima aspetta, come sempre).
 *
 * Solo numeri e frasi delle regole: mai nomi, id o conteggi per persona (D37).
 */
class AppFacesAnalyzeNow(
  private val tally: suspend () -> FacesTally?,
  private val rates: () -> FacesRates,
  private val decide: suspend (estimatedBytes: Long) -> RuleDecision,
  private val request: () -> Unit,
  private val previewBytes: Long = DeviceMeasures.PHASE_0.analysisPreviewBytes,
  private val io: CoroutineDispatcher = Dispatchers.Default,
) : FacesAnalyzeNow {

  override suspend fun estimate(): FacesAnalyzeEstimate? = withContext(io) {
    val counted = try {
      tally()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    } ?: return@withContext null
    FacesAnalyzeEstimate(
      remaining = counted.work.total,
      done = counted.done,
      minutes = FacesEstimate.minutes(counted.work, rates()),
      bytes = previewBytesOf(counted.work),
    )
  }

  override suspend fun networkNote(estimate: FacesAnalyzeEstimate): String? {
    val decision = try {
      decide(estimate.bytes ?: 0L)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return null
    }
    return (decision as? RuleDecision.NeedsConfirmation)?.message
  }

  override fun start() = request()

  /**
   * Al piu' i byte delle anteprime da scaricare (150 KB l'una, quattro per un video: la copertina e i fotogrammi): cio' che e' gia' nel buffer o sul telefono non
   * si scarica, quindi e' un tetto, non una promessa.
   */
  private fun previewBytesOf(work: FacesWork): Long = (work.photos + work.videos * (1 + FacesComputeLimits.FRAMES_PER_VIDEO)) * previewBytes

  override fun toString(): String = "AppFacesAnalyzeNow"
}
