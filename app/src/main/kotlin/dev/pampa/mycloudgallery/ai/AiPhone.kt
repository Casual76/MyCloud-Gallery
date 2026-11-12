package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.ai.compute.AiPhoneMedia
import dev.pampa.mycloudgallery.core.ai.contract.AiCandidate
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.ai.contract.AiResults
import dev.pampa.mycloudgallery.core.backup.contract.BackupFence
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.backup.contract.PhoneOpen
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.database.CatalogPhoneCursor
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.prepare.contract.PrepFailCode
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOpen
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOutput
import dev.pampa.mycloudgallery.core.prepare.contract.PrepResult
import dev.pampa.mycloudgallery.core.prepare.contract.PrepSpec
import dev.pampa.mycloudgallery.core.prepare.contract.PrepareSource
import dev.pampa.mycloudgallery.core.prepare.contract.Preparer
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap
import kotlin.coroutines.cancellation.CancellationException

// I MEDIA DEL TELEFONO per il calcolo AI (docs/fase6-progetto.md §2.2, D10, D14; Fase 6, P11b): quelli che stanno **solo sul telefono** (nessun
// file sul NAS) e i byte del telefono per i media del NAS che sono anche qui (backup). Le anteprime le prepara il `Preparer` di core-prepare dai
// byte letti dal MediaStore (mai byte di altre app al decodificatore del modello, D5) e vanno nel buffer degli indicizzatori; i risultati dei
// media del solo telefono restano **locali** (nessuna share li accoglie: M3).

/**
 * Il telefono per il calcolo AI: l'elenco dei media del solo telefono ancora da fare ([candidates]) e l'apertura dei byte di un contenuto per
 * chiave ([open], porta [AiPhoneMedia]).
 *
 * - **D13** (debug con un account di prova): con la recinzione del telefono si calcolano e si aprono solo le righe ammesse (`mcg-prova`); le
 *   altre non sono mai candidate. Senza recinzione (release, account veri) tutte.
 * - **Gia' fatto non si riprende**: un candidato ha solo le classi che mancano con la ricetta corrente, meno quelle segnate "non supportate"
 *   (come il catalogo del NAS, `CatalogAiWorkCatalog.settled`).
 * - **D8**: i byte aperti devono essere quelli della versione del catalogo: la dimensione si confronta all'apertura e l'impronta rapida
 *   dell'uscita della preparazione ([CheckedPreparer]); se differiscono il media si salta (`SOURCE_CHANGED`), mai un vettore per byte altrui.
 */
