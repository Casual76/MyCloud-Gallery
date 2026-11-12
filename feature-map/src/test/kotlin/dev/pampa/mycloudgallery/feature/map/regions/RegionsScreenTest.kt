package dev.pampa.mycloudgallery.feature.map.regions

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.map.contract.CameraSpot
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.core.map.contract.PlaceHit
import dev.pampa.mycloudgallery.core.map.contract.RegionId
import dev.pampa.mycloudgallery.core.map.contract.RegionRadius
import dev.pampa.mycloudgallery.core.map.contract.RegionSpec
import dev.pampa.mycloudgallery.core.map.contract.RegionState
import dev.pampa.mycloudgallery.core.map.contract.RegionStatus
import dev.pampa.mycloudgallery.core.map.contract.RegionStop
import dev.pampa.mycloudgallery.core.map.regions.RegionCatalog
import dev.pampa.mycloudgallery.core.map.regions.RegionPlanner
import dev.pampa.mycloudgallery.feature.map.LARGE_TEXT
import dev.pampa.mycloudgallery.feature.map.contract.MapScreenState
import dev.pampa.mycloudgallery.feature.map.map.MapInitial
import dev.pampa.mycloudgallery.feature.map.map.MapScreen
import dev.pampa.mycloudgallery.feature.map.map.MapStyles
import dev.pampa.mycloudgallery.feature.map.map.MapUi
import dev.pampa.mycloudgallery.feature.map.map.NoMapHandlers
import dev.pampa.mycloudgallery.feature.map.support.MapRig
import dev.pampa.mycloudgallery.feature.map.testing.FakeMapSurface
import dev.pampa.mycloudgallery.feature.map.MapScreenTest
import dev.pampa.mycloudgallery.feature.map.SHOT_PHONE_QUALIFIERS
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private fun spec(code: String): RegionSpec = RegionCatalog.entryOf(RegionId(code))!!.spec

private fun offers(kind: (RegionSpec) -> Boolean, present: Map<RegionId, RegionState> = emptyMap()) =
  RegionCatalog.specs().filter(kind).map { RegionOffer(it, RegionPlanner.plan(it), present[it.id]) }

/** Le scene della pagina, con dati di prova (nessuna zona vera dell'utente: regioni del catalogo e un luogo qualunque). */
internal object RegionScenes {
  val umbria = RegionState(spec("it-umbria"), RegionStatus.READY, 528, 528, 21_300_000, 20261001, maxZoom = 12)
  val marche = RegionState(spec("it-marche"), RegionStatus.DOWNLOADING, 300, 731, 9_800_000, null, maxZoom = 12)
  val toscana = RegionState(spec("it-toscana"), RegionStatus.PARTIAL, 120, 460, 7_000_000, null, maxZoom = 11, stop = RegionStop.NETWORK)
  val vecchia = RegionState(spec("it-liguria"), RegionStatus.NEEDS_UPDATE, 724, 724, 20_000_000, 20260301, maxZoom = 12)
  val stuck = RegionState(spec("it-lombardia"), RegionStatus.PARTIAL, 380, 454, 50_100_000, null, maxZoom = 11, stop = RegionStop.LIMIT_BYTES)
  val around = RegionState(RegionSpec.AroundPlace(11, RegionRadius.KM_30, GeoE6(43_300_000, 11_300_000), urban = true, name = "Cittàdiprova"), RegionStatus.READY, 1_700, 1_700, 30_000_000, 20261015, maxZoom = 13)

  fun empty() = RegionsUi(italian = offers(kind = { it.id.code.startsWith("it-") }), countries = offers(kind = { it.id.code.startsWith("country-") }))

  fun withDownloads(): RegionsUi {
    val mine = listOf(marche, toscana, umbria, vecchia, around)
    val byId = mine.associateBy { it.spec.id }
    return RegionsUi(
      downloaded = mine,
      italian = offers(kind = { it.id.code.startsWith("it-") }, present = byId),
      countries = offers(kind = { it.id.code.startsWith("country-") }, present = byId),
      usedBytes = mine.sumOf { it.bytes },
    )
  }

  val hit = PlaceHit(11, "Cittàdiprova", "Regione di prova, Italia", GeoE6(43_300_000, 11_300_000), urban = true)
  val hit2 = PlaceHit(12, "Cittàvicina", "Regione di prova, Italia", GeoE6(43_000_000, 11_100_000), urban = false)

  fun radii(): List<RadiusOffer> = RegionRadius.entries.map {
    val spec = RegionSpec.AroundPlace(hit.placeId, it, hit.center, hit.urban, hit.name)
    RadiusOffer(spec, RegionPlanner.plan(spec), null)
  }
}

