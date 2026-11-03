package dev.pampa.mycloudgallery.release

import android.content.Context
import dev.pampa.mycloudgallery.BuildConfig
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.release.contract.AppVersion
import dev.pampa.mycloudgallery.core.release.contract.ManifestLimits
import dev.pampa.mycloudgallery.core.release.contract.ManifestOrigin
import dev.pampa.mycloudgallery.core.release.contract.ManifestRead
import dev.pampa.mycloudgallery.core.release.contract.ManifestSource
import dev.pampa.mycloudgallery.core.release.contract.OfferResult
import dev.pampa.mycloudgallery.core.rules.run.StageOutcome
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.debugtools.DeveloperTools
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.graph.snapshotOrNull
import java.io.File
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// IL LEGAME DEL MANIFEST (Fase 10, P13, §1.4 "ReleaseGraph", §2.8 "Lettura"; Q8 (a): solo dal NAS, con la sessione che c'e' gia'). La fiducia e il canale vengono dalla
// variante (`DeveloperTools`): la release (e le varianti di misura) si fida di K1 e K2; la debug anche della chiave di prova, che la fabbrica toglie comunque a ogni
// canale che non sia `DEBUG`.

object ReleaseWiring {

  /** Il grafo del manifest di questo processo (nessun I/O: lo stato si legge con `ReleaseGraph.load`). */
  fun create(context: Context, nasNow: () -> Instant?): ReleaseGraph = ReleaseGraph.create(
    dir = File(context.noBackupFilesDir, ReleaseGraph.DIR),
    version = AppVersion(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, DeveloperTools.releaseChannel(BuildConfig.VERSION_NAME)),
    trust = DeveloperTools.releaseTrust(),
    nasNow = nasNow,
  )

  /** R2: le letture del manifest di questo processo, con la sessione del dispositivo e l'area comune della configurazione. */
  fun reads(graph: AppGraph): ManifestReads =
    ManifestReads(graph.release) { NasManifestSource(graph.session) { graph.onboardingStore.snapshotOrNull()?.commonShare } }
}

/**
 * **Quando** si legge il manifest (D29: "al giro di sincronizzazione gia' esistente e all'avvio se l'ultima lettura ha piu' di 24 ore"; R2 della revisione di sicurezza della
 * Fase 10). Prima lo leggeva solo una tappa della sincronizzazione dell'indice **finita**: un giro che non finiva mai (una prima sincronizzazione di giorni, o proprio il
 * difetto da fermare) teneva lontano il blocco, mentre il resto continuava a scrivere.
 *
 * - [afterIndexSync]: dopo una tappa della sincronizzazione dell'indice che ha usato la sessione, **finita o lasciata a meta'** per tempo o per un tetto (`Yielded`). Non
 *   dopo una tappa che le regole hanno fermato o non lasciato partire: li' la sessione non si e' usata (rete, fascia, traffico).
 * - [atAppStart]: all'apertura dell'app (avvio e ritorno in primo piano), **dopo il primo disegno**, con la famiglia pronta e l'area comune nota: anche se il giro dell'indice
 *   non arriva mai alla fine o lancia.
 *
 * Il ritmo e' sempre quello di [ReleaseGraph.readIfDue]: al piu' una lettura con una risposta ogni 24 ore (un NAS che non risponde si riprova alla volta dopo). Mai
 * un'eccezione verso chi chiama.
 */
class ManifestReads(private val release: ReleaseGraph?, private val source: () -> ManifestSource) {

  suspend fun afterIndexSync(outcome: StageOutcome): OfferResult? = when (outcome) {
    is StageOutcome.Finished, is StageOutcome.Yielded -> read()
    else -> null
  }

  /** [ready]: la famiglia pronta, l'area comune nota e i lavori ammessi (mai nella variante di misura): senza, una lettura "assente" segnerebbe l'ora per 24 ore. */
  suspend fun atAppStart(ready: Boolean): OfferResult? = if (ready) read() else null

  private suspend fun read(): OfferResult? = try {
    release?.readIfDue(source())
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    null
  }
}

/**
 * Il manifest sul NAS (§2.8, Q8 (a)): `<area comune>/` + `ManifestLimits.NAS_PATH`, un file solo, al piu' `maxBytes` (oltre: `TooLarge`, senza leggere il resto), con la
 * sessione **gia' aperta** del giro della sincronizzazione (nessuna connessione nuova). Nessuna area comune, file assente = `Absent`; NAS che non risponde, recinzione
 * delle prove o un errore = `Unavailable` (niente cambia, si riprova al giro dopo).
 */
class NasManifestSource(
  private val session: NasSessionManager,
  private val commonShare: () -> String?,
) : ManifestSource {
  override val origin: ManifestOrigin = ManifestOrigin.NAS

  override suspend fun read(maxBytes: Int): ManifestRead {
    val share = commonShare() ?: return ManifestRead.Absent
    return withContext(Dispatchers.IO) {
      try {
        when (val read = session.fileSystem(share).read(SharePath.parse(ManifestLimits.NAS_PATH), maxBytes)) {
          is NasRead.Data -> ManifestRead.Found(read.bytes)
          NasRead.NotFound -> ManifestRead.Absent
          NasRead.TooLarge -> ManifestRead.TooLarge
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        ManifestRead.Unavailable
      }
    }
  }
}
