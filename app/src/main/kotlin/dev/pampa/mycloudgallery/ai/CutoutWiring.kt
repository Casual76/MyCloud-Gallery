package dev.pampa.mycloudgallery.ai

import android.app.Activity
import android.app.Application
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import dev.pampa.mycloudgallery.core.ai.contract.AiDiag
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
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
import dev.pampa.mycloudgallery.core.models.contract.EnsureResult
import dev.pampa.mycloudgallery.core.models.contract.ModelFailCode
import dev.pampa.mycloudgallery.core.models.contract.ModelFiles
import dev.pampa.mycloudgallery.core.models.contract.ModelId
import dev.pampa.mycloudgallery.core.models.contract.VerifiedModel
import dev.pampa.mycloudgallery.feature.integrations.share.CacheUris
import dev.pampa.mycloudgallery.feature.integrations.share.ShareCache
import dev.pampa.mycloudgallery.feature.integrations.share.ShareHolder
import dev.pampa.mycloudgallery.feature.integrations.share.ShareLease
import dev.pampa.mycloudgallery.feature.integrations.share.UriRevoker
import dev.pampa.mycloudgallery.feature.viewer.cutout.CutoutClipMarker
import dev.pampa.mycloudgallery.feature.viewer.cutout.CutoutFailure
import dev.pampa.mycloudgallery.feature.viewer.cutout.CutoutNewPhoto
import dev.pampa.mycloudgallery.feature.viewer.cutout.CutoutPngSource
import dev.pampa.mycloudgallery.feature.viewer.cutout.CutoutSaveResult
import dev.pampa.mycloudgallery.feature.viewer.cutout.CutoutSaver
import dev.pampa.mycloudgallery.feature.viewer.cutout.CutoutShareFiles
import dev.pampa.mycloudgallery.feature.viewer.cutout.PhoneCutoutNewPhoto
import dev.pampa.mycloudgallery.feature.viewer.cutout.StagedCutout
import dev.pampa.mycloudgallery.feature.viewer.cutout.StagedLifecycle
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
class EnsuringModelFiles(private val files: ModelFiles, private val ensure: suspend (ModelId) -> EnsureResult) : ModelFiles {

  /**
   * L'ultimo tentativo di mettere il modello fra quelli verificati e' fallito per **mancanza di spazio** (VR-2): chi non riesce ad aprirlo lo dice
   * ("Spazio insufficiente") invece del generico "Ritaglio non riuscito". Torna falso appena il modello si apre.
   */
  @Volatile var lastNoSpace: Boolean = false
    private set

  override suspend fun open(id: ModelId): VerifiedModel? {
    files.open(id)?.let {
      lastNoSpace = false
      return it
    }
    val result = ensure(id)
    lastNoSpace = result is EnsureResult.Failed && result.code == ModelFailCode.NO_SPACE
    return files.open(id)
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
  /** VR-2: il modello dell'APK non si e' potuto mettere fra i verificati per mancanza di spazio (`EnsuringModelFiles.lastNoSpace`). */
  private val noSpace: () -> Boolean = { false },
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
    } catch (e: OutOfMemoryError) {
      // R20: una foto grande con poca memoria non fa cadere l'app.
      return failed(CutoutFailure.LOW_MEMORY)
    }
    return try {
      when (result) {
        is CutoutResult.Subject -> {
          val w = result.mask.width
          val h = result.mask.height
          val b = result.bounds
          ViewerCutout.Subject(
            // R20: la maschera passa al viewer senza una seconda copia (12,6 MB per una foto da 12 megapixel): `alpha()` e' gia' una copia nostra.
            ViewerMask.adopting(w, h, result.mask.alpha()),
            ViewerRect(b.left.toFloat() / w, b.top.toFloat() / h, b.right.toFloat() / w, b.bottom.toFloat() / h),
          )
        }
        CutoutResult.NoClearSubject -> ViewerCutout.NoSubject
        is CutoutResult.Failed -> failed(
          when {
            // Il motore stesso dice che la memoria e' finita (R20): "chiudi qualche app", non "riprova e basta".
            result.error == AiEngineError.OUT_OF_MEMORY -> CutoutFailure.LOW_MEMORY
            noSpace() -> CutoutFailure.NO_SPACE
            else -> CutoutFailure.OTHER
          },
        )
      }
    } catch (e: OutOfMemoryError) {
      failed(CutoutFailure.LOW_MEMORY)
    }
  }

  private fun failed(reason: CutoutFailure = CutoutFailure.OTHER): ViewerCutout {
    diag(AiDiag.CUTOUT_FAILED)
    return ViewerCutout.Failed(AiDiag.CUTOUT_FAILED.id, reason)
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
    // R20: l'alfa si presta senza copiarlo (`AlphaMask` ne fa una sua): una copia da 12,6 MB in meno proprio dove la memoria e' piu' stretta.
    val mask = cutout.mask.readAlpha { alpha -> AlphaMask(cutout.mask.width, cutout.mask.height, alpha) }
    return runtime.cutting().png(id.value, source, mask, outline)
  }
}

