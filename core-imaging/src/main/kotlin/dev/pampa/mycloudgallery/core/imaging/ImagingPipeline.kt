package dev.pampa.mycloudgallery.core.imaging

import android.content.Context
import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.imaging.budget.ImagingBudget
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.loader.PipelineThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.noop.NoThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.placeholder.ThumbHashDecoder
import dev.pampa.mycloudgallery.core.imaging.placeholder.ThumbHashPlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.source.PhoneThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.source.RoutingThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.viewer.ContentImageOrigin
import dev.pampa.mycloudgallery.core.imaging.viewer.PipelineViewerImageSource

/**
 * Il montaggio della pipeline per `:app` (P11): un posto solo che sa quali pezzi vanno insieme, cosi' il grafo di
 * Hilt scrive una riga per oggetto. `core-*` non usa Hilt (D23): `:app` chiama queste funzioni da un modulo `@Provides`
 * e le tiene `@Singleton`.
 *
 * ```
 * val budget = ImagingBudget.of(context)
 * val sources = ImagingPipeline.thumbnailSource(context, synthetic = debugSyntheticSource)   // null/niente in release
 * val loader = ImagingPipeline.thumbnailLoader(sources, budget)
 * val viewer = ImagingPipeline.viewerImageSource(context, sources, budget)
 * val placeholders = ImagingPipeline.placeholderCache(budget, ThumbHashCodec::decode)
 * ```
 *
 * `onTrimMemory(level)` dell'applicazione va a `loader.trim(level)`.
 */
object ImagingPipeline {

  /**
   * La sorgente delle miniature: il telefono dal MediaStore. [synthetic] e' la sorgente della Libreria sintetica
   * (`SyntheticThumbnailSource`, solo build di debug: sta in `testing`, che la release non porta).
   */
  fun thumbnailSource(context: Context, synthetic: ThumbnailSource = NoThumbnailSource): ThumbnailSource =
    RoutingThumbnailSource(phone = PhoneThumbnailSource(context.applicationContext.contentResolver), synthetic = synthetic)

  fun thumbnailLoader(
    source: ThumbnailSource,
    budget: ImagingBudget,
    dispatchers: DispatcherProvider = DefaultDispatcherProvider,
  ): PipelineThumbnailLoader = PipelineThumbnailLoader(source = source, budget = budget, dispatchers = dispatchers)

  /** Le immagini del viewer: i byte dal MediaStore, l'anteprima dalla stessa sorgente delle miniature. */
  fun viewerImageSource(
    context: Context,
    thumbnails: ThumbnailSource,
    budget: ImagingBudget,
    dispatchers: DispatcherProvider = DefaultDispatcherProvider,
  ): PipelineViewerImageSource = PipelineViewerImageSource(
    origin = ContentImageOrigin(context.applicationContext.contentResolver),
    previews = thumbnails,
    budget = budget,
    dispatchers = dispatchers,
  )

  /** I segnaposto: [decoder] e' `ThumbHashCodec::decode` di `core-metadata` (questo modulo non lo vede). */
  fun placeholderCache(budget: ImagingBudget, decoder: ThumbHashDecoder): ThumbHashPlaceholderCache =
    ThumbHashPlaceholderCache(decoder, budget.placeholderEntries)
}