/** I fogli e la pagina sopra uno sfondo con l'host dei pop-up (come alla radice dell'app). */
@Composable
internal fun RegionsStage(content: @Composable () -> Unit) {
  val host = rememberFluidGlassModalHostState()
  val backdrop = rememberGlassBackdrop()
  CompositionLocalProvider(LocalFluidGlassModalHostState provides host) {
    Box(Modifier.fillMaxSize()) {
      Box(Modifier.fillMaxSize().glassBackdropSource(backdrop)) { content() }
      FluidGlassModalHost(state = host, backdrop = backdrop)
    }
  }
}

/** La pagina "Zone senza Internet": le frasi, i bersagli da 48 dp, i tocchi, a testo normale e grande (Fase 9, P5). */
internal class RegionsScreenTest : MapScreenTest() {

  private class Recorder : RegionsHandlers by NoRegionsHandlers {
    val calls = mutableListOf<String>()
    override fun onCatalog(spec: RegionSpec) { calls += "catalogo" }
    override fun onDownloaded(id: RegionId) { calls += "scaricata" }
    override fun onOpenAround() { calls += "luogo" }
    override fun onConfirm() { calls += "conferma" }
    override fun onDismiss() { calls += "annulla" }
    override fun onResume() { calls += "riprendi" }
    override fun onUpdate() { calls += "aggiorna" }
    override fun onAskDelete() { calls += "chiedi-elimina" }
    override fun onDelete() { calls += "elimina" }
    override fun onRadius(offer: RadiusOffer) { calls += "raggio" }
    override fun onPick(hit: PlaceHit) { calls += "luogo-scelto" }
    override fun onDismissMessage() { calls += "avviso" }
  }

  private fun page(ui: RegionsUi, handlers: RegionsHandlers = NoRegionsHandlers): @Composable () -> Unit = { RegionsStage { RegionsScreen(ui, handlers) } }

  /** La pagina e' una lista pigra: cio' che sta sotto la piega si compone solo scorrendo. Controlla ogni frase, scorrendo fino a lei se non c'e' ancora. */
  private fun assertShowsScrolling(scene: String, vararg texts: String) {
    for (text in texts) {
      if (!shows(text)) {
        val lists = composeRule.onAllNodes(hasScrollAction())
        if (lists.fetchSemanticsNodes().isNotEmpty()) runCatching { lists[0].performScrollToNode(hasText(text, substring = true)) }
        composeRule.waitForIdle()
      }
      assertThat(shows(text)).isTrue()
    }
  }

  @Test
  fun ilCatalogoDiceDimensioneELivelloEOgniVoceSiTocca() {
    val rec = Recorder()
    showAll(content = page(RegionScenes.empty(), rec))
    assertShowsScrolling("catalogo", "Zone senza Internet", "La zona attorno a un luogo", "REGIONI ITALIANE", "Toscana", "strade principali, paesi, acqua e boschi")
    assertThat(isClickable("Toscana")).isTrue()
    clickText("Toscana")
    assertShowsScrolling("paesi", "PAESI VICINI", "Francia", "Troppo grande per scaricarla")
    clickText("Francia")
    assertShowsScrolling("alto", "La zona attorno a un luogo")
    clickText("La zona attorno a un luogo")
    assertThat(rec.calls).containsExactly("catalogo", "catalogo", "luogo").inOrder()
  }

  @Test
  fun leZoneScaricateDiconoLoStato() {
    showAll(content = page(RegionScenes.withDownloads()))
    assertShowsScrolling(
      "scaricate", "SCARICATE", "Scarico: 41%", "aspetta il Wi-Fi", "Completa", "da aggiornare", "Cittàdiprova, 30 km",
      "Le zone scaricate occupano", "al massimo 1 GB",
    )
  }

  @Test
  fun unaZonaFermataDaiLimitiDiceCheSiEliminaEBasta() {
    showAll(content = page(RegionScenes.empty().copy(downloaded = listOf(RegionScenes.stuck), usedBytes = RegionScenes.stuck.bytes)))
    assertShows("limite", "si è fermata ai limiti di OpenFreeMap", "eliminala e scegli una zona più piccola")
  }

  @Test
  fun gliAvvisiDiconoPerche_NonSiScarica() {
    val switch = switching<RegionMessage?>(null) { m -> RegionsStage { RegionsScreen(RegionScenes.empty().copy(message = m), NoRegionsHandlers) } }
    switch(RegionMessage.TooLarge)
    assertShows("grande", "Questa zona è troppo grande: scegline una più piccola.")
    switch(RegionMessage.NeedsWifi)
    assertShows("wifi", "serve il Wi-Fi", "mai in roaming", "Solo sul Wi-Fi")
    switch(RegionMessage.Busy)
    assertShows("occupato", "Si sta già scaricando un’altra zona")
    switch(RegionMessage.NoSpace(1_500_000_000L))
    assertShows("spazio", "liberane almeno 1,5 GB")
    switch(RegionMessage.Expired)
    assertShows("scaduta", "la conferma era scaduta")
  }

