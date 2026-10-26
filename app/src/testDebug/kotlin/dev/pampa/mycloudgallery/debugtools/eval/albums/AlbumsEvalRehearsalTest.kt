package dev.pampa.mycloudgallery.debugtools.eval.albums

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * La prova generale dello strumento sui viaggi sintetici (F87 con lo strumento al posto del PC): il file che il generatore scrive (`viaggi-sintetici.tsv`) portato
 * dentro con `adb`, i membri ritrovati per nome di file nel catalogo, la corsa automatica con l'utente ideale e il confronto con i numeri attesi dalla JVM.
 * Libreria sintetica (Oslo, 2025): nessuna foto vera.
 */
class AlbumsEvalRehearsalTest {
  @get:Rule val tmp = TemporaryFolder()

  private val tab = "\t"

  /** Un file di una storia con un album: i 12 scatti di Oslo e il familiare, con i numeri attesi dati (telefono, completo). */
  private fun importText(phone: List<String>, full: List<String>, members: List<Int>): String {
    val lines = ArrayList<String>()
    lines += "#MCGSINT1${tab}storia=0"
    lines += listOf("A", "0", "Oslo marzo 2025", "2025-03-14", "2025-03-16").plus(phone).plus(full).joinToString(tab)
    for (n in members) lines += listOf("M", "0", "IMG_$n.jpg").joinToString(tab)
    return lines.joinToString("\n") + "\n"
  }

  private fun rig(): AlbumsTestRig = AlbumsTestRig.osloTrip().also { rig ->
    for (n in (1..12) + listOf(100, 101)) rig.files["IMG_$n.jpg"] = rig.id(n)
  }

  private fun store(importText: String?): AlbumsEvalStore {
    val root = tmp.newFolder()
    val import = tmp.newFolder()
    if (importText != null) File(import, AlbumsEvalStore.IMPORT_FILE).writeText(importText)
    return AlbumsEvalStore(root, import)
  }

  @Test
  fun ilFileSiLeggeConAlbumNumeriEMembri() {
    val albums = SyntheticImport.parse(importText(listOf("12", "12", "12", "1", "12"), listOf("13", "12", "12", "2", "13"), listOf(1, 2, 101)))
    assertThat(albums).hasSize(1)
    val a = albums.single()
    assertThat(a.title).isEqualTo("Oslo marzo 2025")
    assertThat(a.from.toString()).isEqualTo("2025-03-14")
    assertThat(a.files).containsExactly("IMG_1.jpg", "IMG_2.jpg", "IMG_101.jpg")
    assertThat(a.phone!!.truth).isEqualTo(12)
    assertThat(a.full!!.suggested).isEqualTo(2)
    // Senza numeri attesi ("-") l'album c'e' lo stesso.
    val bare = SyntheticImport.parse(importText(List(5) { "-" }, List(5) { "-" }, listOf(1)))
    assertThat(bare.single().phone).isNull()
    assertThat(bare.single().full).isNull()
  }

  @Test
  fun unFileRovinatoDaUnErroreConIlNumeroDellaRigaMaiIlContenuto() {
    fun message(text: String) = assertThrows(IllegalArgumentException::class.java) { SyntheticImport.parse(text) }.message.orEmpty()
    assertThat(message("MCGEVAL1\n")).contains("intestazione")
    assertThat(message("#MCGSINT1\nX${tab}qualcosa di privato\n")).contains("Riga 2")
    assertThat(message("#MCGSINT1\nX${tab}qualcosa di privato\n")).doesNotContain("privato")
    assertThat(message("#MCGSINT1\nM${tab}7${tab}IMG_1.jpg\n")).contains("Riga 2")
    assertThat(message("#MCGSINT1\nA${tab}0${tab}Titolo${tab}2025-13-40${tab}2025-03-16\n")).contains("Riga 2")
  }

