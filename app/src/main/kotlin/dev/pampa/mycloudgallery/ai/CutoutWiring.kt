package dev.pampa.mycloudgallery.ai

import android.content.ComponentCallbacks2
import android.content.ContentResolver
import android.net.Uri
import dev.pampa.mycloudgallery.core.ai.contract.AiDiag
import dev.pampa.mycloudgallery.core.ai.contract.AlphaMask
import dev.pampa.mycloudgallery.core.ai.contract.CutoutResult
import dev.pampa.mycloudgallery.core.ai.contract.SubjectCutter
import dev.pampa.mycloudgallery.core.ai.cutout.CutoutSource
import dev.pampa.mycloudgallery.core.ai.cutout.SubjectCutting
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.imaging.viewer.OriginalFetcher
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaWriter
import dev.pampa.mycloudgallery.core.mediastore.write.CutoutPhoneSaver
import dev.pampa.mycloudgallery.core.mediastore.write.MediaStorePhoneMediaWriter
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.models.contract.ModelFiles
import dev.pampa.mycloudgallery.core.models.contract.ModelId
import dev.pampa.mycloudgallery.core.models.contract.VerifiedModel
import dev.pampa.mycloudgallery.feature.integrations.share.CacheUris
import dev.pampa.mycloudgallery.feature.integrations.share.ShareCache
import dev.pampa.mycloudgallery.feature.viewer.cutout.CutoutNewPhoto
import dev.pampa.mycloudgallery.feature.viewer.cutout.CutoutPngSource
import dev.pampa.mycloudgallery.feature.viewer.cutout.CutoutSaveResult
import dev.pampa.mycloudgallery.feature.viewer.cutout.CutoutSaver
import dev.pampa.mycloudgallery.feature.viewer.cutout.CutoutShareFiles
import dev.pampa.mycloudgallery.feature.viewer.cutout.PhoneCutoutNewPhoto
import dev.pampa.mycloudgallery.feature.viewer.cutout.StagedCutout
import dev.pampa.mycloudgallery.feature.viewer.cutout.ViewerCutout
import dev.pampa.mycloudgallery.feature.viewer.cutout.ViewerMask
import dev.pampa.mycloudgallery.feature.viewer.cutout.ViewerRect
import dev.pampa.mycloudgallery.feature.viewer.cutout.ViewerSubjectCutter
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// IL RITAGLIO DEL SOGGETTO collegato in :app (docs/fase6-progetto.md §2.10, D44, D45, D47; Fase 6, P11b, come P13 lo descrive in docs/decisioni.md):
// MagicTouch su LiteRT dentro `SubjectCutting` (core-ai), gli adattatori delle porte del viewer (`ViewerSubjectCutter`, `CutoutSaver`) sopra il
// ritagliatore, il PNG per Copia e Condividi nella cartella del `FileProvider` che c'e' gia', il salvataggio come foto nuova nella cartella
// `Ritagli/` del telefono (e, da li', la sorgente "Ritagli" del backup). Il viewer non vede core-ai (Phase6ArchitectureTest): i tipi si
// convertono qui. Mai rete (F67): la sorgente e' sempre cio' che il dispositivo ha gia'.

/**
 * Il modello del ritaglio e' dentro l'APK (asset di core-ai): la prima volta che serve lo si mette fra i modelli verificati ([ensure]: una
 * copia locale, senza rete) e si riapre. Il verificatore resta quello di sempre (dimensione e SHA-256 a ogni apertura, D16).
 */
class EnsuringModelFiles(private val files: ModelFiles, private val ensure: suspend (ModelId) -> Unit) : ModelFiles {
  override suspend fun open(id: ModelId): VerifiedModel? = files.open(id) ?: run {
    ensure(id)
    files.open(id)
  }
}

/**
 * Il ritagliatore del processo: `SubjectCutting` costruito al primo uso (il modello da 6 MB si apre al primo ritaglio e occupa circa 240 MB di
 * memoria, P13) e **rilasciato** [idle] dopo l'ultimo uso e a memoria bassa ([trim]); anche nell'azzeramento ([release], `AiGraph.stop`). Il
 * riferimento all'immagine trattenuta e' morbido (`SubjectCutting`): il sistema la riprende da solo se la memoria serve.
 *
 * @param factory il ritagliatore: `MagicTouchCutter.create(files)` in produzione, quello finto nei test d'integrazione.
 * @param maxPixels `ImagingBudget.of(context).viewerMaxPixels` (E-M1-M4): 12,8 MP sull'S25, meno dove `memoryClass` e' piu' piccola.
 */
