package dev.pampa.mycloudgallery.faces

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Fase 8, P13 (§2.8 invariante 1, D29, E-F35): **ogni** apertura dei dati dei volti in `:app` passa dal recinto (`FacesEraseFence`), c'e' **un solo**
 * `FacesStores` per processo, e le chiavi dei volti si distruggono solo da chi ha il compito (lo stop, il portachiavi dell'azzeramento). E' una rete a maglie
 * larghe sul testo dei sorgenti (commenti esclusi), sul modello dei test omonimi degli altri moduli: la regola e' questa, il test impedisce la distrazione.
 */
class FacesFenceSourcesTest {

  private val base = File("src/main/kotlin/dev/pampa/mycloudgallery")

  private fun sources(): List<Pair<String, String>> =
    base.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.relativeTo(base).invariantSeparatorsPath to withoutComments(it.readText()) }.toList()

  /** Il codice senza commenti (a blocchi e di riga): una scansione a maglie larghe, come quella di `NoNetworkSources` (interna a core-common). */
  private fun withoutComments(source: String): String =
    source.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""(?m)(^|\s)//.*$"""), "")

  private fun lines(source: String): List<String> = source.lineSequence().toList()

  @Test
  fun leTrePorteDegliArchiviSiPrendonoSoloDietroIlRecinto() {
    val doors = listOf("stores.archives()", "stores.replicaStore()", "stores.publishStore()")
    val bare = ArrayList<String>()
    for ((path, text) in sources()) {
      for (line in lines(text)) {
        if (doors.none { it in line }) continue
        if ("Fenced" !in line) bare += "$path: ${line.trim()}"
      }
    }
    assertWithMessage("aperture dei dati dei volti fuori dal recinto: $bare").that(bare).isEmpty()
    // Controllo positivo: la scansione vede i tre usi del montaggio.
    val wiring = sources().single { it.first == "faces/FacesWiring.kt" }.second
    assertThat(doors.count { it in wiring }).isEqualTo(3)
  }

  @Test
  fun ilNegozioDelCalcoloEIlRecintoSiCostruisconoSoloInsieme() {
    val wiring = sources().single { it.first == "faces/FacesWiring.kt" }.second
    assertThat(wiring).contains("FencedStoreSource(fence, stores)")
    assertThat(wiring).contains("FacesEraseFence(switch)")
    // La cancellazione locale e il recinto nascono prima di qualunque apertura (il cancello `started` apre il calcolo e la replica solo dopo).
    assertThat(wiring.indexOf("FacesEraseFence(switch)")).isLessThan(wiring.indexOf("FencedArchives(fence"))
    assertThat(wiring.indexOf("started.complete(Unit)")).isGreaterThan(wiring.indexOf("DefaultFacesEraser("))
  }

  @Test
  fun unSoloFacesStoresPerProcesso() {
    val builders = sources().filter { (_, text) -> Regex("""\bFacesStores\s*\(""").containsMatchIn(text) }.map { it.first }
    assertThat(builders).containsExactly("faces/FacesWiring.kt")
  }

  @Test
  fun leChiaviDeiVoltiLeDistruggonoSoloLoStopEIlPortachiavi() {
    val destroyers = sources().filter { (_, text) -> "destroyAll()" in text && "facesKeys" in text || "eraseEverything()" in text }.map { it.first }.sorted()
    // Lo stop delle persone (`FacesGraph.stop`), il portachiavi dell'azzeramento dei dati locali e quello delle credenziali del dispositivo (`AppGraph`).
    assertThat(destroyers).containsExactly("faces/FacesGraph.kt", "graph/AppGraph.kt", "graph/LocalData.kt")
  }

  @Test
  fun iMotoriDeiVoltiSiCostruisconoSoloQuandoServono() {
    // Nessuna classe dei motori (rilevatore, embedder, sessioni LiteRT) si carica prima dell'attivazione: la fabbrica sta in un solo punto, dentro la funzione che
    // il calcolo chiama quando ha qualcosa da fare (`FacesEngineHolder`), e nessun altro file di :app nomina le classi dei motori veri.
    val users = sources().filter { (_, text) -> Regex("""\bFaceEnginesFactory\b|\bFaceLiteRt\w*|\bYuNet[A-Z]\w*|\bSFace[A-Z]\w*""").containsMatchIn(text) }.map { it.first }
    assertThat(users).containsExactly("faces/FacesWiring.kt")
    val wiring = sources().single { it.first == "faces/FacesWiring.kt" }.second
    assertThat(wiring).contains("FacesEngineHolder { testing?.engines?.invoke() ?: FaceEnginesFactory.create(ensuring) }")
    // Il segnaposto e' l'unico posto che chiama la fabbrica: una sola volta.
    assertThat(Regex("""FaceEnginesFactory\.create""").findAll(wiring).count()).isEqualTo(1)
  }

  @Test
  fun leFacciateDelleAperturePassanoDalGrafoENonLoAprono() {
    // Le facciate (persone, ritagli, interruttore) leggono `graph.live`: nessuna di loro costruisce un archivio.
    val facades = sources().single { it.first == "faces/FacesFacades.kt" }.second
    assertThat(facades).doesNotContain("FacesStores")
    assertThat(facades).doesNotContain("archives.open(")
  }

  // --- Correzioni A della Fase 8 -------------------------------------------------------------------------------------------------------

  /**
   * I1: la cancellazione nasce **prima** di tutto cio' che apre i dati dei volti. `DefaultFacesEraser` legge il diario e chiude il recinto nel suo costruttore;
   * `ArchivePeople` lancia subito una lettura che apre l'archivio dal recinto, e il calcolo e il custode lo aprono appena partono (C130: il recinto e' chiuso prima
   * di ogni apertura, per costruzione).
   */
  @Test
  fun i1_laCancellazioneNascePrimaDiOgniLetturaDeiVolti() {
    val wiring = sources().single { it.first == "faces/FacesWiring.kt" }.second
    val eraser = wiring.indexOf("DefaultFacesEraser(")
    assertThat(eraser).isAtLeast(0)
    for (opener in listOf("ArchivePeople(", "CustodianGrouping(", "FacesComputeEngine(", "NasFacesPublisher(", "NasFacesSync(", "GuardedPeopleCrops(")) {
      val at = wiring.indexOf(opener)
      assertWithMessage("$opener deve esserci").that(at).isAtLeast(0)
      assertWithMessage("DefaultFacesEraser( deve nascere prima di $opener").that(eraser).isLessThan(at)
    }
    // Il recinto, poi la cancellazione: il resto dopo.
    assertThat(wiring.indexOf("FacesEraseFence(switch)")).isLessThan(eraser)
  }

  /** R11: la cancellazione locale butta tutto cio' che le persone tengono in memoria (nomi, raggruppamento, ultimo archivio, forma della pagina). */
  @Test
  fun r11_laCancellazioneLocaleButtaLaMemoria() {
    val wiring = sources().single { it.first == "faces/FacesWiring.kt" }.second
    val memory = wiring.substringAfter("memory = {").substringBefore("},")
    for (call in listOf("cropsRef[0]?.clear()", "peopleRef[0]?.release()", "archivesRef[0]?.forget()", "InstantShapes.remove(PEOPLE_INSTANT_SHAPE_CODE)", "arrivals.tryEmit(Unit)")) {
      assertWithMessage("la cancellazione locale deve fare $call").that(memory).contains(call)
    }
    // E le persone e gli archivi si ricordano per essere buttati.
    assertThat(wiring).contains("peopleRef[0] = people")
    assertThat(wiring).contains("archivesRef[0] = archives")
    // Senza i dati dei volti qui la forma ricordata della pagina Persone si toglie.
    val graph = sources().single { it.first == "faces/FacesGraph.kt" }.second
    assertThat(graph).contains("InstantShapes.remove(PEOPLE_INSTANT_SHAPE_CODE)")
  }

  /** R1: l'interruttore guarda le generazioni sul disco, non solo quella che `faces-local` ricorda. */
  @Test
  fun r1_lInterruttoreGuardaIlDiscoDellaCancellazioneLocale() {
    val wiring = sources().single { it.first == "faces/FacesWiring.kt" }.second
    assertThat(wiring).contains("override suspend fun generationsOnDisk()")
    assertThat(wiring).contains("localErase.generationsOnDisk()")
  }

  /** CE-2: il giro dell'indice riprende il diario in qualunque stato (dopo la parte locale lo stato e' `Off`), tra la pulizia del NAS e la replica. */
  @Test
  fun ce2_ilGiroDellIndiceRiprendeIlDiarioInQualunqueStato() {
    val graph = sources().single { it.first == "faces/FacesGraph.kt" }.second
    val round = graph.substringAfter("suspend fun syncRound").substringBefore("suspend fun computeStage")
    val resumes = Regex("""live\.eraser\.resume\(budget\)""").findAll(round).map { it.range.first }.toList()
    assertThat(resumes).hasSize(2)
    assertThat(resumes[1]).isGreaterThan(round.indexOf("live.janitor.run(budget)"))
    assertThat(resumes[1]).isLessThan(round.indexOf("if (state !is FacesState.On"))
  }

  /** CE-5: il tasto "Apri Dispositivi" del foglio porta a `SettingsDevicesRoute` (la stessa rotta di `onOpenDevices` della revisione F della Fase 7). */
  @Test
  fun ce5_ilFoglioHaIlTastoDispositivi() {
    val nav = sources().single { it.first == "navigation/PeopleNavigation.kt" }.second
    assertThat(nav).contains("FacesEraseRoute(onClose = { nav.popBackStack() }, onOpenDevices = { nav.navigate(SettingsDevicesRoute) })")
    assertThat(nav).contains("import dev.pampa.mycloudgallery.feature.settings.SettingsDevicesRoute")
  }
}
