package dev.pampa.mycloudgallery.core.nasmedia

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.LocationKey
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanStop
import dev.pampa.mycloudgallery.core.nasmedia.scan.ScanConfig
import dev.pampa.mycloudgallery.core.nasmedia.testing.SyntheticNas
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Dai piu' recenti" anche per l'ordine delle cartelle (proposta 11): in `Backup/<dispositivo>/AAAA/MM/` si parte dall'anno e
 * dal mese piu' recenti. La ripresa a meta' continua a funzionare: il giro ricorda l'ordine con cui e' cominciato.
 */
@RunWith(AndroidJUnit4::class)
class ScanFolderOrderTest {

  private lateinit var h: ScanHarness

  /** 2026-10-14T00:00:00Z: l'oggi del progetto (e l'ora del NAS finto di default). */
  private val october2026 = 1_791_936_000_000L

  @After
  fun close() {
    if (::h.isInitialized) h.close()
  }

  private fun yearOf(millis: Long): Int = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).year

  private fun pathOf(key: LocationKey): String = key.value.substringAfter("nas:test:test:")

  private suspend fun mtimeOf(path: String): Long =
    ((h.nas.reader.stat("test", SharePath.parse(path), Purpose.BACKGROUND) as NasResult.Ok).value!!).modifiedAt

  @Test
  fun iPrimi500RilevamentiDiAlessioSonoDelLAnnoPiuRecente() = runTest {
    // La share privata con le proporzioni dell'inventario (12 mila file, una cartella da 8.910) piu' l'albero delle copie dei
    // telefoni: Backup/Pixel 9/AAAA/MM dal 2015.
    h = ScanHarness(
      shuffle = Random(4),
      config = ScanConfig(batchSize = 25, detectConcurrency = 1, cursorSaveEvery = 1),
      latency = { if (it == Op.OPEN) 10L else 0L },
      writeMetadata = false,
    )
    h.phoneNow = october2026 - h.nasOffset
    h.synthetic.populate(SyntheticNas.Profile.INVENTARIO, privateShare = "test", commonShare = "test-comune")
    h.synthetic.populateBackup("test", firstYear = 2015, lastYear = 2026, lastMonth = 10, perMonth = 20)

    val newest = yearOf(h.nasNow)
    val tick = h.scan(ScanMode.Full, budgetMs = 5_400) // ~540 aperture
    assertThat(tick.stoppedBy).isEqualTo(ScanStop.BUDGET)
    assertThat(tick.detected).isAtLeast(500)

    val first500 = h.store.recordedOrder.take(500)
    assertThat(first500).hasSize(500)
    val years = first500.map { yearOf(mtimeOf(pathOf(it))) }
    assertThat(years.toSet()).containsExactly(newest)
    // La prima cartella dopo la radice e' quella con la data di modifica piu' recente.
    val root = ArrayList<Pair<String, Long>>()
    h.nas.reader.list("test", null, Purpose.BACKGROUND) { page -> page.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "@eaDir" }.forEach { root += it.name to it.modifiedAt } }
    assertThat(h.reader.listed[1]).isEqualTo(root.maxBy { it.second }.first)
  }

  @Test
  fun inBackupSiParteDalLAnnoEDalMesePiuRecentiEPoiSiScendeFinoAlPiuVecchio() = runTest {
    h = ScanHarness(
      config = ScanConfig(batchSize = 20, detectConcurrency = 1, cursorSaveEvery = 1),
      latency = { if (it == Op.OPEN) 10L else 0L },
      writeMetadata = false,
    )
    h.phoneNow = october2026 - h.nasOffset
    val paths = h.synthetic.populateBackup("test", firstYear = 2015, lastYear = 2026, lastMonth = 10, perMonth = 60)

    val first = h.scan(ScanMode.Full, budgetMs = 5_300)
    assertThat(first.stoppedBy).isEqualTo(ScanStop.BUDGET)
    val order = h.store.recordedOrder.map { pathOf(it) }
    val firstYears = order.take(500).map { it.split('/')[2].toInt() }
    val firstMonths = order.take(500).map { it.split('/')[3].toInt() }
    // Il 2026 ha 10 mesi da 60 foto: i primi 500 sono tutti del 2026, dal mese 10 in giu', senza salti all'indietro.
    assertThat(firstYears.toSet()).containsExactly(2026)
    assertThat(firstMonths).isInOrder(Comparator.reverseOrder<Int>())
    assertThat(firstMonths.first()).isEqualTo(10)

    // La ripresa: il resto, senza rifare niente.
    val second = h.scan(ScanMode.Full)
    assertThat(second.resumed).isTrue()
    assertThat(second.complete).isTrue()
    assertThat(second.vanished).isEqualTo(0)
    assertThat(h.reader.opens).isEqualTo(paths.size)
    assertThat(h.store.knownCount(h.me, "test")).isEqualTo(paths.size)
    // Su tutto il giro gli anni non risalgono mai, e dentro un anno i mesi nemmeno.
    val sequence = h.store.recordedOrder.map { pathOf(it) }.map { it.split('/')[2].toInt() * 100 + it.split('/')[3].toInt() }
    assertThat(sequence).isInOrder(Comparator.reverseOrder<Int>())
    // Gli elenchi lo dicono: 2026 prima del 2025, il 10 prima del 09.
    val listed = h.reader.listed
    assertThat(listed.indexOf("Backup/Pixel 9/2026")).isLessThan(listed.indexOf("Backup/Pixel 9/2025"))
    assertThat(listed.indexOf("Backup/Pixel 9/2026/10")).isLessThan(listed.indexOf("Backup/Pixel 9/2026/09"))
    assertThat(listed.indexOf("Backup/Pixel 9/2016")).isLessThan(listed.indexOf("Backup/Pixel 9/2015"))
  }

  @Test
  fun seLeDateNonDiconoNienteSiVaPerNomeConAnniEMesiInOrdineNumerico() = runTest {
    // Un ripristino da backup: tutte le cartelle con la stessa data di modifica. L'ordine lo danno i nomi.
    h = ScanHarness(writeMetadata = false)
    h.phoneNow = october2026 - h.nasOffset
    val oneDay = 86_400_000L
    for (year in listOf(2009, 2010, 2026, 2011)) {
      for (month in listOf("01", "09", "10", "02")) h.photo("Backup/Pixel 9/$year/$month/a.jpg", ageMs = oneDay, seed = (year * 100L + month.toInt()))
    }
    h.scan()
    val listed = h.reader.listed.filter { it.count { c -> c == '/' } == 3 }.map { it.substringAfter("Pixel 9/") }
    assertThat(listed.take(4)).containsExactly("2026/10", "2026/09", "2026/02", "2026/01").inOrder()
    assertThat(listed.drop(4).take(4)).containsExactly("2011/10", "2011/09", "2011/02", "2011/01").inOrder()
    assertThat(h.reader.listed.filter { it.count { c -> c == '/' } == 2 }.map { it.substringAfter("Pixel 9/") })
      .containsExactly("2026", "2011", "2010", "2009").inOrder()
  }

  // --- La ripresa non perde le cartelle che cambiano fra una tappa e l'altra -----------------------------------------------------------

  /** Quattro cartelle di eta' diverse (A la piu' recente): l'ordine di visita e' A, B, C, D. */
  private fun fourFolders() {
    h = ScanHarness(config = ScanConfig(cursorSaveEvery = 1), latency = { if (it == Op.LIST) 10L else 0L }, writeMetadata = false)
    h.phoneNow = october2026 - h.nasOffset
    h.photo("A/a1.jpg", ageMs = 1 * DAY)
    h.photo("B/b1.jpg", ageMs = 2 * DAY)
    h.photo("C/c1.jpg", ageMs = 3 * DAY)
    h.photo("D/d1.jpg", ageMs = 4 * DAY)
  }

  /** Un giro intero: i quattro file sono noti. Poi passa un'ora. */
  private suspend fun aFirstRoundThenAnHour() {
    assertThat(h.scan(ScanMode.Full).complete).isTrue()
    h.advance(3_600_000)
  }

  /**
   * Una tappa che fa la radice, A e B e si ferma nel mezzo di C (che e' elencata ma non fatta): la ripresa e' dopo B.
   * Restituisce quanti elenchi ha fatto in tutto.
   */
  private suspend fun tickStopsAfterB(): Int {
    val before = h.reader.listed.size
    val tick = h.scan(ScanMode.Full, budgetMs = 40)
    assertThat(tick.stoppedBy).isEqualTo(ScanStop.BUDGET)
    assertThat(h.reader.listed.drop(before)).containsExactly("", "A", "B", "C").inOrder()
    return h.reader.listed.size
  }

  /** Fra una tappa e l'altra il PC lavora: D riceve una foto (la sua data sale in cima a tutte), compare E. Foto vecchie di 3 minuti. */
  private fun pcWorksBetweenTicks() {
    h.advance(600_000)
    h.photo("D/d2.jpg", ageMs = 180_000)
    h.photo("E/e1.jpg", ageMs = 180_000)
  }

  @Test
  fun nelloStessoProcessoLaRipresaUsaLOrdineDiQuandoIlGiroECominciato() = runTest {
    fourFolders()
    val listed = tickStopsAfterB()
    assertThat(h.store.knownCount(h.me, "test")).isEqualTo(2)
    pcWorksBetweenTicks()
    val second = h.scan(ScanMode.Full)
    assertThat(second.resumed).isTrue()
    assertThat(second.complete).isTrue()
    // A e B non si rifanno (solo la catena: la radice e B), C, D ed E si visitano tutte, anche se D ora e' in testa per data.
    assertThat(h.reader.listed.drop(listed)).containsExactly("", "B", "C", "D", "E").inOrder()
    assertThat(h.store.knownCount(h.me, "test")).isEqualTo(6)
    assertThat(h.reader.opens).isEqualTo(6)
    assertThat(second.vanished).isEqualTo(0)
  }

  @Test
  fun inUnAltroProcessoLOrdineSiRicostruisceEUnaCartellaToccataDopoSiVisitaComunque() = runTest {
    fourFolders()
    val listed = tickStopsAfterB()
    pcWorksBetweenTicks()
    h.restartScanner()
    val second = h.scan(ScanMode.Full)
    assertThat(second.resumed).isTrue()
    assertThat(second.complete).isTrue() // nessun file noto non rivisto: il giro vale
    // D ed E sono state toccate dopo l'inizio del giro: davanti a B nell'ordine di adesso, ma si visitano. A, intatta, no.
    val after = h.reader.listed.drop(listed)
    assertThat(after).doesNotContain("A")
    assertThat(after).containsAtLeast("B", "C", "D", "E")
    assertThat(h.store.knownCount(h.me, "test")).isEqualTo(6)
    assertThat(h.reader.opens).isEqualTo(6)
  }

  @Test
  fun inUnAltroProcessoUnFileNonRivistoNonDiventaAssenteSubitoMaAlGiroDopo() = runTest {
    fourFolders()
    aFirstRoundThenAnHour()
    tickStopsAfterB()
    // D, ancora da fare in questo giro, perde il suo file mentre il processo e' morto.
    h.synthetic.deleteFromPc("test", "D/d1.jpg")
    h.restartScanner()
    val second = h.scan(ScanMode.Full)
    // Ordine ricostruito: non si puo' escludere che una cartella sia stata saltata, quindi nessuna assenza segnata.
    assertThat(second.resumed).isTrue()
    assertThat(second.complete).isFalse()
    assertThat(second.vanished).isEqualTo(0)
    assertThat(h.store.stateOf(h.keyOf("D/d1.jpg"))).isEqualTo(LocationState.PRESENT)
    // Un giro intero dopo, in un processo solo, decide.
    val third = h.scan(ScanMode.Verify)
    assertThat(third.complete).isTrue()
    assertThat(third.vanished).isEqualTo(1)
    assertThat(h.store.stateOf(h.keyOf("D/d1.jpg"))).isEqualTo(LocationState.ABSENT)
  }

  @Test
  fun nelloStessoProcessoLaStessaSituazioneSegnaLAssenzaSubito() = runTest {
    fourFolders()
    aFirstRoundThenAnHour()
    tickStopsAfterB()
    h.synthetic.deleteFromPc("test", "D/d1.jpg")
    val second = h.scan(ScanMode.Full)
    assertThat(second.resumed).isTrue()
    assertThat(second.complete).isTrue()
    assertThat(second.vanished).isEqualTo(1)
  }

  @Test
  fun laPrimaScansioneInPiuTappeEPiuProcessiSiChiudeDavvero() = runTest {
    // Nessun file era noto prima del giro: non c'e' nessuna assenza da escludere, e il giro vale anche dopo un riavvio.
    fourFolders()
    tickStopsAfterB()
    h.restartScanner()
    val second = h.scan(ScanMode.Full)
    assertThat(second.complete).isTrue()
    assertThat(h.store.cursor(h.me, "test")!!.lastFullScanAt).isNotNull()
    assertThat(h.store.knownCount(h.me, "test")).isEqualTo(4)
    assertThat(h.reader.opens).isEqualTo(4)
  }
}