  @Test
  fun ilFoglioDiConfermaDiceChiVedeLaZonaEServeUnTocco() {
    val rec = Recorder()
    val estimate = RegionPlanner.plan(spec("it-umbria"))
    showAll { RegionsStage { RegionsScreen(RegionScenes.empty().copy(sheet = RegionSheet.Confirm(spec("it-umbria"), estimate, SheetPurpose.DOWNLOAD)), rec) } }
    assertShows("conferma", "Scarica «Umbria»?", "sul Wi-Fi", "OpenFreeMap vedrà quale zona scarichi, una volta", "Scarica", "Annulla")
    clickText("Scarica")
    assertThat(rec.calls).isEqualTo(listOf("conferma"))
    assertTouchTargets("foglio di conferma")
  }

  @Test
  fun suiDatiMobiliSecondaConfermaConLaDimensione() {
    val estimate = RegionPlanner.plan(spec("it-umbria"))
    showAll { RegionsStage { RegionsScreen(RegionScenes.empty().copy(sheet = RegionSheet.Confirm(spec("it-umbria"), estimate, SheetPurpose.DOWNLOAD, mobileBytes = estimate.bytes)), NoRegionsHandlers) } }
    assertShows("mobile", "Stai usando i dati mobili", "Lo scaricamento di «Umbria» userà circa", "dei tuoi dati mobili", "Scarica con i dati mobili")
  }

  @Test
  fun gestireUnaZonaScaricata() {
    val rec = Recorder()
    val switch = switching<RegionSheet>(RegionSheet.Manage(RegionScenes.umbria), content = { sheet -> RegionsStage { RegionsScreen(RegionScenes.withDownloads().copy(sheet = sheet), rec) } })
    assertShows("gestione", "Umbria", "Funziona senza Internet", "Aggiorna", "Elimina", "Chiudi")
    clickText("Aggiorna")
    switch(RegionSheet.Manage(RegionScenes.toscana))
    assertShows("parziale", "Interrotta al 26%", "Riprendi")
    clickText("Riprendi")
    switch(RegionSheet.Manage(RegionScenes.umbria, confirmDelete = true))
    assertShows("elimina", "Eliminare «Umbria»?", "Potrai scaricarla di nuovo")
    clickText("Elimina", substring = false)
    assertThat(rec.calls).containsAtLeast("aggiorna", "riprendi", "elimina")
  }

  @Test
  fun laZonaAttornoAUnLuogo() {
    val rec = Recorder()
    val switch = switching(AroundState(query = "Citt", hits = listOf(RegionScenes.hit, RegionScenes.hit2), searched = true)) { a ->
      RegionsStage { RegionsScreen(RegionScenes.empty().copy(around = a), rec) }
    }
    assertShows("ricerca", "La zona attorno a un luogo", "Cittàdiprova", "Regione di prova, Italia", "non parte mai dalla tua casa né dalle tue foto")
    clickText("Cittàdiprova")
    switch(AroundState(picked = RegionScenes.hit, radii = RegionScenes.radii()))
    assertShows("raggi", "RAGGIO ATTORNO A «CITTÀDIPROVA»", "15 km", "30 km", "60 km", "circa")
    clickText("30 km")
    switch(AroundState(query = "zzz", searched = true))
    assertShows("nessuno", "Nessun luogo trovato")
    switch(AroundState(available = false))
    // Giro 2 (CS-17): lo stato e' il titolo della figura, nessuna frase.
    assertShows("senza dataset", "Elenco dei luoghi non disponibile")
    assertThat(rec.calls).containsAtLeast("luogo-scelto", "raggio")
  }

  private fun scenes(scale: Float?) = checkScenes(
    scale,
    "catalogo" to { RegionsStage { RegionsScreen(RegionScenes.empty(), NoRegionsHandlers) } },
    "scaricate" to { RegionsStage { RegionsScreen(RegionScenes.withDownloads(), NoRegionsHandlers) } },
    "luogo" to { RegionsStage { RegionsScreen(RegionScenes.empty().copy(around = AroundState(picked = RegionScenes.hit, radii = RegionScenes.radii())), NoRegionsHandlers) } },
  )

  @Test
  fun bersagliDa48dpENessunaSovrapposizione() = scenes(null)

  @Test
  fun bersagliDa48dpENessunaSovrapposizione_ATestoGrande() = scenes(LARGE_TEXT)
}

