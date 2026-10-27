package dev.pampa.mycloudgallery.albums

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import dev.pampa.mycloudgallery.feature.diagnostics.export.ExportKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * La sezione "Album" della Diagnostica composta dai fatti (revisione di fine fase: R22, DG-1, DG-3, VG-1). Solo numeri e segni; ogni chiave sta nell'elenco
 * chiuso dell'esportazione (prima l'esportazione aveva solo `mounted` e `count` e le prove sul campo F72-F93 non avevano da dove leggere), `SMART_NO_SEARCH`
 * esiste e dipende dalla ricerca **pronta**, "nessuna casa" dipende da una ricerca fatta davvero.
 */
class AlbumsDiagnosticsRenderTest {

  private fun diagnostics(): Pair<AlbumsDiagnostics, CoroutineScope> {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    return AlbumsDiagnostics(AlbumsGraph(scope, { null }, MutableStateFlow(false))) to scope
  }

  private fun render(facts: AlbumsFacts): List<DiagItem> {
    val (diag, scope) = diagnostics()
    try {
      return diag.render(facts)
    } finally {
      scope.cancel()
    }
  }

  private fun List<DiagItem>.ids() = map { it.code.id }

  private val busy = AlbumsFacts(
    mounted = true, manual = 4, smart = 3, shared = 2, openShares = 1, contributions = 17, syncIncomplete = true, roundErrors = 2, searchReady = true,
    tripsSearched = true, tripsHomeKnown = true, rejected = 1, denied = 2, unattested = 3, pausedSpace = 1, pausedFrozen = 1, waitingLocked = 1, sourceChanged = 1,
    safeCopies = 2, tooBroad = 1, described = 2, projectionPending = 5,
  )

  @Test
  fun loStatoDiceTuttiIConti_R22() {
    val export = render(busy).first { it.code == DiagCodes.ALBUMS_STATE }.export
    assertThat(export["mounted"]).isEqualTo(ExportValue.Flag(true))
    assertThat(export["manual"]).isEqualTo(ExportValue.Num(4))
    assertThat(export["smart"]).isEqualTo(ExportValue.Num(3))
    assertThat(export["shared"]).isEqualTo(ExportValue.Num(2))
    assertThat(export["openShares"]).isEqualTo(ExportValue.Num(1))
    assertThat(export["contributions"]).isEqualTo(ExportValue.Num(17))
    assertThat(export["syncIncomplete"]).isEqualTo(ExportValue.Flag(true))
    assertThat(export["roundErrors"]).isEqualTo(ExportValue.Num(2))
    assertThat(export["searchReady"]).isEqualTo(ExportValue.Flag(true))
    assertThat(export["tripsSearched"]).isEqualTo(ExportValue.Flag(true))
    assertThat(export["tripsHomeKnown"]).isEqualTo(ExportValue.Flag(true))
  }

  @Test
  fun ogniChiaveEsportataStaNellElencoChiusoENonCeNienteDiPersonale() {
    val items = render(busy)
    val outside = items.flatMap { it.export.keys }.distinct().filterNot(ExportKeys::isAllowed)
    assertWithMessage("chiavi fuori da ExportKeys: $outside").that(outside).isEmpty()
    // Solo numeri e segni: nessun testo libero.
    for (item in items) for ((key, value) in item.export) {
      assertWithMessage("$key di ${item.code.id}").that(value is ExportValue.Num || value is ExportValue.Flag).isTrue()
    }
    val outsideCodes = items.map { it.code }.filterNot { it in DiagCatalog }.map { it.id }
    assertThat(outsideCodes).isEmpty()
  }

  @Test
  fun tuttiICodiciDelContrattoCompaionoQuandoHannoQualcosaDaDire() {
    assertThat(render(busy).ids()).containsAtLeast(
      "ALBUMS_STATE", "ALBUM_CONTRIB_REJECTED", "ALBUM_ENTRY_DENIED", "ALBUM_ITEM_UNATTESTED", "SHARE_PAUSED_SPACE", "SHARE_PAUSED_FROZEN", "SHARE_WAITING_LOCKED",
      "SHARE_SOURCE_CHANGED", "SHARE_SAFE_COPY_QUEUED", "SMART_TOO_BROAD", "ALBUM_PROJECTION_PENDING",
    )
  }