  @Test
  fun laProvaGeneraleDaGliStessiNumeriDellaJvm() = runTest {
    val text = importText(listOf("-", "-", "-", "-", "-"), listOf("13", "12", "12", "2", "13"), (1..12) + listOf(101))
    val rehearsal = AlbumsEvalRehearsal(rig(), store(text))
    assertThat(rehearsal.albumsInImport()).isEqualTo(1)
    val outcome = rehearsal.run()!!
    println(outcome.text)
    assertThat(outcome.albums).isEqualTo(1)
    // 12 scatti di Oslo sicuri; lo screenshot e il familiare suggeriti; il familiare e' del viaggio, lo screenshot no.
    assertThat(outcome.text).contains("1 album, 13 foto trovate nel catalogo su 13 elencate")
    assertThat(outcome.text).contains("0 | 13/13 | 13 | 12 (12) | 12 (12) | 2 (2) | 13 (13) | 1 | uguale")
    assertThat(outcome.text).contains("Album uguali alla JVM: 1 su 1")
    assertThat(outcome.text).contains("RAGGIUNTE")
    assertThat(outcome.allEqual).isTrue()
    assertThat(outcome.reportName).isEqualTo("report-01.txt")
    // Solo numeri: niente titolo, niente date, niente nomi di file.
    for (secret in listOf("Oslo", "2025", "IMG_")) assertThat(outcome.text).doesNotContain(secret)
    // Le etichette della prova generale sono nella sua cartella: la sessione le ha, con la verita' e i giudizi.
    val labels = rehearsal.session.labels.value
    assertThat(labels.trips).hasSize(1)
    assertThat(labels.inTripOf("v01")).hasSize(13)
    assertThat(labels.notInTripOf("v01")).containsExactly(rig().id(100).value)
  }

  @Test
  fun unNumeroDiversoDaQuelloAttesoSiVede() = runTest {
    // La JVM si aspettava un suggerito in piu': la prova generale lo dice con la differenza.
    val text = importText(listOf("-", "-", "-", "-", "-"), listOf("13", "12", "12", "3", "14"), (1..12) + listOf(101))
    val outcome = AlbumsEvalRehearsal(rig(), store(text)).run()!!
    assertThat(outcome.allEqual).isFalse()
    assertThat(outcome.text).contains("2 (3)")
    assertThat(outcome.text).contains("diverso: sicuri 0, coperte -1")
    assertThat(outcome.text).contains("Album uguali alla JVM: 0 su 1")
  }

  @Test
  fun senzaIFileDelTabletEDeiFamiliariVaLaVarianteDelSoloTelefono() = runTest {
    val r = rig().also { it.files.remove("IMG_101.jpg") }
    val text = importText(listOf("12", "12", "12", "1", "12"), listOf("13", "12", "12", "2", "13"), (1..12) + listOf(101))
    val outcome = AlbumsEvalRehearsal(r, store(text)).run()!!
    assertThat(outcome.text).contains("12 foto trovate nel catalogo su 13 elencate")
    assertThat(outcome.text).contains("solo telefono")
    // Il familiare c'e' nella Libreria della prova ma non e' del viaggio per l'utente: l'album lo suggerisce e la copertura e' piena.
    assertThat(outcome.text).contains("0 | 12/13 | 12 | 12 (12) | 12 (12) | 2 (1) | 12 (12) | 1 | diverso")
  }

  @Test
  fun senzaFileOSenzaAlbumNonFaNiente() = runTest {
    assertThat(AlbumsEvalRehearsal(rig(), store(null)).run()).isNull()
    assertThat(AlbumsEvalRehearsal(rig(), store(null)).albumsInImport()).isEqualTo(0)
    assertThat(AlbumsEvalRehearsal(rig(), store("#MCGSINT1\n")).run()).isNull()
    // Un album i cui file non sono nella Libreria: niente da misurare, ma la prova non si ferma.
    val none = importText(List(5) { "-" }, List(5) { "-" }, listOf(901, 902))
    val outcome = AlbumsEvalRehearsal(rig(), store(none)).run()!!
    assertThat(outcome.text).contains("0/2")
    assertThat(outcome.allEqual).isFalse()
  }

  @Test
  fun leEtichetteDellaProvaNonSiMescolanoConQuelleVere() = runTest {
    val real = File(tmp.root, "albums").apply { mkdirs() }
    val r = rig()
    val realSession = AlbumsEvalSession(r, AlbumsEvalStore(real)).also { it.load() }
    realSession.addTrip("Un viaggio vero", AlbumsTestRig.day(3, 14), AlbumsTestRig.day(3, 16))
    val text = importText(List(5) { "-" }, List(5) { "-" }, (1..12).toList())
    AlbumsEvalRehearsal(r, store(text)).run()
    // Il file dei viaggi veri e' rimasto com'era.
    val back = AlbumsEvalSession(r, AlbumsEvalStore(real)).also { it.load() }
    assertThat(back.labels.value.trips).hasSize(1)
    assertThat(back.labels.value.trips.single().title).isEqualTo("Un viaggio vero")
    assertThat(back.labels.value.inTripOf("v01")).isEmpty()
  }
}
