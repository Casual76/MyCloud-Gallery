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
}
