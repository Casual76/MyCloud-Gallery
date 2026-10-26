package dev.pampa.mycloudgallery.debugtools.eval.albums

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.debugtools.eval.albums.AlbumsTestRig.Companion.day
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Lo strumento di etichettatura e di corsa (P12, solo debug), senza interfaccia: il metodo "a pozzo" (§5.3) per un viaggio -- alla cieca, la corsa in memoria, i
 * giudizi, i mancanti --, le etichette salvate a ogni modifica, i numeri di F88 e il confronto di F89. Libreria sintetica (Oslo, 2025): nessuna foto vera.
 */
class AlbumsEvalSessionTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun store() = AlbumsEvalStore(tmp.newFolder("albums"))

  private fun session(rig: AlbumsTestRig, store: AlbumsEvalStore = store()) = AlbumsEvalSession(rig, store).also { it.load() }

  private val osloFrom = day(3, 14)
  private val osloTo = day(3, 16)

  @Test
  fun senzaFotoSegnateAllaCiecaLaCorsaNonParte() = runTest {
    val s = session(AlbumsTestRig.osloTrip())
    val trip = s.addTrip("Oslo marzo 2025", osloFrom, osloTo)
    val error = assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { s.runTrip(trip.id) } }
    assertThat(error.message).isEqualTo(AlbumsEvalStrings.BLIND_FIRST)
    // E senza titolo non si crea nessun album.
    val untitled = s.addTrip(null, day(5, 1), day(5, 3))
    s.setInTrip(untitled.id, listOf("7a1c0000-0000-4000-8000-000000000001"), true)
    assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { s.runTrip(untitled.id) } }
    assertThat(s.runOf(trip.id)).isNull()
  }

  @Test
  fun ilMetodoAPozzoDiUnViaggio() = runTest {
    val rig = AlbumsTestRig.osloTrip()
    val s = session(rig)
    val trip = s.addTrip("Oslo marzo 2025", osloFrom, osloTo)

    // 1. Alla cieca: le celle dei giorni del viaggio nella Libreria (14 = 12 di Oslo, lo screenshot e il familiare), non quelle di casa.
    val blind = s.blindCells(trip.id)
    assertThat(blind.map { it.id }).containsExactlyElementsIn((1..12).map { rig.id(it).value } + listOf(rig.id(100).value, rig.id(101).value))
    assertThat(blind.map { it.day }.distinct()).containsExactly(day(3, 14), day(3, 15), day(3, 16)).inOrder()
    // L'utente segna le dodici foto sue.
    s.setInTrip(trip.id, (1..12).map { rig.id(it).value }, true)

    // 2. La corsa: l'album sta in memoria. I dodici sono sicuri; lo screenshot e il familiare, suggeriti.
    val run = s.runTrip(trip.id)
    assertThat(run.sure).containsExactlyElementsIn((1..12).map { rig.id(it).value })
    assertThat(run.suggested).containsExactly(rig.id(100).value, rig.id(101).value)
    // "Mostra tutti": l'elenco intero dei sicuri e dei suggeriti, anche di cio' che l'utente ha gia' segnato.
    assertThat(s.listed(trip.id, suggested = false)).hasSize(12)
    assertThat(s.listed(trip.id, suggested = true)).hasSize(2)
    assertThat(s.listed("v09", suggested = false)).isEmpty()
    // Su cio' che l'utente ha segnato non c'e' niente da giudicare fra i sicuri; i due suggeriti aspettano.
    assertThat(s.pending(trip.id).sure).isEmpty()
    assertThat(s.pending(trip.id).suggested).containsExactly(rig.id(100).value, rig.id(101).value)
    var m = s.metrics(trip.id)!!
    assertThat(m.sureRight).isEqualTo(12)
    assertThat(m.sureUnjudged).isEqualTo(0)
    assertThat(m.truth).isEqualTo(12)
    assertThat(m.coverage).isEqualTo(1.0)

    // Il familiare e' del viaggio, lo screenshot no.
    s.setVerdict(trip.id, rig.id(101).value, Verdict.RIGHT)
    s.setVerdict(trip.id, rig.id(100).value, Verdict.WRONG)
    assertThat(s.verdictOf(trip.id, rig.id(101).value)).isEqualTo(Verdict.RIGHT)
    assertThat(s.verdictOf(trip.id, rig.id(100).value)).isEqualTo(Verdict.WRONG)
    assertThat(s.pending(trip.id).isEmpty).isTrue()
    m = s.metrics(trip.id)!!
    assertThat(m.truth).isEqualTo(13)
    assertThat(m.covered).isEqualTo(13)
    assertThat(m.suggestedRight).isEqualTo(1)

    // 3. I mancanti: le foto dei giorni attorno che l'album non ha preso.
    rig.put(300, day(3, 12), 18 * 60) // due giorni prima del periodo dell'album (13..17), senza GPS: l'album non la prende
    rig.put(301, day(3, 19), 18 * 60) // due giorni dopo
    rig.publish()
    val missing = s.missingCells(trip.id)
    assertThat(missing.map { it.id }).containsExactly(rig.id(300).value, rig.id(301).value)
    s.setInTrip(trip.id, listOf(rig.id(300).value), true)
    m = s.metrics(trip.id)!!
    assertThat(m.truth).isEqualTo(14)
    assertThat(m.missing).isEqualTo(1)
    assertThat(m.coverage).isWithin(1e-12).of(13.0 / 14)
    assertThat(m.precision).isEqualTo(1.0)
  }

  /** Il motore puo' prendere una foto oltre i giorni scritti dall'utente: e' un sicuro da giudicare, non un "mancante" (i mancanti sono cio' che l'album NON ha preso). */
  @Test
  fun unaFotoPresaOltreIGiorniDelViaggioNonEFraIMancanti() = runTest {
    val rig = AlbumsTestRig.osloTrip()
    // Il 17 marzo, a Oslo: l'utente ha scritto 14-16 marzo, ma l'album la prende (il periodo si allarga alla serie di giorni con foto nel luogo).
    val beyond = rig.osloPhotos(302, day(3, 17), count = 1).single()
    val later = rig.put(303, day(3, 19), 18 * 60)
    rig.publish()
    val s = session(rig)
    val trip = s.addTrip("Oslo marzo 2025", osloFrom, osloTo)
    s.setInTrip(trip.id, (1..12).map { rig.id(it).value }, true)
    val run = s.runTrip(trip.id)
    assertThat(run.sure).contains(beyond.value)
    assertThat(s.pending(trip.id).sure).containsExactly(beyond.value)
    val missing = s.missingCells(trip.id).map { it.id }
    assertThat(missing).doesNotContain(beyond.value)
    assertThat(missing).contains(later.value)
  }

  @Test
  fun unSicuroNonSegnatoAspettaIlGiudizio() = runTest {
    val rig = AlbumsTestRig.osloTrip()
    val s = session(rig)
    val trip = s.addTrip("Oslo marzo 2025", osloFrom, osloTo)
    // L'utente ha segnato solo i primi otto: i quattro del 16 marzo l'album li mette fra i sicuri e lui non e' d'accordo ne' in disaccordo.
    s.setInTrip(trip.id, (1..8).map { rig.id(it).value }, true)
    s.runTrip(trip.id)
    assertThat(s.pending(trip.id).sure).containsExactlyElementsIn((9..12).map { rig.id(it).value })
    var m = s.metrics(trip.id)!!
    assertThat(m.sureUnjudged).isEqualTo(4)
    assertThat(m.precision).isWithin(1e-12).of(8.0 / 12)
    // Dice che due sono del viaggio e due no.
    s.setVerdict(trip.id, rig.id(9).value, Verdict.RIGHT)
    s.setVerdict(trip.id, rig.id(10).value, Verdict.RIGHT)
    s.setVerdict(trip.id, rig.id(11).value, Verdict.WRONG)
    s.setNotInTrip(trip.id, listOf(rig.id(12).value))
    m = s.metrics(trip.id)!!
    assertThat(m.sureUnjudged).isEqualTo(0)
    assertThat(m.sureWrong).isEqualTo(2)
    assertThat(m.precision).isWithin(1e-12).of(10.0 / 12)
    // Togliere un giudizio lo riporta a "da giudicare".
    s.setVerdict(trip.id, rig.id(11).value, Verdict.NONE)
    assertThat(s.pending(trip.id).sure).containsExactly(rig.id(11).value)
  }

  @Test
  fun leEtichetteSopravvivonoAlRiavvioESiSalvanoOgniVolta() = runTest {
    val rig = AlbumsTestRig.osloTrip()
    val folder = store()
    val first = session(rig, folder)
    val trip = first.addTrip("Oslo marzo 2025", osloFrom, osloTo)
    first.setInTrip(trip.id, listOf(rig.id(1).value, rig.id(2).value), true)
    first.setNotInTrip(trip.id, listOf(rig.id(3).value))
    // Un'altra sessione (il processo e' morto e l'app riparte) ritrova tutto.
    val second = session(rig, folder)
    assertThat(second.loadError).isNull()
    assertThat(second.labels.value.trips.map { it.id }).containsExactly(trip.id)
    assertThat(second.labels.value.trip(trip.id)!!.title).isEqualTo("Oslo marzo 2025")
    assertThat(second.labels.value.inTripOf(trip.id)).containsExactly(rig.id(1).value, rig.id(2).value)
    assertThat(second.labels.value.notInTripOf(trip.id)).containsExactly(rig.id(3).value)
    // Nessun file temporaneo rimasto, e il file ha l'intestazione.
    val dir = File(tmp.root, "albums")
    assertThat(dir.listFiles().orEmpty().map { it.name }).containsExactly(AlbumsLabelFormat.FILE_NAME)
    assertThat(File(dir, AlbumsLabelFormat.FILE_NAME).readText()).startsWith(AlbumsLabelFormat.MAGIC)
  }

  @Test
  fun unFileRovinatoSiDiceEPartiDaCapo() {
    val folder = store()
    File(tmp.root, "albums/${AlbumsLabelFormat.FILE_NAME}").writeText("${AlbumsLabelFormat.MAGIC}\nX\tcosa privata\n")
    val s = session(AlbumsTestRig.osloTrip(), folder)
    assertThat(s.loadError).contains("Riga 2")
    assertThat(s.loadError).doesNotContain("privata")
    assertThat(s.labels.value.trips).isEmpty()
  }

  @Test
  fun unNascostoNonSiVedeInNessunaFase() = runTest {
    val rig = AlbumsTestRig.osloTrip()
    val s = session(rig)
    val trip = s.addTrip("Oslo marzo 2025", osloFrom, osloTo)
    rig.hidden += listOf(rig.id(3), rig.id(100), rig.id(300))
    rig.put(300, day(3, 12), 18 * 60)
    rig.publish()
    assertThat(s.blindCells(trip.id).map { it.id }).containsNoneOf(rig.id(3).value, rig.id(100).value)
    s.setInTrip(trip.id, listOf(rig.id(1).value), true)
    val run = s.runTrip(trip.id)
    assertThat(run.sure).doesNotContain(rig.id(3).value)
    assertThat(run.suggested).doesNotContain(rig.id(100).value)
    assertThat(s.cellsOf(listOf(rig.id(3).value, rig.id(1).value)).map { it.id }).containsExactly(rig.id(1).value)
    assertThat(s.missingCells(trip.id).map { it.id }).doesNotContain(rig.id(300).value)
  }

  @Test
  fun iNumeriDiF88SonoSoloNumeri() = runTest {
    val rig = AlbumsTestRig.osloTrip()
    val folder = store()
    val s = session(rig, folder)
    assertThat(s.saveF88Report()).isNull()
    val trip = s.addTrip("Oslo marzo 2025", osloFrom, osloTo)
    s.addTrip(null, day(8, 1), day(8, 9))
    s.setInTrip(trip.id, (1..12).map { rig.id(it).value }, true)
    s.runTrip(trip.id)
    val (name, text) = s.saveF88Report()!!
    println(text)
    assertThat(name).isEqualTo("report-01.txt")
    assertThat(folder.readReport(name)).isEqualTo(text)
    assertThat(text).contains("F88, album smart su 1 viaggi")
    assertThat(text).contains("v01 | 3 | 12 | 12 | 12 | 0 | 0 | 2 | 0 | 12 | 0 | 100.0% | 100.0% | 1 | si | 0")
    for (secret in listOf("Oslo", "2025", rig.id(1).value, osloFrom.toString())) assertThat(text).doesNotContain(secret)
    // Un secondo resoconto e' un altro file.
    assertThat(s.saveF88Report()!!.first).isEqualTo("report-02.txt")
    assertThat(folder.reports()).containsExactly("report-01.txt", "report-02.txt").inOrder()
  }

  @Test
  fun ilConfrontoConLeProposteChiudeLElencoESaleSoloSeLeProposteCiSono() = runTest {
    val rig = AlbumsTestRig.osloTrip()
    val s = session(rig)
    s.addTrip("Oslo marzo 2025", osloFrom, osloTo)
    s.addTrip(null, day(8, 1), day(8, 9))
    // Senza i viaggi collegati: niente da confrontare e l'elenco resta aperto.
    rig.proposals = TripsView.UNAVAILABLE
    assertThat(s.compareTrips()).isNull()
    assertThat(s.labels.value.listClosed).isFalse()

    // Con le proposte: Oslo c'e' (un giorno in piu' per parte), agosto no.
    rig.proposals = TripsView(listOf(ProposedDays(day(3, 13), day(3, 17), isTrip = true), ProposedDays(day(5, 5), day(5, 6), isTrip = false)), homeKnown = true)
    val (found, name, text) = s.compareTrips()!!
    println(text)
    assertThat(found.found).isEqualTo(1)
    assertThat(found.missing).isEqualTo(1)
    assertThat(found.meets).isFalse()
    assertThat(found.eventProposals).isEqualTo(1)
    assertThat(name).isEqualTo("report-01.txt")
    assertThat(text).contains("trovati: 1")
    assertThat(text).doesNotContain("2025")
    // Dopo aver visto le proposte l'elenco non si cambia piu'.
    assertThat(s.labels.value.listClosed).isTrue()
    assertThrows(IllegalArgumentException::class.java) { s.addTrip(null, day(9, 1), day(9, 3)) }
    assertThrows(IllegalArgumentException::class.java) { s.removeTrip("v02") }
    // Ma la chiusura sopravvive al riavvio.
    val again = AlbumsEvalSession(rig, AlbumsEvalStore(File(tmp.root, "albums"))).also { it.load() }
    assertThat(again.labels.value.listClosed).isTrue()
  }

  @Test
  fun unElencoVuotoNonSiConfronta() = runTest {
    val s = session(AlbumsTestRig.osloTrip())
    val error = assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { s.compareTrips() } }
    assertThat(error.message).isEqualTo(AlbumsEvalStrings.LIST_FIRST)
  }

  @Test
  fun iGiorniDeiViaggiDevonoEssereValidi() {
    val s = session(AlbumsTestRig.osloTrip())
    assertThrows(IllegalArgumentException::class.java) { s.addTrip("x", LocalDate.of(2025, 3, 16), LocalDate.of(2025, 3, 14)) }
    assertThat(s.labels.value.trips).isEmpty()
    val trip = s.addTrip("  Oslo  ", osloFrom, osloTo)
    assertThat(s.labels.value.trip(trip.id)!!.title).isEqualTo("Oslo")
    s.setTitle(trip.id, null)
    assertThat(s.labels.value.trip(trip.id)!!.title).isNull()
  }

  @Test
  fun cancellareLeEtichetteTogliLeEtichetteEIResocontiMaNonIlFileDiProva() {
    val albums = tmp.newFolder("albums2")
    val folder = AlbumsEvalStore(albums)
    val s = AlbumsEvalSession(AlbumsTestRig.osloTrip(), folder).also { it.load() }
    s.addTrip("Oslo", osloFrom, osloTo)
    folder.saveReport("numeri")
    File(albums, "import").mkdirs()
    File(albums, "import/${AlbumsEvalStore.IMPORT_FILE}").writeText("#MCGSINT1\n")
    File(albums, "prova").mkdirs()
    File(albums, "prova/${AlbumsLabelFormat.FILE_NAME}").writeText("x")
    folder.wipe()
    assertThat(File(albums, AlbumsLabelFormat.FILE_NAME).exists()).isFalse()
    assertThat(folder.reports()).isEmpty()
    assertThat(File(albums, "import/${AlbumsEvalStore.IMPORT_FILE}").exists()).isTrue()
    assertThat(File(albums, "prova/${AlbumsLabelFormat.FILE_NAME}").exists()).isTrue()
    s.load()
    assertThat(s.labels.value.trips).isEmpty()
  }
}