class CutoutRuntime(
  private val scope: CoroutineScope,
  private val factory: () -> SubjectCutter,
  private val maxPixels: Long,
  private val idle: Duration = 3.minutes,
) {
  private val created = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SubjectCutting(factory(), maxPixels = maxPixels) }

  @Volatile private var idleJob: Job? = null

  @Volatile private var stopped = false

  /** Vero se il ritagliatore e' stato costruito (per i test e la Diagnostica). */
  val loaded: Boolean get() = created.isInitialized()

  /** Il ritagliatore; ogni uso riarma il rilascio dopo [idle]. Dopo [stop] non si ritaglia piu' (azzeramento). */
  fun cutting(): SubjectCutting {
    check(!stopped) { "Ritagliatore fermato." }
    val cutting = created.value
    idleJob?.cancel()
    idleJob = scope.launch {
      delay(idle)
      close()
    }
    return cutting
  }

  /** Rilascia il modello e l'immagine trattenuta; il prossimo ritaglio li riapre da solo. Idempotente. */
  suspend fun release() {
    idleJob?.cancel()
    idleJob = null
    close()
  }

  private suspend fun close() {
    if (created.isInitialized()) runCatching { created.value.release() }
  }

  /** `onTrimMemory`: da "memoria bassa in primo piano" in su si rilascia tutto (il modello pesa piu' di quanto l'app puo' permettersi). */
  fun trim(level: Int) {
    if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
    if (!created.isInitialized()) return
    scope.launch { release() }
  }

  /** Per sempre in questo processo (azzeramento): rilascia e rifiuta altri usi. */
  suspend fun stop() {
    stopped = true
    release()
  }
}

/**
 * Dove sta l'immagine da ritagliare, **la migliore gia' sul dispositivo** (D44, mai uno scaricamento solo per ritagliare): per un media del telefono
 * la sua riga del MediaStore; per uno del NAS l'originale gia' portato qui (aperto nel viewer, `OriginalFetcher.localFile`) e altrimenti l'HQ della
 * replica ([hq], in memoria). Un media sintetico o senza nessuna immagine locale: null.
 */
class CutoutSources(
  private val resolver: ContentResolver,
  private val uriOf: (MediaRef.Phone) -> Uri,
  private val originals: OriginalFetcher?,
  private val hq: ReplicaHq?,
) {
  suspend fun of(ref: MediaRef): CutoutSource? = when (ref) {
    is MediaRef.Phone -> CutoutSource.OfUri(resolver, uriOf(ref))
    is MediaRef.Nas -> originals?.localFile(ref)?.let { CutoutSource.OfFile(it) } ?: hq?.bytes(ref)?.let { CutoutSource.OfBytes(it) }
    else -> null
  }
}

/** Il nome del file da cui viene l'ultimo ritaglio, per dare il nome alla foto nuova (`<origine>-ritaglio.png`): dato dell'utente, mai nei log. */
class RememberedSourceName {
  private val last = AtomicReference<Pair<MediaRef, String>?>(null)

  fun remember(ref: MediaRef, name: String?) {
    last.set(name?.let { ref to it })
  }

  fun nameOf(ref: MediaRef): String? = last.get()?.takeIf { it.first == ref }?.second
}

/**
 * Il ritagliatore del viewer ([ViewerSubjectCutter]): la sorgente di [ref], il punto normalizzato, `SubjectCutting`. Un soggetto chiaro diventa
 * [ViewerCutout.Subject] (maschera alfa e rettangolo in frazioni), "nessun soggetto chiaro" (E-V10) [ViewerCutout.NoSubject], un guasto
 * [ViewerCutout.Failed] con il codice `CUTOUT_FAILED`, che finisce anche nella Diagnostica. Ricorda il nome del file di origine per il salvataggio.
 */
class AppViewerSubjectCutter(
  private val runtime: CutoutRuntime,
  private val sources: CutoutSources,
  private val catalog: suspend () -> MediaCatalog?,
  private val names: RememberedSourceName,
  private val diag: (DiagCode) -> Unit,
) : ViewerSubjectCutter {

  override suspend fun cut(id: MediaId, ref: MediaRef, x: Float, y: Float): ViewerCutout {
    val source = sources.of(ref) ?: return failed()
    if (names.nameOf(ref) == null) names.remember(ref, sourceNameOf(id, ref))
    val result = try {
      runtime.cutting().cut(id.value, source, x, y)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return failed()
    }
    return when (result) {
      is CutoutResult.Subject -> {
        val w = result.mask.width
        val h = result.mask.height
        val b = result.bounds
        ViewerCutout.Subject(
          ViewerMask(w, h, result.mask.alpha()),
          ViewerRect(b.left.toFloat() / w, b.top.toFloat() / h, b.right.toFloat() / w, b.bottom.toFloat() / h),
        )
      }
      CutoutResult.NoClearSubject -> ViewerCutout.NoSubject
      is CutoutResult.Failed -> failed()
    }
  }

  private fun failed(): ViewerCutout {
    diag(AiDiag.CUTOUT_FAILED)
    return ViewerCutout.Failed(AiDiag.CUTOUT_FAILED.id)
  }

  private suspend fun sourceNameOf(id: MediaId, ref: MediaRef): String? {
    val media = catalog()?.media(id) ?: return null
    return when (ref) {
      is MediaRef.Phone -> media.locations.firstNotNullOfOrNull { (it.location as? Location.Phone)?.displayName }
      is MediaRef.Nas -> media.locations.firstOrNull { it.id.value == ref.locationId }?.let { (it.location as? Location.Nas)?.path?.fileName }
      else -> null
    }
  }
}

