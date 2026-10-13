package dev.pampa.mycloudgallery.gallery

import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagText
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue

/** I codici che `:app` aggiunge alla sezione "Libreria del telefono" (gli stessi id del catalogo di feature-diagnostics). */
object LibraryDiagCodes {
  val PLACES = DiagCode("PHONE_PLACES")
  val INDEX = DiagCode("PHONE_INDEX")
  val THUMBNAILS = DiagCode("PHONE_THUMBNAILS", setOf("E-V5"))

  val ALL: List<DiagCode> = listOf(PLACES, INDEX, THUMBNAILS)
}

/**
 * Il contributo di `:app` alla Diagnostica della galleria (Fase 3): i luoghi offline (presenti o no), l'indice della
 * timeline (quanti elementi, ricostruzioni, patch, scritture dell'istantanea) e la cache delle miniature. **Solo numeri ed
 * enumerati**: nessun nome, percorso, luogo o id di media.
 */
class LibraryDiagnostics(private val gallery: GalleryGraph) : DiagnosticsContributor {

  override val section: DiagSection = DiagSection.PHONE_LIBRARY

  override suspend fun collect(): List<DiagItem> {
    val items = ArrayList<DiagItem>(3)

    val places = gallery.places
    val placesExport = LinkedHashMap<String, ExportValue>()
    placesExport["places"] = ExportValue.Enum.of(places.status.value)
    places.failure?.let { placesExport["placesFailure"] = ExportValue.Enum.of(it) }
    placesExport["dataset"] = ExportValue.Num(places.dataset.toLong())
    items += when (places.status.value) {
      PlacesStatus.AVAILABLE -> item(LibraryDiagCodes.PLACES, DiagSeverity.OK, R.string.gallery_diag_places_ok, export = placesExport)
      PlacesStatus.OPENING -> item(LibraryDiagCodes.PLACES, DiagSeverity.INFO, R.string.gallery_diag_places_opening, export = placesExport)
      PlacesStatus.NOT_BUNDLED ->
        item(LibraryDiagCodes.PLACES, DiagSeverity.INFO, R.string.gallery_diag_places_missing, R.string.gallery_diag_places_missing_detail, placesExport)
      PlacesStatus.FAILED ->
        item(LibraryDiagCodes.PLACES, DiagSeverity.WARNING, R.string.gallery_diag_places_failed, R.string.gallery_diag_places_failed_detail, placesExport)
    }

    val repository = gallery.repository
    val snapshot = repository.snapshot.value
    val reconciled = repository.reconciled.value
    items += item(
      LibraryDiagCodes.INDEX,
      if (gallery.started.value && reconciled) DiagSeverity.OK else DiagSeverity.INFO,
      if (gallery.started.value) R.string.gallery_diag_index else R.string.gallery_diag_index_not_started,
      export = linkedMapOf(
        "items" to ExportValue.Num((snapshot?.size ?: 0).toLong()),
        "rebuilds" to ExportValue.Num(repository.rebuilds.toLong()),
        "patches" to ExportValue.Num(repository.patches.toLong()),
        "snapshotWrites" to ExportValue.Num(repository.snapshotWrites.toLong()),
        "reconciled" to ExportValue.Flag(reconciled),
      ),
    )

    val stats = gallery.thumbnails.stats.value
    items += item(
      LibraryDiagCodes.THUMBNAILS,
      DiagSeverity.OK,
      R.string.gallery_diag_thumbnails,
      export = linkedMapOf(
        "cachedBytes" to ExportValue.Num(stats.cachedBytes),
        "budgetBytes" to ExportValue.Num(stats.budgetBytes),
        "started" to ExportValue.Num(stats.started),
        "cancelled" to ExportValue.Num(stats.cancelled),
        "failed" to ExportValue.Num(stats.failed),
        "memoryHits" to ExportValue.Num(stats.memoryHits),
        "misses" to ExportValue.Num(stats.misses),
        "inFlight" to ExportValue.Num(stats.inFlight.toLong()),
      ),
    )
    return items
  }

  private fun item(
    code: DiagCode,
    severity: DiagSeverity,
    title: Int,
    detail: Int? = null,
    export: Map<String, ExportValue> = emptyMap(),
  ): DiagItem = DiagItem(code, section, severity, title, detail?.let { DiagText(it) }, null, export)
}