/**
 * Dove si mette il PNG per Copia e Condividi ([CutoutShareFiles]): `cacheDir/share/<token>/ritaglio.png`, la cartella del `FileProvider` che c'e'
 * gia' (nessun percorso nuovo). Il file **non resta per sempre** (R18): ha un [ShareLease] (permesso di lettura e cartella) che si rilascia secondo come
 * finisce. Condividi: passa al custode delle condivisioni ([holder]), che lo toglie quando l'altra app ha finito (ritorno nell'app con un minuto di margine,
 * al piu' mezz'ora). Copia: passa a [clipboard], che ne tiene uno solo (il successivo sostituisce il precedente), lo toglie quando gli appunti cambiano e in
 * ogni caso dopo poco. Non partita o non riuscita: subito. Se il processo muore la pulizia all'avvio (`ShareCache.sweepAtStart`) toglie i file e
 * [CutoutClipboard.clearIfOurs] svuota gli appunti che li citavano (VR-3). Null se non si e' potuto scrivere.
 */
class CutoutFiles(
  private val cache: ShareCache,
  private val uris: CacheUris,
  private val io: CoroutineDispatcher = Dispatchers.IO,
  private val holder: ShareHolder? = null,
  private val revoker: UriRevoker = UriRevoker { },
  private val clipboard: CutoutClipboard? = null,
) : CutoutShareFiles {
  override suspend fun stage(png: ByteArray): StagedCutout? = withContext(io) {
    try {
      val dir = cache.newShareDir()
      val file = File(dir, FILE)
      file.writeBytes(png)
      val uri = uris.uriFor(file)
      StagedCutout(uri, Lifecycle(ShareLease(listOf(uri), listOf(dir), revoker, cache)))
    } catch (e: IOException) {
      null
    } catch (e: IllegalArgumentException) {
      // Il `FileProvider` non serve quel percorso.
      null
    }
  }

  /** Chi tiene il file secondo come e' finito (R18). Senza custode o appunti (i test della sola parte di `:app`) il file resta fino alla pulizia all'avvio. */
  private inner class Lifecycle(private val lease: ShareLease) : StagedLifecycle {
    override fun shared() {
      holder?.hold(lease)
    }

    override fun copied() {
      clipboard?.place(lease)
    }

    override fun discard() = lease.release()
  }

  private companion object {
    const val FILE = "ritaglio.png"
  }
}

