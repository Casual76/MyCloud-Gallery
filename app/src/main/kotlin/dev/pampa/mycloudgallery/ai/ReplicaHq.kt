package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.imaging.nas.NasBlobScopes
import dev.pampa.mycloudgallery.core.index.contract.ReplicaBlobs
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException

/**
 * L'anteprima **HQ della replica** di un media del NAS (2.048 px, anche offline): la migliore immagine che il dispositivo ha di un file che
 * non e' sul telefono e di cui non ha l'originale (D44, §2.10 "Sorgente dell'immagine"; D42 per la lettura al volo). Mai la rete. Il blob si
 * cerca solo nel perimetro del media (le sue share, la sua area per prima: R1 della 4A), come fa il viewer ([NasBlobScopes]).
 */
class ReplicaHq(private val blobs: ReplicaBlobs, private val scopes: NasBlobScopes? = null) {

  /** I byte dell'HQ (mappati, in sola lettura), o null se la replica non ce l'ha o e' stata azzerata nel frattempo. */
  suspend fun buffer(ref: MediaRef.Nas): ByteBuffer? {
    val scope = try {
      scopes?.scopeOf(ref)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    }
    return try {
      val blob = (if (scope == null) blobs.locate(ref.contentKey, SegmentClass.HQ) else blobs.locate(ref.contentKey, SegmentClass.HQ, scope))
        ?: return null
      blobs.read(blob)
    } catch (e: IOException) {
      null
    } catch (e: RuntimeException) {
      null
    }
  }

  /** Come [buffer], copiato in un array (per l'OCR: `ModelInputs` lavora su byte). */
  suspend fun bytes(ref: MediaRef.Nas): ByteArray? = buffer(ref)?.let { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) } }
}
