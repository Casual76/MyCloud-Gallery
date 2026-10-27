package dev.pampa.mycloudgallery.albums

import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AlbumDiag
import dev.pampa.mycloudgallery.core.albums.contract.ShareFailure
import dev.pampa.mycloudgallery.core.albums.contract.ShareWait
import dev.pampa.mycloudgallery.core.albums.contract.SmartLimits
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import kotlinx.coroutines.CancellationException

/**
 * La sezione "Album" della Diagnostica (Fase 7, §2.8, D37, P13): lo stato (quanti album, condivisioni aperte, proiezione, ultimo giro della replica) e
 * un elemento per ogni codice del §2.8 che ha qualcosa da dire. **Solo numeri e codici**: mai titoli, cartelle, luoghi, casa, coordinate, descrizioni,
 * id di media o account. Prima che gli album siano montati (famiglia non pronta) dice solo lo stato, con `mounted` spento.
 */
class AlbumsDiagnostics(private val graph: AlbumsGraph) : DiagnosticsContributor {

  override val section: DiagSection = DiagSection.ALBUMS

  override suspend fun collect(): List<DiagItem> = render(facts())

  /** Quello che il grafo sa adesso, in soli numeri e segni: la sezione si compone da qui (e si prova senza montare gli album). */
  internal suspend fun facts(): AlbumsFacts {
    val live = graph.live.value
    val albums = live?.let { safely { it.albums.all.value } }?.albums.orEmpty()
    val problems = live?.let { safely { it.shared.diary.problemCounts() } }
    val sync = graph.lastSync.value
    val trips = live?.trips?.proposals?.value
    val smart = albums.filterIsInstance<Album.Smart>()
    val library = if (live == null) 0 else safely { live.catalog.albums.visibleCount() } ?: 0
    return AlbumsFacts(
      mounted = live != null,
      manual = albums.count { it is Album.Manual },
      smart = smart.size,
      shared = albums.count { it is Album.Shared },
      openShares = live?.let { safely { it.shared.diary.openOps().size } } ?: 0,
      contributions = sync?.contributionsRead ?: 0,
      syncIncomplete = sync?.incomplete == true,
      roundErrors = graph.roundErrors,
      searchReady = live?.content?.ready == true,
      tripsSearched = trips?.searched == true,
      tripsHomeKnown = trips?.homeKnown == true,
      // DG-3: i rifiuti non spariscono al giro dopo. Il segno della testa rifiutata sta nel database (resta finche' il dispositivo non ne pubblica una
      // buona); il resoconto dell'ultimo giro conta anche le teste in attesa dell'ora (senza un segno). Vale il maggiore.
      rejected = maxOf(sync?.contributionsRejected ?: 0, live?.let { safely { it.shared.replicaStore.rejectedHeads() } } ?: 0),
      denied = sync?.entriesDenied ?: 0,
      unattested = live?.let { safely { it.shared.replica.views.value.values.sumOf { view -> view.unattested } } } ?: 0,
      pausedSpace = problems?.waits?.get(ShareWait.NO_SPACE),
      pausedFrozen = problems?.waits?.get(ShareWait.WRITES_BLOCKED),
      waitingLocked = problems?.waits?.get(ShareWait.LOCKED),
      sourceChanged = problems?.failures?.get(ShareFailure.SOURCE_CHANGED),
      safeCopies = problems?.safeCopies ?: 0,
      tooBroad = smart.count { tooBroad(it.count, library) },
      // DG-1: conta che la ricerca per contenuto sia **pronta** (modello e indice), non che il motore ci sia (con Hilt c'e' sempre).
      described = smart.count { !it.rules.text.isNullOrBlank() || it.rules.examples.isNotEmpty() },
      projectionPending = live?.let { safely { it.albums.projection.pending.value } } ?: 0,
    )
  }