/**
 * Il PNG del ritaglio negli appunti (R18, VR-3). Il `ClipData` porta un indirizzo del `FileProvider` e il permesso di lettura a chi incolla: finche' stava in
 * `cacheDir/share/` fino all'avvio dopo, qualunque app in primo piano o tastiera poteva leggere il PNG per giorni, e dopo un riavvio gli appunti
 * puntavano a un file tolto dalla pulizia. Adesso:
 *  - ne vale **uno solo**: una copia nuova toglie il file della precedente;
 *  - se gli appunti cambiano (l'utente copia altro) il file si toglie subito;
 *  - comunque dopo [ttlMs] (10 minuti) il file si toglie e gli appunti, se hanno ancora il nostro ritaglio, si svuotano;
 *  - all'avvio gli appunti col nostro ritaglio si svuotano ([clearIfOurs]): la pulizia ha tolto il file e non devono puntare al niente. La descrizione degli
 *    appunti si legge solo da un'app in primo piano (da Android 10): a processo appena nato non c'e' ancora una schermata, quindi lo stesso controllo si rifa'
 *    a ogni schermata che torna davanti, finche' non c'e' un ritaglio nostro in corso ([onForeground]).
 * Il ritaglio nostro si riconosce dal segno nella descrizione ([CutoutClipMarker]), senza leggere il contenuto degli appunti.
 */
class CutoutClipboard(
  private val context: Context,
  private val scope: CoroutineScope,
  private val ttlMs: Long = TTL_MS,
) {
  private val lock = Any()
  private var current: ShareLease? = null
  private var timer: Job? = null

  private val listener = ClipboardManager.OnPrimaryClipChangedListener {
    // Gli appunti sono cambiati: se non sono piu' il nostro ritaglio, nessuno ha piu' bisogno del file.
    if (!isOurs(context)) release()
  }

  init {
    manager(context)?.addPrimaryClipChangedListener(listener)
    (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(
      object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) = onForeground()

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
      },
    )
  }

  /**
   * Una schermata dell'app e' in primo piano (VR-3): se in questo processo non c'e' un ritaglio nostro in corso e gli appunti ne citano uno (di un processo
   * morto, o scaduto con l'app in secondo piano), si svuotano: il file non c'e' piu'.
   */
  fun onForeground() {
    val idle = synchronized(lock) { current == null }
    if (idle) clearIfOurs(context)
  }

  /** [lease] e' il file appena messo negli appunti: sostituisce il precedente e scade fra [ttlMs]. */
  fun place(lease: ShareLease) {
    val previous: ShareLease?
    synchronized(lock) {
      previous = current
      current = lease
      timer?.cancel()
      timer = scope.launch {
        delay(ttlMs)
        expire(lease)
      }
    }
    previous?.release()
  }

  /** Toglie il file di adesso, se c'e' (gli appunti sono cambiati, l'azzeramento). */
  fun release() {
    val lease = synchronized(lock) {
      timer?.cancel()
      timer = null
      current.also { current = null }
    }
    lease?.release()
  }

  private fun expire(lease: ShareLease) {
    val mine = synchronized(lock) {
      if (current === lease) {
        current = null
        timer = null
        true
      } else {
        false
      }
    }
    if (!mine) return
    clearIfOurs(context)
    lease.release()
  }

  companion object {
    /** Quanto resta il ritaglio copiato: il tempo di aprire un'altra app e incollare. */
    const val TTL_MS: Long = 10L * 60 * 1000

    private fun manager(context: Context): ClipboardManager? = context.getSystemService(ClipboardManager::class.java)

    /** Gli appunti hanno adesso un ritaglio nostro (dal segno nella descrizione: il contenuto, da fuori primo piano, non si puo' leggere). */
    fun isOurs(context: Context): Boolean = try {
      manager(context)?.primaryClipDescription?.extras?.getBoolean(CutoutClipMarker.EXTRA) == true
    } catch (_: SecurityException) {
      false
    }

    /** Svuota gli appunti se hanno un ritaglio nostro (all'avvio, alla scadenza): mai quelli di altre app. */
    fun clearIfOurs(context: Context) {
      try {
        if (isOurs(context)) manager(context)?.clearPrimaryClip()
      } catch (_: SecurityException) {
        // Gli appunti non si toccano da qui (app non in primo piano): il file e' comunque tolto, resta un indirizzo che non si apre piu'.
      }
    }
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
  /**
   * Un ritaglio dai Nascosti e' stato salvato (VR-1, R19): il segno "nascosto" e' gia' nel catalogo, e va ai dispositivi dello stesso account come ogni
   * "Nascondi" (`personalChanged()`), senza aspettare il giro periodico dei dati personali (fino a 6 ore: nel frattempo il tablet lo avrebbe mostrato in Libreria).
   */
  private val onHiddenSaved: () -> Unit = {},
) : CutoutNewPhoto {
  private val writers = HashMap<String, PhoneMediaWriter>()
  private val savers = HashMap<String, CutoutNewPhoto>()

  override suspend fun save(png: ByteArray, sourceName: String?, hidden: Boolean): CutoutSaveResult {
    val catalog = catalog() ?: return CutoutSaveResult.Failed
    val folder = folder()
    val saver = synchronized(savers) {
      savers.getOrPut(folder) { PhoneCutoutNewPhoto(CutoutPhoneSaver(writerOf(folder), catalog), nowMillis) }
    }
    val result = saver.save(png, sourceName, hidden)
    if (hidden && result is CutoutSaveResult.Saved) runCatching { onHiddenSaved() }
    return result
  }

  /** All'avvio: le righe in attesa che un salvataggio interrotto ha lasciato nella cartella dei ritagli si tolgono (`IS_PENDING`). */
  suspend fun purgeOrphans(): Int = (writerOf(folder()) as? MediaStorePhoneMediaWriter)?.purgeOrphans() ?: 0

  private fun writerOf(folder: String): PhoneMediaWriter = synchronized(writers) { writers.getOrPut(folder) { writerFor(folder) } }
}

