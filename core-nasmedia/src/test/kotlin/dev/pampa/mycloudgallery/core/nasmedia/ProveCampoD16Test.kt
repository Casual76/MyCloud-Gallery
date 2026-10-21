package dev.pampa.mycloudgallery.core.nasmedia

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.LocationKey
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nasmedia.scan.FileNotMediaMemory
import dev.pampa.mycloudgallery.core.nasmedia.scan.InMemoryNotMediaMemory
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * **D-16** (prove sul campo, `docs/prove-campo-esiti.md`, "Blocco A, resto"): a ogni processo nuovo la verifica riapriva tutti i file "non media"
 * (E-M12), perche' `DefaultNasScanner` li ricordava solo in memoria. Con 1.429 documenti nella share comune erano 61,7 MB e 87-91 secondi a ogni
 * verifica a freddo, contro 38,6 s a caldo. Adesso il ricordo sta su un file ([FileNotMediaMemory]): cio' che e' gia' stato riconosciuto "non
 * media" e non e' cambiato (dimensione e data invariate) non si rilegge; un file cambiato si rilegge, come prima.
 */
@RunWith(AndroidJUnit4::class)
class ProveCampoD16Test {

  @get:Rule
  val tmp = TemporaryFolder()

  private lateinit var h: ScanHarness
  private var generation = "app 1"

  @After
  fun close() {
    if (::h.isInitialized) h.close()
  }

  private val memoryFile: File get() = File(tmp.root, "nas-notmedia-private")

  /** Un harness il cui scanner, a ogni "processo nuovo" ([ScanHarness.restartScanner]), rilegge la memoria dei non media dal file. */
  private fun harness(): ScanHarness = ScanHarness(notMediaMemory = { FileNotMediaMemory(memoryFile, generation) }).also { h = it }

  private val text = "non sono una foto".repeat(100).toByteArray()

  /** Una foto vera, un file vuoto, un testo che si chiama .jpg e un video falso: tre "non media" (come E-M12). */
  private fun seed() {
    h.photo("Foto/a.jpg")
    h.nas.putFile("test", "Documenti/vuoto.jpg", NasContent.of(ByteArray(0)), h.nasNow - DAY)
    h.nas.putFile("test", "Documenti/testo.jpg", NasContent.of(text), h.nasNow - DAY)
    h.nas.putFile("test", "Documenti/falso.mp4", dev.pampa.mycloudgallery.core.nasmedia.testing.SyntheticNas.content(500_000, 4), h.nasNow - DAY)
  }

  /** (a) La causa e la correzione: dopo un avvio a freddo i "non media" gia' riconosciuti non si riaprono, e nemmeno un byte si rilegge. */
  @Test
  fun d16_inUnProcessoNuovoINonMediaGiaRiconosciutiNonSiRiaprono() = runTest {
    harness()
    seed()
    val first = h.scan()
    assertThat(first.notMedia).isEqualTo(3)
    assertThat(first.detected).isEqualTo(3) // la foto e i due non media da aprire (il vuoto non si apre)
    assertThat(memoryFile.isFile).isTrue()
    val opens = h.nas.opens.get()
    val bytes = h.nas.bytesRead.get()

    // Il processo muore e ne parte un altro: stesso NAS, stesso catalogo, la memoria dal file.
    h.restartScanner()
    val second = h.scan()

    assertThat(second.notMedia).isEqualTo(3)
    assertThat(second.detected).isEqualTo(0)
    assertThat(second.bytesRead).isEqualTo(0)
    assertThat(h.nas.opens.get()).isEqualTo(opens)
    assertThat(h.nas.bytesRead.get()).isEqualTo(bytes)
    assertThat(second.complete).isTrue()
    assertThat(h.store.knownCount(h.me, "test")).isEqualTo(1)
  }

  /** (b) Senza la memoria su file (come prima di D-16) un processo nuovo li riaprebbe tutti: la misura che il test (a) ha cambiato. */
  @Test
  fun d16_senzaLaMemoriaSuFileUnProcessoNuovoLiRiapre() = runTest {
    h = ScanHarness()
    seed()
    h.scan()
    h.restartScanner()
    val second = h.scan()
    assertThat(second.detected).isEqualTo(2)
    assertThat(second.bytesRead).isGreaterThan(0)
  }