  /** Le voci della sezione per [f]: lo stato e un elemento per ogni codice del §2.8 che ha qualcosa da dire. */
  internal fun render(f: AlbumsFacts): List<DiagItem> {
    val items = ArrayList<DiagItem>()
    items += item(
      DiagCodes.ALBUMS_STATE,
      DiagSeverity.INFO,
      mapOf(
        "mounted" to ExportValue.Flag(f.mounted),
        "manual" to ExportValue.Num(f.manual.toLong()),
        "smart" to ExportValue.Num(f.smart.toLong()),
        "shared" to ExportValue.Num(f.shared.toLong()),
        "openShares" to ExportValue.Num(f.openShares.toLong()),
        "contributions" to ExportValue.Num(f.contributions.toLong()),
        "syncIncomplete" to ExportValue.Flag(f.syncIncomplete),
        "roundErrors" to ExportValue.Num(f.roundErrors.toLong()),
        // DG-1, VG-1: la ricerca per contenuto e' pronta adesso; i viaggi sono stati cercati e si e' trovata una casa (solo si'/no, mai dove).
        "searchReady" to ExportValue.Flag(f.searchReady),
        "tripsSearched" to ExportValue.Flag(f.tripsSearched),
        "tripsHomeKnown" to ExportValue.Flag(f.tripsHomeKnown),
      ),
    )
    if (f.rejected > 0) items += count(DiagCodes.ALBUM_CONTRIB_REJECTED, f.rejected)
    if (f.denied > 0) items += count(DiagCodes.ALBUM_ENTRY_DENIED, f.denied)
    if (f.unattested > 0) items += count(DiagCodes.ALBUM_ITEM_UNATTESTED, f.unattested)
    f.pausedSpace?.let { items += count(DiagCodes.SHARE_PAUSED_SPACE, it) }
    f.pausedFrozen?.let { items += count(DiagCodes.SHARE_PAUSED_FROZEN, it) }
    f.waitingLocked?.let { items += count(DiagCodes.SHARE_WAITING_LOCKED, it) }
    f.sourceChanged?.let { items += count(DiagCodes.SHARE_SOURCE_CHANGED, it) }
    if (f.safeCopies > 0) items += count(DiagCodes.SHARE_SAFE_COPY_QUEUED, f.safeCopies)
    if (f.mounted) {
      if (f.tooBroad > 0) items += count(DiagCodes.SMART_TOO_BROAD, f.tooBroad)
      if (!f.searchReady && f.described > 0) items += count(DiagCodes.SMART_NO_SEARCH, f.described)
    }
    // VG-1, DG-3: "nessuna casa" solo dopo aver cercato, e dalla memoria locale dei viaggi: resta dopo la morte del processo (prima: l'esito in memoria
    // dell'ultima notte).
    if (f.tripsSearched && !f.tripsHomeKnown) items += item(DiagCodes.TRIP_NO_GPS, DiagSeverity.INFO, emptyMap())
    if (f.projectionPending > 0) items += count(DiagCodes.ALBUM_PROJECTION_PENDING, f.projectionPending)
    return items
  }

  private suspend fun <T> safely(block: suspend () -> T): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    null
  }

  private fun count(code: DiagCode, n: Int) = item(code, DiagCatalog.entry(code)?.severity ?: DiagSeverity.INFO, mapOf("count" to ExportValue.Num(n.toLong())))

  private fun item(code: DiagCode, severity: DiagSeverity, export: Map<String, ExportValue>) =
    DiagItem(code = code, section = section, severity = severity, titleRes = DiagCatalog.titleOf(code), export = export)

  companion object {
    /** I codici del contratto (`AlbumDiag`, §2.8): `Phase7DiagCatalogTest` li cerca tutti nel catalogo, con lo stesso id e gli stessi edge case. */
    val CONTRACT_CODES: List<DiagCode> = AlbumDiag.ALL

    /** E-AL6: oltre 3.000 elementi o oltre il 20% della Libreria visibile. */
    fun tooBroad(count: Int, library: Int): Boolean =
      count > SmartLimits.TOO_BROAD_ITEMS || (library > 0 && count > library * SmartLimits.TOO_BROAD_FRACTION)
  }
}

/** Cio' che la sezione degli album dice, in soli numeri e segni (D37): mai un titolo, una cartella, un luogo, una coordinata o un id. */
internal data class AlbumsFacts(
  val mounted: Boolean = false,
  val manual: Int = 0,
  val smart: Int = 0,
  val shared: Int = 0,
  val openShares: Int = 0,
  val contributions: Int = 0,
  val syncIncomplete: Boolean = false,
  val roundErrors: Int = 0,
  val searchReady: Boolean = false,
  val tripsSearched: Boolean = false,
  val tripsHomeKnown: Boolean = false,
  val rejected: Int = 0,
  val denied: Int = 0,
  val unattested: Int = 0,
  val pausedSpace: Int? = null,
  val pausedFrozen: Int? = null,
  val waitingLocked: Int? = null,
  val sourceChanged: Int? = null,
  val safeCopies: Int = 0,
  val tooBroad: Int = 0,
  /** Gli smart con una descrizione o delle foto d'esempio (che contano solo con la ricerca per contenuto pronta). */
  val described: Int = 0,
  val projectionPending: Int = 0,
)