/** Il [CutoutSaver] con i codici della Diagnostica (D40, solo il codice): un salvataggio fallito e' `CUTOUT_SAVE_FAILED`, una copia o condivisione fallita `CUTOUT_FAILED`. */
class DiagCutoutSaver(private val inner: CutoutSaver, private val diag: (DiagCode) -> Unit) : CutoutSaver {
  override suspend fun copy(id: MediaId, ref: MediaRef, cutout: ViewerCutout.Subject, outline: Boolean): Boolean =
    noted(AiDiag.CUTOUT_FAILED) { inner.copy(id, ref, cutout, outline) }.also { if (!it) diag(AiDiag.CUTOUT_FAILED) }

  override suspend fun share(id: MediaId, ref: MediaRef, cutout: ViewerCutout.Subject, outline: Boolean): Boolean =
    noted(AiDiag.CUTOUT_FAILED) { inner.share(id, ref, cutout, outline) }.also { if (!it) diag(AiDiag.CUTOUT_FAILED) }

  override suspend fun saveAsNewPhoto(id: MediaId, ref: MediaRef, cutout: ViewerCutout.Subject, outline: Boolean, fromHidden: Boolean): CutoutSaveResult =
    noted(AiDiag.CUTOUT_SAVE_FAILED) { inner.saveAsNewPhoto(id, ref, cutout, outline, fromHidden) }.also { if (it is CutoutSaveResult.Failed) diag(AiDiag.CUTOUT_SAVE_FAILED) }

  /** R20: la memoria esaurita (un `Error`) arriva al viewer, che la dice ("Memoria insufficiente"); qui resta nella Diagnostica, solo il codice. */
  private suspend fun <T> noted(code: DiagCode, block: suspend () -> T): T = try {
    block()
  } catch (e: OutOfMemoryError) {
    diag(code)
    throw e
  }
}

/**
 * Dove Copia e Condividi mettono il PNG: la cartella di condivisione che c'e' gia' e il suo indirizzo (`IntegrationsGraph.shareCache` e `uris`), e chi tiene
 * il file finche' serve (R18): il custode delle condivisioni e il revocatore dei permessi (`IntegrationsGraph.shareHolder` e `revoker`).
 */
class CutoutShareEnv(
  val cache: ShareCache,
  val uris: CacheUris,
  val holder: ShareHolder? = null,
  val revoker: UriRevoker = UriRevoker { },
)