  /** (c) La garanzia non si indebolisce: un file cambiato (un'altra dimensione, o la stessa e un'altra data) si rilegge; gli altri no. */
  @Test
  fun d16_unFileCambiatoSiRilegge() = runTest {
    harness()
    seed()
    h.scan()
    h.restartScanner()

    // testo.jpg cambia dimensione, falso.mp4 cambia solo la data di modifica: si rileggono questi due, in un processo nuovo.
    h.nas.putFile("test", "Documenti/testo.jpg", NasContent.of(text + "in coda".toByteArray()), h.nasNow - DAY)
    h.nas.putFile("test", "Documenti/falso.mp4", dev.pampa.mycloudgallery.core.nasmedia.testing.SyntheticNas.content(500_000, 4), h.nasNow - DAY / 2)
    val opens = h.nas.opens.get()
    val changed = h.scan()
    assertThat(changed.detected).isEqualTo(2)
    assertThat(changed.notMedia).isEqualTo(3)
    assertThat(h.nas.opens.get() - opens).isEqualTo(2)

    // Ricordati di nuovo con il timbro nuovo: il processo dopo non rilegge niente.
    h.restartScanner()
    val after = h.scan()
    assertThat(after.detected).isEqualTo(0)
    assertThat(after.notMedia).isEqualTo(3)
  }

  /** (d) Un "non media" che diventa un media (il file e' stato sostituito da una foto vera) entra in Libreria, e il ricordo cade. */
  @Test
  fun d16_unNonMediaChePoiDiventaUnaFotoEntraInLibreria() = runTest {
    harness()
    seed()
    h.scan()
    h.restartScanner()
    assertThat(h.store.knownCount(h.me, "test")).isEqualTo(1)

    h.photo("Documenti/testo.jpg", ageMs = DAY / 2, seed = 9)
    h.restartScanner()
    val report = h.scan()

    assertThat(report.detected).isEqualTo(1)
    assertThat(report.notMedia).isEqualTo(2)
    assertThat(h.store.knownCount(h.me, "test")).isEqualTo(2)
    // Nel processo dopo: niente da rilevare, e la foto resta in Libreria.
    h.restartScanner()
    val again = h.scan()
    assertThat(again.detected).isEqualTo(0)
    assertThat(h.store.knownCount(h.me, "test")).isEqualTo(2)
  }

  /** (e) Un'altra generazione (un'app piu' nuova potrebbe riconoscere un formato che prima non conosceva): si rilegge una volta, poi di nuovo il ricordo. */
  @Test
  fun d16_conUnAltraGenerazioneDelRiconoscitoreSiRileggeUnaVolta() = runTest {
    harness()
    seed()
    h.scan()
    generation = "app 2"
    h.restartScanner()
    val renewed = h.scan()
    assertThat(renewed.detected).isEqualTo(2)
    assertThat(renewed.notMedia).isEqualTo(3)

    h.restartScanner()
    assertThat(h.scan().detected).isEqualTo(0)
  }

  /** (f) Un file di ricordi illeggibile o che non e' nostro non rompe niente: vale "non ricordo", si rilegge, e il file si rimette a posto. */
  @Test
  fun d16_unFileDiRicordiRovinatoValeNonRicordoESiRimetteAPosto() = runTest {
    harness()
    seed()
    memoryFile.writeText("questo non e' un file di ricordi\n\u0000\u0001 12 34\n")
    val first = h.scan()
    assertThat(first.detected).isEqualTo(3)
    assertThat(first.notMedia).isEqualTo(3)

    h.restartScanner()
    assertThat(h.scan().detected).isEqualTo(0)
  }

  // --- La memoria su file, da sola ---------------------------------------------------------------------------------------------------

  private fun key(path: String) = LocationKey("nas:test:test:$path")

