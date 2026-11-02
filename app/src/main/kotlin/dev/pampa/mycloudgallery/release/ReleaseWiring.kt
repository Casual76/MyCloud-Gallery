package dev.pampa.mycloudgallery.release

import android.content.Context
import dev.pampa.mycloudgallery.BuildConfig
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.release.contract.AppVersion
import dev.pampa.mycloudgallery.core.release.contract.ManifestLimits
import dev.pampa.mycloudgallery.core.release.contract.ManifestOrigin
import dev.pampa.mycloudgallery.core.release.contract.ManifestRead
import dev.pampa.mycloudgallery.core.release.contract.ManifestSource
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.debugtools.DeveloperTools
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
