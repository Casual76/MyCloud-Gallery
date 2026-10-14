package dev.pampa.mycloudgallery.core.prepare.engine

import dev.pampa.mycloudgallery.core.imaging.decode.ImageBytes
import dev.pampa.mycloudgallery.core.prepare.codec.CodecAvailability
import dev.pampa.mycloudgallery.core.prepare.codec.DecoderFamily
import dev.pampa.mycloudgallery.core.prepare.codec.PlatformCodecs
import dev.pampa.mycloudgallery.core.prepare.contract.PrepBudget
import dev.pampa.mycloudgallery.core.prepare.contract.PrepResult
import dev.pampa.mycloudgallery.core.prepare.contract.PrepSpec
import dev.pampa.mycloudgallery.core.prepare.contract.PrepareSource
import dev.pampa.mycloudgallery.core.prepare.contract.Preparer
import dev.pampa.mycloudgallery.core.prepare.photo.FileImageBytes
import dev.pampa.mycloudgallery.core.prepare.photo.PhotoPreparer
import dev.pampa.mycloudgallery.core.prepare.spill.TempSpill
import dev.pampa.mycloudgallery.core.prepare.video.MediaMetadataFrameGrabbers
import dev.pampa.mycloudgallery.core.prepare.video.VideoFrameGrabberFactory
import dev.pampa.mycloudgallery.core.prepare.video.VideoPreparer
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Il preparatore vero (docs/fase4-progetto.md §2.4, D12, D14, D16): una foto si legge **una volta** (hash completo e `qh1`
 * gratis) e si decodifica una volta alla taglia piu' grande richiesta; un video non si scarica mai (indice e fotogrammi
 * chiave a intervalli). Non conosce il NAS ne' SMB: riceve [PrepareSource] con i byte (`MediaBytes`) o un file locale, quindi
 * lo usa anche il backup della Fase 5 con i byte del telefono.
 *
 * Uno per processo: i cancelli di memoria (una decodifica pesante alla volta, una foto grande in memoria alla volta) sono
 * suoi, e valgono per tutte le preparazioni che lo condividono.
 *
 * @param budget i tetti di memoria e concorrenza ([PrepBudget]).
 * @param tempDir `cacheDir/prep/`: le foto oltre 64 MiB passano da qui. All'avvio di un giro [sweepStale] toglie gli orfani.
 * @param codecs i decoder del dispositivo, interrogati solo quando una decodifica fallisce (o prima, per un video: il codec si
 *   legge dall'indice).
 * @param decoderFamily la famiglia scritta in `fail.dec` quando manca un decoder (D14).
 * @param frames come si aprono i video (di norma `MediaMetadataRetriever`).
 * @param cpu dove girano decodifica e codifica (blocchi lunghi: mai il thread principale).
 * @param probe una finestra sul picco di memoria delle bitmap, per i test e la Diagnostica.
 */
class DefaultPreparer internal constructor(
  budget: PrepBudget,
  tempDir: File,
  codecs: CodecAvailability,
  decoderFamily: String,
  frames: VideoFrameGrabberFactory,
  cpu: CoroutineDispatcher,
  probe: PrepProbe,
  fileImages: (File) -> ImageBytes,
) : Preparer {

  constructor(
    budget: PrepBudget,
    tempDir: File,
    codecs: CodecAvailability = PlatformCodecs,
    decoderFamily: String = DecoderFamily.ofDevice(),
    frames: VideoFrameGrabberFactory = MediaMetadataFrameGrabbers,
    cpu: CoroutineDispatcher = Dispatchers.Default,
    probe: PrepProbe = PrepProbe.NONE,
  ) : this(budget, tempDir, codecs, decoderFamily, frames, cpu, probe, ::FileImageBytes)

  private val spill = TempSpill(tempDir)
  private val gate = PrepGate(budget)
  private val photos = PhotoPreparer(budget, gate, codecs, decoderFamily, spill, cpu, probe, fileImages = fileImages)
  private val videos = VideoPreparer(budget, gate, codecs, decoderFamily, frames, cpu)

  override suspend fun prepare(source: PrepareSource, spec: PrepSpec): PrepResult =
    if (source.kind.isVideo) videos.prepare(source, spec) else photos.prepare(source, spec)

  /** Toglie i temporanei orfani di un processo morto (nessuno li usa). Si chiama all'inizio di un giro. */
  fun sweepStale(): Int = spill.sweepStale()
}