  @Test
  fun laMemoriaSuFileSiScriveESiRilegge() {
    val file = File(tmp.root, "m")
    val a = FileNotMediaMemory(file, "g")
    a.remember(key("documenti/relazione-privata.jpg"), NasStamp(100, 5_000))
    a.remember(key("documenti/altro.mp4"), NasStamp(200, 6_000))
    // Nel processo: subito. Su file: dopo save().
    assertThat(a.matches(key("documenti/altro.mp4"), NasStamp(200, 6_000))).isTrue()
    assertThat(file.exists()).isFalse()
    a.save()
    assertThat(file.isFile).isTrue()
    // Nessun nome o percorso su disco: solo impronte, dimensioni e date.
    assertThat(file.readText()).doesNotContain("relazione")
    assertThat(file.readText()).doesNotContain("documenti")
    assertThat(file.readText()).doesNotContain("test")

    val b = FileNotMediaMemory(file, "g")
    assertThat(b.matches(key("documenti/relazione-privata.jpg"), NasStamp(100, 5_000))).isTrue()
    // Dimensione o data diverse, o un'altra chiave: non vale. Il fileId non conta (come in memoria).
    assertThat(b.matches(key("documenti/relazione-privata.jpg"), NasStamp(101, 5_000))).isFalse()
    assertThat(b.matches(key("documenti/relazione-privata.jpg"), NasStamp(100, 5_001))).isFalse()
    assertThat(b.matches(key("documenti/relazione-privata.jpg"), NasStamp(100, 5_000, fileId = 77))).isTrue()
    assertThat(b.matches(key("documenti/mai-vista.jpg"), NasStamp(100, 5_000))).isFalse()
    // Un'altra generazione non crede ai ricordi.
    assertThat(FileNotMediaMemory(file, "altra").matches(key("documenti/altro.mp4"), NasStamp(200, 6_000))).isFalse()
  }

  @Test
  fun laMemoriaSuFileSiDimenticaESiSvuota() {
    val file = File(tmp.root, "m")
    val a = FileNotMediaMemory(file, "g")
    a.remember(key("x"), NasStamp(1, 2))
    a.save()
    assertThat(file.isFile).isTrue()
    a.forget(key("x"))
    assertThat(a.matches(key("x"), NasStamp(1, 2))).isFalse()
    a.save()
    // Vuota: il file non resta.
    assertThat(file.exists()).isFalse()
    assertThat(FileNotMediaMemory(file, "g").matches(key("x"), NasStamp(1, 2))).isFalse()
  }

  @Test
  fun laMemoriaSuFileHaUnTettoEFaCadereIPiuVecchi() {
    val file = File(tmp.root, "m")
    val a = FileNotMediaMemory(file, "g", limit = 3)
    for (i in 1..5) a.remember(key("f$i"), NasStamp(i.toLong(), 10L * i))
    a.save()
    val b = FileNotMediaMemory(file, "g", limit = 3)
    assertThat((1..5).filter { b.matches(key("f$it"), NasStamp(it.toLong(), 10L * it)) }).containsExactly(3, 4, 5)
    // La stessa cosa in memoria.
    val m = InMemoryNotMediaMemory(limit = 3)
    for (i in 1..5) m.remember(key("f$i"), NasStamp(i.toLong(), 10L * i))
    assertThat((1..5).filter { m.matches(key("f$it"), NasStamp(it.toLong(), 10L * it)) }).containsExactly(3, 4, 5)
  }

  @Test
  fun unaRigaCheNonSiCapisceNonDaScontiEUnaDiScritturaNonFaMale() {
    val file = File(tmp.root, "m")
    val a = FileNotMediaMemory(file, "g")
    a.remember(key("buono"), NasStamp(7, 8))
    a.save()
    // Una riga in coda che non si capisce: la riga buona resta, la cattiva no.
    file.appendText("non-un-digest 1 2\nabc\n" + "0".repeat(32) + " -5 3\n")
    val b = FileNotMediaMemory(file, "g")
    assertThat(b.matches(key("buono"), NasStamp(7, 8))).isTrue()
    assertThat(b.matches(key("altro"), NasStamp(1, 2))).isFalse()
    // Dove non si puo' scrivere (la cartella e' un file) non lancia: il ricordo vale nel processo, non sul disco.
    val blocker = File(tmp.root, "blocco").also { it.writeText("sono un file") }
    val c = FileNotMediaMemory(File(blocker, "m"), "g")
    c.remember(key("x"), NasStamp(1, 1))
    c.save()
    assertThat(c.matches(key("x"), NasStamp(1, 1))).isTrue()
  }
}
