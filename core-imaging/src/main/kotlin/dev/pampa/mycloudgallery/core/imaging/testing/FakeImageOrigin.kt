package dev.pampa.mycloudgallery.core.imaging.testing

import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.core.imaging.decode.ByteArrayImageBytes
import dev.pampa.mycloudgallery.core.imaging.decode.ImageBytes
import dev.pampa.mycloudgallery.core.imaging.viewer.ImageOrigin
import dev.pampa.mycloudgallery.core.imaging.viewer.OriginResult
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay

/**
 * Un'origine dei byte finta per il viewer (E-V1, E-V2, E-V5): i file sono array di byte in memoria, la sorgente puo'
 * essere **lenta** ([latencyMs], con il tempo virtuale dei test) o **irraggiungibile** ([unreachable], come sara' il
 * NAS in Fase 4). Come i fake di P0 sta in `testing`: vive in `src/main` perche' i moduli a valle (feature-viewer) la
 * riusino, e non entra mai nell'APK di release.
 */
class FakeImageOrigin : ImageOrigin {

  private val files = ConcurrentHashMap<MediaRef, ByteArray>()

  /** Quanto ci mette ad aprire un media; 0 = subito. */
  @Volatile
  var latencyMs: Long = 0

  /** Se non e' null, ogni apertura risponde questo (sorgente irraggiungibile). */
  @Volatile
  var unreachable: OriginResult.Unreachable? = null

  /** Quante volte e' stata chiamata [open]. */
  @Volatile
  var openCalls: Int = 0
    private set

  fun put(ref: MediaRef, bytes: ByteArray) {
    files[ref] = bytes
  }

  fun remove(ref: MediaRef) {
    files.remove(ref)
  }

  /** Comodo per i test: il NAS non risponde, con la dimensione da mostrare (E-V1, E-V2). */
  fun makeUnreachable(reason: UnavailableReason = UnavailableReason.NAS_UNREACHABLE, sizeBytes: Long? = null) {
    unreachable = OriginResult.Unreachable(reason, sizeBytes)
  }

  override suspend fun open(ref: MediaRef): OriginResult {
    openCalls++
    if (latencyMs > 0) delay(latencyMs)
    unreachable?.let { return it }
    val bytes = files[ref] ?: return OriginResult.Missing
    return OriginResult.Opened(ByteArrayImageBytes(bytes))
  }

  override fun openLocal(ref: MediaRef): ImageBytes? = files[ref]?.let { ByteArrayImageBytes(it) }
}