/** Gli screenshot della pagina (`docs/screenshots/9-P5/`), chiaro e scuro, telefono: catalogo, scaricamento in corso, i fogli, la zona attorno a un luogo, la mappa con l'avanzamento. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE_QUALIFIERS)
internal class RegionsScreenshotTest(private val dark: Boolean) : MapScreenTest() {

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }

  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun shoot(name: String, vararg expect: String, content: @Composable () -> Unit) {
    showAll(dark = dark, content = content)
    assertShows(name, *expect)
    composeRule.onRoot().captureRoboImage("../docs/screenshots/9-P5/$name-$look.png")
  }

  @Test fun catalogo() = shoot("regioni-catalogo", "Zone senza Internet", "La zona attorno a un luogo", "REGIONI ITALIANE") { RegionsStage { RegionsScreen(RegionScenes.empty(), NoRegionsHandlers) } }

  @Test fun scaricamento() = shoot("regioni-scaricamento", "Scarico: 41%", "aspetta il Wi-Fi", "da aggiornare") { RegionsStage { RegionsScreen(RegionScenes.withDownloads(), NoRegionsHandlers) } }

  @Test fun fermataDaiLimiti() = shoot("regioni-limite", "si è fermata ai limiti di OpenFreeMap") {
    RegionsStage { RegionsScreen(RegionScenes.empty().copy(downloaded = listOf(RegionScenes.umbria, RegionScenes.stuck), usedBytes = 71_400_000), NoRegionsHandlers) }
  }

  @Test fun avvisoTroppoGrande() = shoot("regioni-troppo-grande", "Questa zona è troppo grande: scegline una più piccola.") {
    RegionsStage { RegionsScreen(RegionScenes.empty().copy(message = RegionMessage.TooLarge), NoRegionsHandlers) }
  }

  @Test fun foglioDiConferma() = shoot("regioni-conferma", "Scarica «Toscana»?", "OpenFreeMap vedrà quale zona scarichi, una volta") {
    val toscana = spec("it-toscana")
    RegionsStage { RegionsScreen(RegionScenes.empty().copy(sheet = RegionSheet.Confirm(toscana, RegionPlanner.plan(toscana), SheetPurpose.DOWNLOAD)), NoRegionsHandlers) }
  }

  @Test fun foglioDatiMobili() = shoot("regioni-dati-mobili", "Stai usando i dati mobili") {
    val umbria = spec("it-umbria")
    val plan = RegionPlanner.plan(umbria)
    RegionsStage { RegionsScreen(RegionScenes.empty().copy(sheet = RegionSheet.Confirm(umbria, plan, SheetPurpose.DOWNLOAD, mobileBytes = plan.bytes)), NoRegionsHandlers) }
  }

  @Test fun gestioneDiUnaZona() = shoot("regioni-gestione", "Funziona senza Internet", "Aggiorna", "Elimina") {
    RegionsStage { RegionsScreen(RegionScenes.withDownloads().copy(sheet = RegionSheet.Manage(RegionScenes.umbria)), NoRegionsHandlers) }
  }

  @Test fun zonaAttornoAUnLuogo() = shoot("regioni-luogo-ricerca", "Cittàdiprova", "non parte mai dalla tua casa") {
    RegionsStage { RegionsScreen(RegionScenes.empty().copy(around = AroundState(query = "Citt", hits = listOf(RegionScenes.hit, RegionScenes.hit2), searched = true)), NoRegionsHandlers) }
  }

  /** La mappa con l'avanzamento di uno scaricamento (la carta e' quella finta: lo screenshot dice come stanno controlli e riga, non com'e' disegnata la carta). */
  @Test fun mappaConAvanzamento() = shoot("regioni-mappa", "Scarico una zona senza Internet: 41%") {
    val center = GeoE6(43_000_000, 12_000_000)
    val on = MapOnline.On(byDefault = true)
    val surface = FakeMapSurface(center, zoom = 6.0, widthDp = 412, heightDp = 915, density = 2f)
    val rig = MapRig()
    MapScreen(
      MapUi(MapScreenState.Ready(on, null, 1_240), MapStyles.choose(on, hasRegions = true, dark = dark), MapInitial.Saved(CameraSpot(center, 6.0)), download = 41),
      surface, surface.content(), images.thumbnails, rig.snapshot, NoMapHandlers, Modifier.fillMaxSize(),
    )
  }

  @Test fun zonaAttornoAUnLuogoRaggi() = shoot("regioni-luogo-raggi", "RAGGIO ATTORNO A «CITTÀDIPROVA»", "30 km") {
    RegionsStage { RegionsScreen(RegionScenes.empty().copy(around = AroundState(picked = RegionScenes.hit, radii = RegionScenes.radii())), NoRegionsHandlers) }
  }
}