  @Test
  fun unaRicercaNonProntaConDescrizioniDiceSMART_NO_SEARCH_DG1() {
    val notReady = render(busy.copy(searchReady = false, described = 2))
    assertThat(notReady.ids()).contains("SMART_NO_SEARCH")
    assertThat(notReady.first { it.code.id == "SMART_NO_SEARCH" }.export["count"]).isEqualTo(ExportValue.Num(2))
    // Pronta, o nessuna descrizione: niente da dire.
    assertThat(render(busy.copy(searchReady = true)).ids()).doesNotContain("SMART_NO_SEARCH")
    assertThat(render(busy.copy(searchReady = false, described = 0)).ids()).doesNotContain("SMART_NO_SEARCH")
    // Gli album non montati non dicono dei loro smart.
    assertThat(render(AlbumsFacts(mounted = false, described = 3)).ids()).doesNotContain("SMART_NO_SEARCH")
  }

  @Test
  fun nessunaCasaSiDiceSoloDopoAverCercato_VG1() {
    assertThat(render(busy.copy(tripsSearched = true, tripsHomeKnown = false)).ids()).contains("TRIP_NO_GPS")
    // Non ancora cercato: «nessuna casa» non vuol dire niente.
    assertThat(render(busy.copy(tripsSearched = false, tripsHomeKnown = false)).ids()).doesNotContain("TRIP_NO_GPS")
    assertThat(render(busy.copy(tripsSearched = true, tripsHomeKnown = true)).ids()).doesNotContain("TRIP_NO_GPS")
  }

  @Test
  fun iRifiutiDuranoOltreIlGiroCheLiHaVisti_DG3() {
    val items = render(busy.copy(rejected = 3))
    assertThat(items.first { it.code.id == "ALBUM_CONTRIB_REJECTED" }.export["count"]).isEqualTo(ExportValue.Num(3))
    assertThat(render(busy.copy(rejected = 0)).ids()).doesNotContain("ALBUM_CONTRIB_REJECTED")
  }

  @Test
  fun iNuoviConteggiDegliAlbumCondivisiHannoLaLoroRigaSoloQuandoCeQualcosaDaDire_R22() {
    val quiet = render(busy.copy(unreadableRegisters = 0, heldContributions = 0, pausedCatalog = null)).ids()
    assertThat(quiet).containsNoneOf("ALBUM_REGISTRY_UNREADABLE", "ALBUM_CONTRIB_HELD", "SHARE_PAUSED_CATALOG")

    val items = render(busy.copy(unreadableRegisters = 2, heldContributions = 5, pausedCatalog = 1))
    assertThat(items.ids()).containsAtLeast("ALBUM_REGISTRY_UNREADABLE", "ALBUM_CONTRIB_HELD", "SHARE_PAUSED_CATALOG")
    assertThat(items.first { it.code.id == "ALBUM_REGISTRY_UNREADABLE" }.export["count"]).isEqualTo(ExportValue.Num(2))
    assertThat(items.first { it.code.id == "ALBUM_CONTRIB_HELD" }.export["count"]).isEqualTo(ExportValue.Num(5))
    assertThat(items.first { it.code.id == "SHARE_PAUSED_CATALOG" }.export["count"]).isEqualTo(ExportValue.Num(1))
    // Solo numeri, chiavi dell'elenco chiuso, codici del catalogo.
    assertThat(items.flatMap { it.export.keys }.distinct().filterNot(ExportKeys::isAllowed)).isEmpty()
    assertThat(items.map { it.code }.filterNot { it in DiagCatalog }).isEmpty()
  }

  @Test
  fun primaDelMontaggioCeSoloLoStatoSpento() {
    val items = render(AlbumsFacts())
    assertThat(items.ids()).containsExactly("ALBUMS_STATE")
    assertThat(items.single().export["mounted"]).isEqualTo(ExportValue.Flag(false))
  }

  @Test
  fun senzaGrafoMontatoIFattiDiconoNonMontato() = runBlocking<Unit> {
    val (diag, scope) = diagnostics()
    assertThat(diag.facts().mounted).isFalse()
    scope.cancel()
  }
}
