package dev.pampa.mycloudgallery.core.imaging.source

import android.graphics.Bitmap
import android.os.CancellationSignal
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.noop.NoThumbnailSource
import dev.pampa.mycloudgallery.core.model.MediaRef

/**
 * Sceglie la sorgente giusta per il tipo di riferimento. E' l'unico punto che conosce tutti i [MediaRef]: dalla
 * Fase 4 qui si aggiunge `Nas` (la `when` esaustiva fa fallire la compilazione finche' non lo si fa).
 *
 * La sorgente sintetica **non** si importa da qui: sta nel package `testing`, che non deve finire nella release
 * (`tools/check.ps1`). La passa `:app`, solo nella build di debug con la Libreria sintetica; altrimenti i
 * riferimenti sintetici non producono niente.
 */
class RoutingThumbnailSource(
  private val phone: ThumbnailSource,
  private val synthetic: ThumbnailSource = NoThumbnailSource,
) : ThumbnailSource {

  override suspend fun load(ref: MediaRef, px: Int, cancel: CancellationSignal): Bitmap? = when (ref) {
    is MediaRef.Phone -> phone.load(ref, px, cancel)
    is MediaRef.Synthetic -> synthetic.load(ref, px, cancel)
  }
}
