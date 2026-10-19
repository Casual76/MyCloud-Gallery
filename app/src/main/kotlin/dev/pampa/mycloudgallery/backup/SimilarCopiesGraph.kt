package dev.pampa.mycloudgallery.backup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.SimilarCandidate
import dev.pampa.mycloudgallery.core.imaging.budget.ImagingBudget
import dev.pampa.mycloudgallery.core.imaging.nas.NasBlobScopes
import dev.pampa.mycloudgallery.core.imaging.viewer.ContentImageOrigin
import dev.pampa.mycloudgallery.core.index.contract.ReplicaBlobs
import dev.pampa.mycloudgallery.core.index.contract.UnitPriority
import dev.pampa.mycloudgallery.core.library.similar.BitmapRasters
import dev.pampa.mycloudgallery.core.library.similar.CatalogSignatureSource
import dev.pampa.mycloudgallery.core.library.similar.CatalogSimilarCopies
import dev.pampa.mycloudgallery.core.library.similar.ChoiceOutcome
import dev.pampa.mycloudgallery.core.library.similar.ImageOriginPhonePixels
import dev.pampa.mycloudgallery.core.library.similar.MediaPair
import dev.pampa.mycloudgallery.core.library.similar.NasThumbnailPixels
import dev.pampa.mycloudgallery.core.library.similar.SeparatedPairs
import dev.pampa.mycloudgallery.core.library.similar.SignaturePixels
import dev.pampa.mycloudgallery.core.library.similar.SignatureSource
import dev.pampa.mycloudgallery.core.library.similar.SimilarChoices
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopies
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopiesGroup
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopiesProgress
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopiesReport
import dev.pampa.mycloudgallery.core.library.similar.VideoFrames
import dev.pampa.mycloudgallery.core.library.similar.VideoInstants
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStore
import dev.pampa.mycloudgallery.core.personal.store.SimilarCopiesChoices
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// LE COPIE SIMILI IN :app (Fase 5A, P7; docs/fase5-progetto.md §2.12, D36; note del P15). Il rilevatore e' di core-library
// (`CatalogSimilarCopies`), le scelte dell'utente sono dati personali di core-personal (`SimilarCopiesChoices`): i due moduli non si
// vedono, li lega `:app` con adattatori di poche righe. Il rilevatore e' uno solo per processo (stessa istanza per il viewer, la
// Diagnostica e il riepilogo del primo backup).

/** Le scelte dell'utente sulle copie simili (core-personal) viste dal rilevatore (core-library): un adattatore di una riga per metodo. */
class PersonalSimilarChoices(private val choices: SimilarCopiesChoices) : SimilarChoices {
  override suspend fun chosenCover(members: Collection<MediaId>): MediaId? = choices.chosenCover(members)

  override suspend fun separatedPairs(ids: Collection<MediaId>): SeparatedPairs =
    SeparatedPairs(choices.separatedPairs(ids).map { (a, b) -> MediaPair(a, b) })

  override suspend fun saveCover(chosen: MediaId, others: Collection<MediaId>) = choices.saveCover(chosen, others)

  override suspend fun saveSeparation(member: MediaId, others: Collection<MediaId>) = choices.saveSeparation(member, others)
}

/**
 * Il rilevatore delle copie simili dell'app: una facciata che risponde "niente" finche' non ci sono il catalogo e il registro dei dati
 * personali (senza, "Separa" andrebbe perso: meglio nessuna striscia), poi delega a [CatalogSimilarCopies]. Gira a tappe
 * ([requestRun]) su `Dispatchers.Default`, mai insieme a se' stesso, dentro i lavori che l'azzeramento ferma e aspetta.
 *
 * @param personal il registro dei dati personali (`NasOrganizer.Parts.personalStore`), null finche' il motore del NAS non e' partito.
 * @param work un lavoro che l'azzeramento puo' fermare e aspettare (`GalleryGraph.runIfActive`).
 */
