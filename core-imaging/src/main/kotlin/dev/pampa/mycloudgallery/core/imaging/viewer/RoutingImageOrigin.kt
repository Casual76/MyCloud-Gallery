package dev.pampa.mycloudgallery.core.imaging.viewer

import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.core.imaging.decode.ImageBytes
import dev.pampa.mycloudgallery.core.model.MediaRef

/**
 * Sceglie l'origine dei byte per il tipo di riferimento, come `RoutingThumbnailSource` fa per le miniature (Fase 4): il
 * telefono legge dal `ContentResolver` ([ContentImageOrigin]), il NAS da [NasImageOrigin]. Senza un'origine per il NAS
 * (la famiglia non e' pronta, o la recinzione D33 la tiene spenta) un media del NAS risponde "il NAS non risponde" invece
 * di sparire come se non esistesse: la miniatura resta sotto la nota (E-L4).
 *
 * Un solo posto che conosce tutti i `MediaRef`: la `when` esaustiva fa fallire la compilazione se ne nasce un altro.
 */
class RoutingImageOrigin(
  private val phone: ImageOrigin,
  private val nas: ImageOrigin? = null,
) : ImageOrigin {

  override suspend fun open(ref: MediaRef): OriginResult = open(ref) { }

  override suspend fun open(ref: MediaRef, onProgress: (Float?) -> Unit): OriginResult = when (ref) {
    is MediaRef.Nas -> nas?.open(ref, onProgress) ?: OriginResult.Unreachable(UnavailableReason.NAS_UNREACHABLE)
    is MediaRef.Phone -> phone.open(ref, onProgress)
    // La libreria sintetica (debug e benchmark) non ha byte da aprire: il viewer mostra la miniatura che ha.
    is MediaRef.Synthetic -> OriginResult.Missing
  }

  override fun openLocal(ref: MediaRef): ImageBytes? = when (ref) {
    is MediaRef.Nas -> nas?.openLocal(ref)
    is MediaRef.Phone -> phone.openLocal(ref)
    is MediaRef.Synthetic -> null
  }

  override fun declaredSize(ref: MediaRef): DeclaredSize? = when (ref) {
    is MediaRef.Nas -> nas?.declaredSize(ref)
    is MediaRef.Phone -> phone.declaredSize(ref)
    is MediaRef.Synthetic -> null
  }
}
