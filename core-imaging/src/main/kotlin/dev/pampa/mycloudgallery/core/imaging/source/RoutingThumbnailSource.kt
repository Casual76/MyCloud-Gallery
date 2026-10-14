package dev.pampa.mycloudgallery.core.imaging.source

import android.graphics.Bitmap
import android.os.CancellationSignal
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbContext
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbResult
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.noop.NoThumbnailSource
import dev.pampa.mycloudgallery.core.model.MediaRef

/**
 * Sceglie la sorgente giusta per il tipo di riferimento. E' l'unico punto che conosce tutti i [MediaRef]: la `when`
 * esaustiva fa fallire la compilazione se ne nasce uno nuovo.
 *
 * La sorgente sintetica **non** si importa da qui: sta nel package `testing`, che non deve finire nella release
 * (`tools/check.ps1`). La passa `:app`, solo nella build di debug con la Libreria sintetica; altrimenti i
 * riferimenti sintetici non producono niente.
 *
 * Fase 4 (P7): [nas] e' `NasThumbnailSource` (replica → miniatura provvisoria → originale solo se visibile, D17). Finche'
 * `:app` non la passa, i riferimenti del NAS non producono niente (la cella mostra il segnaposto). [loadResult] e
 * [canUpgrade] arrivano alla sorgente giusta: e' cosi' che il contesto "visibile / in arrivo" raggiunge il NAS.
 */
class RoutingThumbnailSource(
  private val phone: ThumbnailSource,
  private val synthetic: ThumbnailSource = NoThumbnailSource,
  private val nas: ThumbnailSource = NoThumbnailSource,
) : ThumbnailSource {

  override suspend fun load(ref: MediaRef, px: Int, cancel: CancellationSignal): Bitmap? = when (ref) {
    is MediaRef.Phone -> phone.load(ref, px, cancel)
    is MediaRef.Synthetic -> synthetic.load(ref, px, cancel)
    is MediaRef.Nas -> nas.load(ref, px, cancel)
  }

  override suspend fun loadResult(ref: MediaRef, px: Int, cancel: CancellationSignal, context: ThumbContext): ThumbResult? = when (ref) {
    is MediaRef.Phone -> phone.loadResult(ref, px, cancel, context)
    is MediaRef.Synthetic -> synthetic.loadResult(ref, px, cancel, context)
    is MediaRef.Nas -> nas.loadResult(ref, px, cancel, context)
  }

  override fun canUpgrade(ref: MediaRef, px: Int): Boolean = when (ref) {
    is MediaRef.Phone -> phone.canUpgrade(ref, px)
    is MediaRef.Synthetic -> synthetic.canUpgrade(ref, px)
    is MediaRef.Nas -> nas.canUpgrade(ref, px)
  }
}