/** Il PNG del ritaglio ([CutoutPngSource]): la stessa chiave e la stessa sorgente del ritaglio, con o senza contorno. Null se la sorgente non c'e' piu'. */
class AppCutoutPng(private val runtime: CutoutRuntime, private val sources: CutoutSources) : CutoutPngSource {
  override suspend fun png(id: MediaId, ref: MediaRef, cutout: ViewerCutout.Subject, outline: Boolean): ByteArray? {
    val source = sources.of(ref) ?: return null
    val mask = AlphaMask(cutout.mask.width, cutout.mask.height, cutout.mask.alpha())
    return runtime.cutting().png(id.value, source, mask, outline)
  }
}

/**
 * Dove si mette il PNG per Copia e Condividi ([CutoutShareFiles]): `cacheDir/share/<token>/ritaglio.png`, la cartella del `FileProvider` che c'e'
 * gia' (nessun percorso nuovo); il file lo toglie la pulizia all'avvio (`ShareCache.sweepAtStart`). Null se non si e' potuto scrivere.
 */
class CutoutFiles(
  private val cache: ShareCache,
  private val uris: CacheUris,
  private val io: CoroutineDispatcher = Dispatchers.IO,
) : CutoutShareFiles {
  override suspend fun stage(png: ByteArray): StagedCutout? = withContext(io) {
    try {
      val file = File(cache.newShareDir(), FILE)
      file.writeBytes(png)
      StagedCutout(uris.uriFor(file))
    } catch (e: IOException) {
      null
    } catch (e: IllegalArgumentException) {
      // Il `FileProvider` non serve quel percorso.
      null
    }
  }

  private companion object {
    const val FILE = "ritaglio.png"
  }
}

/**
 * "Salva come nuova foto" (D47): il PNG diventa una foto del telefono in `Pictures/MyCloud Gallery/Ritagli/` (in debug con un account di prova la
 * cartella recintata di D13, [folder]) con la data di adesso e, dai Nascosti, nascosta (E-V11). Il catalogo c'e' solo a famiglia pronta: il
 * salvatore si costruisce al primo salvataggio, uno scrittore per cartella (la recinzione puo' cambiare quando arriva l'account).
 */
class AppCutoutNewPhoto(
  private val catalog: suspend () -> MediaCatalog?,
  private val writerFor: (relativePath: String) -> PhoneMediaWriter,
  private val folder: () -> String,
  private val nowMillis: () -> Long,
) : CutoutNewPhoto {
  private val writers = HashMap<String, PhoneMediaWriter>()
  private val savers = HashMap<String, CutoutNewPhoto>()

  override suspend fun save(png: ByteArray, sourceName: String?, hidden: Boolean): CutoutSaveResult {
    val catalog = catalog() ?: return CutoutSaveResult.Failed
    val folder = folder()
    val saver = synchronized(savers) {
      savers.getOrPut(folder) { PhoneCutoutNewPhoto(CutoutPhoneSaver(writerOf(folder), catalog), nowMillis) }
    }
    return saver.save(png, sourceName, hidden)
  }

  /** All'avvio: le righe in attesa che un salvataggio interrotto ha lasciato nella cartella dei ritagli si tolgono (`IS_PENDING`). */
  suspend fun purgeOrphans(): Int = (writerOf(folder()) as? MediaStorePhoneMediaWriter)?.purgeOrphans() ?: 0

  private fun writerOf(folder: String): PhoneMediaWriter = synchronized(writers) { writers.getOrPut(folder) { writerFor(folder) } }
}

/** Il [CutoutSaver] con i codici della Diagnostica (D40, solo il codice): un salvataggio fallito e' `CUTOUT_SAVE_FAILED`, una copia o condivisione fallita `CUTOUT_FAILED`. */
class DiagCutoutSaver(private val inner: CutoutSaver, private val diag: (DiagCode) -> Unit) : CutoutSaver {
  override suspend fun copy(id: MediaId, ref: MediaRef, cutout: ViewerCutout.Subject, outline: Boolean): Boolean =
    inner.copy(id, ref, cutout, outline).also { if (!it) diag(AiDiag.CUTOUT_FAILED) }

  override suspend fun share(id: MediaId, ref: MediaRef, cutout: ViewerCutout.Subject, outline: Boolean): Boolean =
    inner.share(id, ref, cutout, outline).also { if (!it) diag(AiDiag.CUTOUT_FAILED) }

  override suspend fun saveAsNewPhoto(id: MediaId, ref: MediaRef, cutout: ViewerCutout.Subject, outline: Boolean, fromHidden: Boolean): CutoutSaveResult =
    inner.saveAsNewPhoto(id, ref, cutout, outline, fromHidden).also { if (it is CutoutSaveResult.Failed) diag(AiDiag.CUTOUT_SAVE_FAILED) }
}

/** Dove Copia e Condividi mettono il PNG: la cartella di condivisione che c'e' gia' e il suo indirizzo (`IntegrationsGraph.shareCache` e `uris`). */
class CutoutShareEnv(val cache: ShareCache, val uris: CacheUris)
