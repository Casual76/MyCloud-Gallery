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

  override suspend fun collect(): List<DiagItem> {
    val live = graph.live.value
    val items = ArrayList<DiagItem>()
    val list = live?.let { safely { it.albums.all.value } }
    val albums = list?.albums.orEmpty()
    val problems = live?.let { safely { it.shared.diary.problemCounts() } }
    val openOps = live?.let { safely { it.shared.diary.openOps().size } } ?: 0
    val pending = live?.let { safely { it.albums.projection.pending.value } } ?: 0
    val sync = graph.lastSync.value
    items += item(
      DiagCodes.ALBUMS_STATE,
      DiagSeverity.INFO,
      mapOf(
        "mounted" to ExportValue.Flag(live != null),
        "manual" to ExportValue.Num(albums.count { it is Album.Manual }.toLong()),
        "smart" to ExportValue.Num(albums.count { it is Album.Smart }.toLong()),
        "shared" to ExportValue.Num(albums.count { it is Album.Shared }.toLong()),
        "openShares" to ExportValue.Num(openOps.toLong()),
        "contributions" to ExportValue.Num((sync?.contributionsRead ?: 0).toLong()),
        "syncIncomplete" to ExportValue.Flag(sync?.incomplete == true),
        "roundErrors" to ExportValue.Num(graph.roundErrors.toLong()),
      ),
    )
    if (sync != null) {
      if (sync.contributionsRejected > 0) items += count(DiagCodes.ALBUM_CONTRIB_REJECTED, sync.contributionsRejected)
      if (sync.entriesDenied > 0) items += count(DiagCodes.ALBUM_ENTRY_DENIED, sync.entriesDenied)
    }
    val unattested = live?.let { safely { it.shared.replica.views.value.values.sumOf { view -> view.unattested } } } ?: 0
    if (unattested > 0) items += count(DiagCodes.ALBUM_ITEM_UNATTESTED, unattested)
    if (problems != null) {
      problems.waits[ShareWait.NO_SPACE]?.let { items += count(DiagCodes.SHARE_PAUSED_SPACE, it) }
      problems.waits[ShareWait.WRITES_BLOCKED]?.let { items += count(DiagCodes.SHARE_PAUSED_FROZEN, it) }
      problems.waits[ShareWait.LOCKED]?.let { items += count(DiagCodes.SHARE_WAITING_LOCKED, it) }
      problems.failures[ShareFailure.SOURCE_CHANGED]?.let { items += count(DiagCodes.SHARE_SOURCE_CHANGED, it) }
      if (problems.safeCopies > 0) items += count(DiagCodes.SHARE_SAFE_COPY_QUEUED, problems.safeCopies)
    }
    if (live != null) {
      val smart = albums.filterIsInstance<Album.Smart>()
      val library = safely { live.catalog.albums.visibleCount() } ?: 0
      val broad = smart.count { tooBroad(it.count, library) }
      if (broad > 0) items += count(DiagCodes.SMART_TOO_BROAD, broad)
      val described = smart.count { !it.rules.text.isNullOrBlank() || it.rules.examples.isNotEmpty() }
      if (!live.contentSearch && described > 0) items += count(DiagCodes.SMART_NO_SEARCH, described)
    }
    if (graph.lastTrips.value?.homeKnown == false) items += item(DiagCodes.TRIP_NO_GPS, DiagSeverity.INFO, emptyMap())
    if (pending > 0) items += count(DiagCodes.ALBUM_PROJECTION_PENDING, pending)
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
