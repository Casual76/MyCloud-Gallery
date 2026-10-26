package dev.pampa.mycloudgallery.utility

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.duplicates.AlbumMembership
import dev.pampa.mycloudgallery.core.library.duplicates.CatalogIdenticalDuplicates
import dev.pampa.mycloudgallery.core.library.duplicates.CatalogSimilarShots
import dev.pampa.mycloudgallery.core.library.duplicates.CleanupPlan
import dev.pampa.mycloudgallery.core.library.duplicates.FullHashNightly
import dev.pampa.mycloudgallery.core.library.duplicates.GroupProposal
import dev.pampa.mycloudgallery.core.library.duplicates.IdenticalDuplicates
import dev.pampa.mycloudgallery.core.library.duplicates.IdenticalScan
import dev.pampa.mycloudgallery.core.library.duplicates.NightlyReport
import dev.pampa.mycloudgallery.core.library.duplicates.NoIdenticalDuplicates
import dev.pampa.mycloudgallery.core.library.duplicates.NoSimilarShots
import dev.pampa.mycloudgallery.core.library.duplicates.RemovalPlaces
import dev.pampa.mycloudgallery.core.library.duplicates.SigningReport
import dev.pampa.mycloudgallery.core.library.duplicates.SimilarBatch
import dev.pampa.mycloudgallery.core.library.duplicates.SimilarShots
import dev.pampa.mycloudgallery.core.library.similar.SignatureSource
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * L'Utility in `:app` (Fase 7, P13; P8: "IdenticalDuplicates, SimilarShots, FullHashNightly"): le pagine che leggono il catalogo, costruite al
 * primo uso quando il catalogo c'e' (prima rispondono come i null-object: niente gruppi, niente scatti, mai un nascosto, mai un errore che fermi
 * la pagina), e la manutenzione notturna. Una sola istanza per processo (`UtilityModule`).
 *
 * @param catalog il catalogo, null finche' la galleria non e' pronta.
 * @param signatures da dove vengono i pixel da firmare: la stessa sorgente della manutenzione delle firme della Fase 5 (`SimilarCopiesGraph`), null
 *   senza NAS o dati personali (le firme restano quelle gia' fatte).
 * @param fullHashOn l'interruttore notturno di adesso (`full_hash_nightly`).
 */
class UtilityGraph(
  private val catalog: suspend () -> MediaCatalog?,
  private val visible: VisibleMedia,
  private val hidden: HiddenMask,
  private val albums: AlbumMembership,
  private val signatures: suspend () -> SignatureSource?,
  private val fullHashOn: () -> Boolean,
) {
  private val lock = Mutex()
  private var identicalOn: CatalogIdenticalDuplicates? = null
  private var similarOn: CatalogSimilarShots? = null
  private var similarSigned: Boolean = false
  private var nightlyOn: FullHashNightly? = null

  @Volatile var lastNightly: NightlyReport? = null
    private set

  @Volatile var lastSigning: SigningReport? = null
    private set

  private suspend fun identical(): CatalogIdenticalDuplicates? = lock.withLock {
    identicalOn ?: catalog()?.let { CatalogIdenticalDuplicates(it, visible) }?.also { identicalOn = it }
  }

  /** Il rilevatore dei simili; si rifa' una volta quando la sorgente delle firme arriva (prima: solo le firme che ci sono). */
  private suspend fun similar(): CatalogSimilarShots? {
    similarOn?.takeIf { similarSigned }?.let { return it }
    val source = signatures()
    return lock.withLock {
      val existing = similarOn
      if (existing != null && (similarSigned || source == null)) return@withLock existing
      val built = catalog()?.let { CatalogSimilarShots(it, visible, hidden, albums = albums, signatures = source) } ?: return@withLock null
      similarOn = built
      similarSigned = source != null
      built
    }
  }

  private suspend fun nightly(): FullHashNightly? = lock.withLock {
    nightlyOn ?: catalog()?.let { FullHashNightly(it, visible) }?.also { nightlyOn = it }
  }

  val identicalDuplicates: IdenticalDuplicates = object : IdenticalDuplicates {
    override suspend fun scan(): IdenticalScan = identical()?.scan() ?: NoIdenticalDuplicates.scan()

    override suspend fun plan(proposals: Collection<GroupProposal>): CleanupPlan =
      identical()?.plan(proposals) ?: NoIdenticalDuplicates.plan(proposals)
  }

  val similarShots: SimilarShots = object : SimilarShots {
    override suspend fun scan(beforeDay: Int?, budget: Duration): SimilarBatch = similar()?.scan(beforeDay, budget) ?: NoSimilarShots.scan(beforeDay, budget)

    override suspend fun sign(budget: Duration): SigningReport = similar()?.sign(budget) ?: NoSimilarShots.sign(budget)

    override suspend fun placesOf(media: Collection<MediaId>): RemovalPlaces = similar()?.placesOf(media) ?: NoSimilarShots.placesOf(media)
  }

  /** "Controlla di notte i video del NAS" (D27, E-U4): acceso mette in coda `FULL_HASH` per i video del NAS, spento la ritira. */
  suspend fun applyFullHash(on: Boolean, budget: Duration = APPLY_BUDGET) {
    val report = nightly()?.apply(on, budget) ?: return
    lastNightly = report
    GalleryLog.d(TAG) { "hash completo: acceso=${report.enabled} in coda=${report.queued} ritirati=${report.withdrawn} finito=${report.finished}" }
  }

  /**
   * La manutenzione notturna (P8, lavoro della Libreria): l'interruttore applicato (prima che il lavoro di sicurezza del backup prenda la coda
   * `FULL_HASH`) e la firma visiva degli scatti che ne hanno bisogno (Doppioni simili). Un errore non ferma il resto della manutenzione.
   */
  suspend fun nightly(budget: Duration) {
    try {
      applyFullHash(fullHashOn(), budget)
      lastSigning = similarShots.sign(budget)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "manutenzione dell'Utility non riuscita: ${e.javaClass.simpleName}" }
    }
  }

  internal companion object {
    const val TAG = "Utility"
    val APPLY_BUDGET: Duration = 30.seconds
  }
}