class AppAiPhone(
  private val catalog: suspend () -> MediaCatalog?,
  private val bytes: PhoneBytes,
  private val results: AiResults,
  private val unsupported: FileAiUnsupported,
  private val facts: ContentFacts,
  private val recipe: AiRecipe = AiRecipe.CURRENT,
  private val fence: BackupFence? = null,
  private val account: () -> String? = { null },
) : AiPhoneMedia {

  /** Per ogni sorgente aperta qui, l'impronta rapida che i suoi byte devono avere (la legge [CheckedPreparer]). */
  private val expectations: MutableMap<PrepareSource, QuickHash> = Collections.synchronizedMap(WeakHashMap())

  /** L'impronta rapida che i byte di [source] devono avere, se [source] l'ha aperta questo oggetto. */
  fun expectedQuickHash(source: PrepareSource): QuickHash? = expectations[source]

  /**
   * I media del solo telefono da calcolare, dal piu' recente (D21), al piu' [limit]: ognuno con le classi che mancano ([wantsText]: anche `ocr`,
   * solo con la lettura attiva qui, D41). Le chiavi si ricordano in [facts] per [open].
   */
  suspend fun candidates(wantsText: Boolean, limit: Int): List<AiCandidate> {
    val catalog = catalog() ?: return emptyList()
    val wanted = if (wantsText) setOf(AiClass.VEC, AiClass.OCR) else setOf(AiClass.VEC)
    val out = ArrayList<AiCandidate>()
    var cursor: CatalogPhoneCursor? = null
    try {
      while (out.size < limit) {
        val page = catalog.phoneOnlyWork(cursor, PAGE)
        if (page.isEmpty()) break
        for (item in page) {
          val missing = wanted - settled(item.contentKey)
          if (missing.isEmpty() || !allowed(catalog, item.mediaId, item.versionId)) continue
          facts.put(item.contentKey, ContentFact(item.mediaId, item.versionId, item.size, item.source, item.quickHash))
          out += AiCandidate(item.contentKey, item.kind, item.source, missing)
          if (out.size >= limit) break
        }
        if (page.size < PAGE) break
        cursor = page.last().cursor
      }
    } catch (e: IOException) {
      // L'indice di ricerca non si apre (dove si salva): senza, niente si calcola; quello che si e' trovato basta alla stima.
    }
    return out
  }

  /**
   * XS-4: il candidato per un media **chiesto** dall'utente ([id], la chiave [key] che aveva), anche se la pagina limitata dei media del solo
   * telefono non lo contiene (oltre i primi 2.000). Solo se e' ancora lo stesso contenuto, sta solo sul telefono (nessuna copia sul NAS), ha
   * una copia leggibile, e' ammesso dalla recinzione (D13) e ha ancora classi da fare. Altrimenti null: niente si inventa.
   */
  suspend fun candidateOf(id: MediaId, key: ContentKey, wantsText: Boolean): AiCandidate? {
    val catalog = catalog() ?: return null
    val media = catalog.media(id) ?: return null
    val version = media.versions.firstOrNull { it.id == media.currentVersionId } ?: return null
    if (ContentKey.of(version.fingerprint) != key) return null
    if (catalog.nasSharesOf(listOf(id))[id].orEmpty().isNotEmpty()) return null
    if (catalog.phoneRefOf(media.id, version.id) == null) return null
    if (!allowed(catalog, media.id, version.id)) return null
    val wanted = if (wantsText) setOf(AiClass.VEC, AiClass.OCR) else setOf(AiClass.VEC)
    val missing = wanted - settled(key)
    if (missing.isEmpty()) return null
    facts.put(key, ContentFact(media.id, version.id, version.fingerprint.size, media.source, version.fingerprint.quickHash))
    return AiCandidate(key, media.kind, media.source, missing)
  }

  /** Le classi gia' a posto per [key]: salvate qui con la ricetta corrente, o segnate "non supportate" (E-A12). */
  private suspend fun settled(key: ContentKey): Set<AiClass> {
    val forVec = results.saved(key, recipe.version)
    val forOcr = if (recipe.ocrVersion == recipe.version) forVec else results.saved(key, recipe.ocrVersion)
    return buildSet {
      if (AiClass.VEC in forVec) add(AiClass.VEC)
      if (AiClass.OCR in forOcr) add(AiClass.OCR)
      unsupported.classesOf(listOf(key))[key]?.let { addAll(it) }
    }
  }

  /** D13: senza recinzione sempre; con la recinzione solo una riga in una cartella ammessa (senza account, nessuna: si fallisce chiusi). */
  private suspend fun allowed(catalog: MediaCatalog, mediaId: MediaId, versionId: VersionId): Boolean {
    val fence = fence ?: return true
    val account = account() ?: return false
    val place = catalog.phoneRefOf(mediaId, versionId) ?: return false
    val folder = catalog.phoneRowFolder(place.volume, place.mediaStoreId) ?: return false
    return fence.allowsPhoneFolder(account, folder)
  }

  override suspend fun open(key: ContentKey, kind: MediaKind): PrepOpen {
    val fact = facts[key] ?: return PrepOpen.Unavailable(PrepFailCode.SOURCE_CHANGED)
    val catalog = catalog() ?: return PrepOpen.Unavailable(PrepFailCode.SOURCE_CHANGED)
    val place = catalog.phoneRefOf(fact.mediaId, fact.versionId) ?: return PrepOpen.Unavailable(PrepFailCode.SOURCE_CHANGED)
    val opened = bytes.open(PhoneRef(place.volume, place.mediaStoreId, place.mediaStoreVersion)) as? PhoneOpen.Opened
      ?: return PrepOpen.Unavailable(PrepFailCode.SOURCE_CHANGED)
    val file = opened.file
    if (file.bytes.size != fact.size) {
      runCatching { file.close() }
      return PrepOpen.Unavailable(PrepFailCode.SOURCE_CHANGED)
    }
    val source = PrepareSource.Bytes(file.bytes, kind, null)
    fact.quickHash?.let { expectations[source] = it }
    return PrepOpen.Ready(source) { runCatching { file.close() } }
  }

  /**
   * L'anteprima di analisi della versione corrente di [id], preparata ora dai suoi byte del telefono (la lettura al volo del viewer, D42):
   * la chiave di contenuto e il JPEG di 1.024 px. Null se il media non ha una copia leggibile qui, e' un video o la preparazione non riesce.
   */
  suspend fun analysisOf(id: MediaId, preparer: Preparer): Pair<ContentKey, ByteArray>? {
    val catalog = catalog() ?: return null
    val media = catalog.media(id) ?: return null
    if (media.kind == MediaKind.VIDEO) return null
    val version = media.versions.firstOrNull { it.id == media.currentVersionId } ?: return null
    val key = ContentKey.of(version.fingerprint)
    facts.put(key, ContentFact(media.id, version.id, version.fingerprint.size, media.source, version.fingerprint.quickHash))
    val ready = open(key, media.kind) as? PrepOpen.Ready ?: return null
    val result = try {
      CheckedPreparer(preparer, ::expectedQuickHash).prepare(ready.source, PrepSpec(classes = setOf(SegmentClass.ANALYSIS)))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    } finally {
      runCatching { ready.close() }
    }
    val preview = (result as? PrepResult.Done)?.outputs?.firstNotNullOfOrNull { out ->
      (out as? PrepOutput.Image)?.takeIf { it.cls == SegmentClass.ANALYSIS }?.bytes
    }
    return preview?.let { key to it }
  }

  private companion object {
    const val PAGE = 200
  }
}

/**
 * Il preparatore che non accetta byte diversi da quelli del catalogo (D8): se la preparazione dice un'impronta rapida diversa da quella attesa
 * per la sorgente ([expected]), il file e' cambiato dopo l'elenco e l'esito e' `SOURCE_CHANGED`. Una sorgente senza attesa (i byte del NAS,
 * gia' controllati dal timbro) passa com'e'.
 */
class CheckedPreparer(private val inner: Preparer, private val expected: (PrepareSource) -> QuickHash?) : Preparer {
  override suspend fun prepare(source: PrepareSource, spec: PrepSpec): PrepResult {
    val result = inner.prepare(source, spec)
    val want = expected(source) ?: return result
    val got = (result as? PrepResult.Done)?.quickHash
    return if (got != null && got != want) PrepResult.Failed(PrepFailCode.SOURCE_CHANGED, retryable = true) else result
  }
}