class SimilarCopiesGraph(
  private val scope: CoroutineScope,
  private val context: Context,
  private val catalogProvider: suspend () -> MediaCatalog?,
  private val personal: () -> PersonalStore?,
  private val blobs: ReplicaBlobs,
  private val blobScopes: NasBlobScopes?,
  private val budget: ImagingBudget,
  private val uriOf: (MediaRef.Phone) -> Uri,
  private val work: suspend (suspend () -> Unit) -> Unit,
  /** Solo per i test d'integrazione: da dove vengono i pixel da firmare (il MediaStore finto non apre `content://`). Di norma null. */
  private val signatures: ((MediaCatalog) -> SignatureSource)? = null,
) : SimilarCopies {

  private val progressFlow = MutableStateFlow(SimilarCopiesProgress(pending = UNKNOWN, running = false))
  override val progress: StateFlow<SimilarCopiesProgress> = progressFlow.asStateFlow()

  private val awaitingFlow = MutableStateFlow<Set<Long>>(emptySet())

  /** Le chiavi di contenuto delle copie del NAS che aspettano la miniatura dell'indice: la Preparazione le fa per prime (`UnitPriority`). */
  val awaitingThumbnails: StateFlow<Set<Long>> = awaitingFlow.asStateFlow()

  /** Il rilevatore e' pronto (catalogo e dati personali ci sono)? Falso = il confronto non puo' partire. */
  val available: Boolean get() = instance != null || personal() != null

  /**
   * BK-11: una scelta ("Mostra come principale", "Separa") e' stata salvata fra i dati personali. La imposta `BackupGraph`, che conosce il
   * motore del NAS: chiede la sincronizzazione accorpata, come per preferiti e nascosti. Di serie niente.
   */
  @Volatile var onChoiceSaved: () -> Unit = {}

  private val buildLock = Mutex()

  @Volatile private var instance: CatalogSimilarCopies? = null
  private var relay: Job? = null
  private var running: Job? = null

  private suspend fun ensure(): CatalogSimilarCopies? {
    instance?.let { return it }
    return buildLock.withLock {
      instance?.let { return@withLock it }
      val catalog = catalogProvider() ?: return@withLock null
      val store = personal() ?: return@withLock null
      val choices = PersonalSimilarChoices(SimilarCopiesChoices(store, onSaved = { onChoiceSaved() }, resolve = catalog::resolve))
      val source: SignatureSource = signatures?.invoke(catalog) ?: CatalogSignatureSource(
        catalog = catalog,
        phone = ImageOriginPhonePixels(ContentImageOrigin(context.applicationContext.contentResolver), budget),
        nas = NasThumbnailPixels(blobs, blobScopes),
        video = AppVideoFrames(catalog, context.applicationContext, uriOf, blobs, blobScopes),
      )
      CatalogSimilarCopies(catalog, source, choices).also { built ->
        instance = built
        // Lo stato del rilevatore vero arriva a chi guarda la facciata (Diagnostica, riepilogo del primo backup).
        relay = scope.launch {
          launch { built.progress.collect { progressFlow.value = it } }
          launch { built.awaitingThumbnails.collect { awaitingFlow.value = it } }
        }
      }
    }
  }

  override suspend fun run(budget: Duration): SimilarCopiesReport =
    ensure()?.run(budget) ?: SimilarCopiesReport(0, 0, 0, 0, progressFlow.value.pending)

  override suspend fun groupOf(mediaId: MediaId): SimilarCopiesGroup? = ensure()?.groupOf(mediaId)

  override suspend fun chooseCover(mediaId: MediaId): ChoiceOutcome = ensure()?.chooseCover(mediaId) ?: ChoiceOutcome.NOT_IN_GROUP

  override suspend fun separate(mediaId: MediaId): ChoiceOutcome = ensure()?.separate(mediaId) ?: ChoiceOutcome.NOT_IN_GROUP

  /** Quanti candidati aspettano ancora il confronto, riletto dal catalogo (dopo META o una scansione del NAS). */
  suspend fun refreshProgress() {
    ensure()?.refreshProgress()
  }

  /** I media fra [ids] con una copia simile sul NAS (il riepilogo del primo backup, `SimilarCopyLookup`). Niente rilevatore = nessuno. */
  suspend fun withSimilarOnNas(ids: Collection<MediaId>): Set<MediaId> = ensure()?.withSimilarOnNas(ids) ?: emptySet()

  /**
   * Un giro del confronto, a tappe da [SLICE] (il costo di una firma e' qualche millisecondo ma i candidati possono essere migliaia):
   * dopo META, una sincronizzazione dei dati personali o dell'indice. Un giro alla volta; chi lo chiede non aspetta. Si ferma quando non
   * resta altro, quando la tappa non ha fatto progressi o dopo [MAX_SLICES] tappe (il resto al prossimo motivo).
   */
  fun requestRun() {
    synchronized(this) {
      if (running?.isActive == true) return
      running = scope.launch(Dispatchers.Default) {
        try {
          work {
            val detector = ensure() ?: return@work
            detector.refreshProgress()
            var lastPending = Int.MAX_VALUE
            repeat(MAX_SLICES) {
              val report = detector.run(SLICE)
              GalleryLog.d(TAG) { "tappa: firmati=${report.signed} gruppi=${report.grouped} in attesa=${report.pending}" }
              if (report.pending == 0 || (report.signed == 0 && report.grouped == 0 && report.pending >= lastPending)) return@work
              lastPending = report.pending
            }
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          GalleryLog.w(TAG) { "confronto delle copie simili non riuscito: ${e.javaClass.simpleName}" }
        }
      }
    }
  }

  /** Le chiavi da preparare per prime, come priorita' delle unita' di lavoro (`UnitPriority.visible`). */
  val priority: UnitPriority = object : UnitPriority {
    override val visible: StateFlow<Set<Long>> get() = awaitingThumbnails
  }

  private companion object {
    const val TAG = "SimilarCopies"
    const val UNKNOWN = 1
    val SLICE: Duration = 30.seconds
    const val MAX_SLICES = 20
  }
}

/**
 * I fotogrammi di un video per la firma visiva (§2.12 "Firma visiva", note del P15): due fotogrammi agli istanti di [VideoInstants].
 * **Telefono**: `MediaMetadataRetriever` sul file (mai il video intero in memoria). **NAS**: la classe `frames` della replica (i
 * fotogrammi chiave che la Preparazione ha prodotto), **mai il video**; chi non ha un fotogramma esattamente all'istante prende il piu'
 * vicino. Un video senza fotogrammi nell'indice aspetta la Preparazione (`Later` con la sua chiave).
 */
class AppVideoFrames(
  private val catalog: MediaCatalog,
  private val context: Context,
  private val uriOf: (MediaRef.Phone) -> Uri,
  private val blobs: ReplicaBlobs,
  private val scopes: NasBlobScopes?,
) : VideoFrames {

  override suspend fun frames(candidate: SimilarCandidate, instants: VideoInstants): SignaturePixels {
    val id = candidate.mediaId
    val phoneRef = catalog.phoneRefs(listOf(id))[id]
    if (phoneRef != null) {
      phoneFrames(phoneRef, instants)?.let { return SignaturePixels.Ready(it) }
    }
    val nasRef = catalog.nasRefs(listOf(id))[id]
    if (nasRef != null) return nasFrames(nasRef, instants)
    return if (phoneRef != null) SignaturePixels.Never else SignaturePixels.Later()
  }

  /** I fotogrammi dal file del telefono, dritti e ridotti. Null se il file non si legge. */
  private suspend fun phoneFrames(ref: MediaRef.Phone, instants: VideoInstants): List<dev.pampa.mycloudgallery.core.library.similar.Raster>? =
    withContext(Dispatchers.IO) {
      val retriever = MediaMetadataRetriever()
      try {
        retriever.setDataSource(context, uriOf(ref))
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val storedWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val storedHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        val out = ArrayList<dev.pampa.mycloudgallery.core.library.similar.Raster>(instants.atMs.size)
        for (at in instants.atMs) {
          val frame = retriever.getFrameAtTime(at * MICROS, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@withContext null
          val upright = uprightOf(frame, rotation, storedWidth, storedHeight)
          try {
            out += BitmapRasters.of(scaled(upright))
          } finally {
            if (upright !== frame) upright.recycle()
            frame.recycle()
          }
        }
        out.takeIf { it.isNotEmpty() }
      } catch (e: CancellationException) {
        throw e
      } catch (_: RuntimeException) {
        null
      } catch (_: IOException) {
        null
      } finally {
        runCatching { retriever.release() }
      }
    }

  /**
   * Il fotogramma dritto: `MediaMetadataRetriever` a volte lo restituisce gia' ruotato e a volte no (secondo il decoder); come per la
   * Preparazione si decide dalle proporzioni (rotazione 90/270 e video non quadrato: se il fotogramma ha lo stesso verso del video
   * memorizzato non e' stato ruotato; negli altri casi si suppone che il sistema ruoti). Un errore qui porta a non unire (nel dubbio no).
   */
  private fun uprightOf(frame: Bitmap, rotation: Int, storedWidth: Int?, storedHeight: Int?): Bitmap {
    if (rotation != 90 && rotation != 270) return frame
    if (storedWidth == null || storedHeight == null || storedWidth == storedHeight || frame.width == frame.height) return frame
    val sameVerse = (storedWidth > storedHeight) == (frame.width > frame.height)
    if (!sameVerse) return frame
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
  }

  private fun scaled(bitmap: Bitmap): Bitmap {
    val longSide = maxOf(bitmap.width, bitmap.height)
    if (longSide <= SIDE) return bitmap
    val scale = SIDE.toFloat() / longSide
    val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, width, height, true)
  }

  /** I fotogrammi chiave dell'indice del NAS piu' vicini agli istanti chiesti. */
  private suspend fun nasFrames(ref: MediaRef.Nas, instants: VideoInstants): SignaturePixels {
    val scope = try {
      scopes?.scopeOf(ref)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
    return withContext(Dispatchers.IO) {
      val location = (if (scope == null) blobs.locate(ref.contentKey, SegmentClass.FRAMES) else blobs.locate(ref.contentKey, SegmentClass.FRAMES, scope))
        ?: return@withContext SignaturePixels.Later(ref.contentKey)
      val body = try {
        blobs.read(location)
      } catch (_: IOException) {
        return@withContext SignaturePixels.Later(ref.contentKey)
      }
      val keyFrames = parseFrames(body) ?: return@withContext SignaturePixels.Never
      val rasters = ArrayList<dev.pampa.mycloudgallery.core.library.similar.Raster>(instants.atMs.size)
      val used = HashSet<Int>()
      for (at in instants.atMs) {
        val nearest = keyFrames.indices.filter { it !in used }.minByOrNull { abs(keyFrames[it].first - at) } ?: break
        used += nearest
        val jpeg = keyFrames[nearest].second
        val bitmap = decode(jpeg) ?: return@withContext SignaturePixels.Never
        try {
          rasters += BitmapRasters.of(bitmap)
        } finally {
          bitmap.recycle()
        }
      }
      if (rasters.isEmpty()) SignaturePixels.Never else SignaturePixels.Ready(rasters)
    }
  }

  /** Il corpo di una voce `frames` (§5.5-bis): conteggio (1 byte) e, per ognuno, istante (4) + lunghezza (4) + JPEG. Difensivo. */
  private fun parseFrames(body: ByteBuffer): List<Pair<Int, ByteArray>>? {
    val b = body.duplicate()
    if (b.remaining() < 1) return null
    val count = b.get().toInt() and 0xff
    if (count < 1 || count > MAX_FRAMES) return null
    val out = ArrayList<Pair<Int, ByteArray>>(count)
    repeat(count) {
      if (b.remaining() < 8) return null
      val at = b.int
      val length = b.int
      if (at < 0 || length <= 0 || length > MAX_FRAME_BYTES || b.remaining() < length) return null
      val jpeg = ByteArray(length)
      b.get(jpeg)
      out += at to jpeg
    }
    return out
  }

  /** Un JPEG ridotto: prima le dimensioni, poi la decodifica campionata (mai la bitmap intera di un fotogramma enorme). */
  private fun decode(jpeg: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIDE) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return try {
      BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
    } catch (_: OutOfMemoryError) {
      null
    }
  }

  private companion object {
    const val MICROS = 1_000L
    const val SIDE = 512
    const val MAX_FRAMES = 32
    const val MAX_FRAME_BYTES = 2 * 1024 * 1024
  }
}
