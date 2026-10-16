package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.similar.ChoiceOutcome
import dev.pampa.mycloudgallery.core.library.similar.CopyPlace
import dev.pampa.mycloudgallery.core.library.similar.Raster
import dev.pampa.mycloudgallery.core.library.similar.SignaturePixels
import dev.pampa.mycloudgallery.core.library.similar.SignatureSource
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.prepare.contract.PrepControl
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Le copie simili con il grafo vero (Fase 5A, P7; docs/fase5-progetto.md §2.12, D36): la foto ricompressa da un'app di messaggi (che sta
 * nell'area comune del NAS) e l'originale del telefono sono **una cella sola**; "Separa" su un dispositivo arriva all'altro dispositivo
 * dello stesso account (dato personale sincronizzato), che le rimette in due celle. Le copie fuori dall'indice (quella che non e' la
 * copertina) si ritrovano dal catalogo per il viewer.
 *
 * Il rilevatore e' quello vero (`CatalogSimilarCopies`) sul catalogo vero; l'unica parte finta e' da dove vengono i **pixel** da firmare
 * (il MediaStore finto non apre `content://`): le due copie danno lo stesso disegno, come darebbero due JPEG dello stesso scatto.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class SimilarCopiesIntegrationTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val devices = mutableListOf<TestDevice>()
  private val rigs = mutableListOf<BackupRig>()

  @After
  fun tearDown() {
    rigs.forEach { runBlocking { it.gallery.shutdown() } }
    devices.forEach { it.close() }
  }

  /** Un disegno con abbastanza struttura per essere firmato (blocchi di colori diversi), uguale per ogni copia. */
  private fun sameScene(): SignatureSource {
    val pixels = IntArray(64 * 64) { i ->
      val bx = (i % 64) / 8
      val by = (i / 64) / 8
      ((bx * 37 + by * 91) % 256 shl 16) or ((bx * 53 + by * 17) % 256 shl 8) or ((bx * 11 + by * 71 + 40) % 256)
    }
    return SignatureSource { SignaturePixels.Ready(listOf(Raster.of(64, 64, pixels))) }
  }

  private fun rig(device: TestDevice, backend: InMemorySmbBackend): BackupRig =
    BackupRig(app, device, TestNas.ADMIN_USER, signatures = { sameScene() }).also { rigs += it }

  /** Per capire un fallimento: i media del catalogo con la sorgente dell'ora di scatto (solo numeri e codici). */
  private fun BackupRig.dump(): String {
    val db = gallery.database.openHelper.readableDatabase
    return db.query(
      "SELECT m.kind, m.presence, g.kind, (SELECT substr(v.quick_hash, 1, 6) || '/' || COALESCE(substr(v.full_hash, 1, 6), '-') FROM media_version v WHERE v.version_id = m.current_version_id), " +
        "md.taken_source, m.group_id IS NOT NULL, " +
        "(SELECT COUNT(*) FROM media_location l JOIN media_version v ON v.version_id = l.version_id WHERE v.media_id = m.media_id AND l.location_type = 'phone'), " +
        "(SELECT COUNT(*) FROM media_location l JOIN media_version v ON v.version_id = l.version_id WHERE v.media_id = m.media_id AND l.location_type = 'nas') " +
        "FROM media m LEFT JOIN version_metadata md ON md.version_id = m.current_version_id LEFT JOIN media_group g ON g.group_id = m.group_id",
    ).use { c ->
      buildList { while (c.moveToNext()) add((0 until c.columnCount).joinToString(",") { c.getString(it) ?: "-" }) }
    }.toString()
  }

  private fun BackupRig.mediaCount(): Int =
    gallery.database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM media").use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }

  /** Quanti media sono in un gruppo di copie simili. */
  private fun BackupRig.groupedCount(): Int =
    gallery.database.openHelper.readableDatabase.query(
      "SELECT COUNT(*) FROM media m JOIN media_group g ON g.group_id = m.group_id WHERE g.kind = 'similar_copies'",
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }

  private fun BackupRig.allIds(): List<String> {
    val db = gallery.database.openHelper.readableDatabase
    return db.query("SELECT media_id FROM media ORDER BY media_id").use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
  }

  private suspend fun BackupRig.ids(count: Int): List<MediaId> {
    val view = withTimeout(60.seconds) { gallery.repository.view(LibraryFilter.ALL).first { it.size == count } }
    return (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }
  }

  @Test
  fun laCopiaRicompressataEL_originaleStannoInUnaCellaESeparaArrivaAllAltroDispositivo() = runBlocking<Unit> {
    val backend = TestNas.backend(time)
    val phone = TestDevice(app, File(temp.root, "telefono"), TestPlatform(time, backend)).also { devices += it }
    phone.graph.start()
    phone.graph.foundFamily()
    val tablet = TestDevice(app, File(temp.root, "tablet"), TestPlatform(time, backend)).also { devices += it }
    enrolSecondDevice(phone, tablet)

    // L'originale (qualita' alta) e' sul telefono; la stessa immagine ricompressa (byte diversi) sta nell'area comune del NAS.
    val takenAt = time.now() - 5 * HOUR
    // Le due copie dicono lo stesso istante di scatto nell'EXIF (08:00 del NAS meno cinque ore = 03:00:00 UTC): senza, non sono candidate.
    val shot = "2026:10:12 03:00:00"
    val original = BackupRig.withExif(BackupRig.jpeg(tone = 60, quality = 95), shot)
    val recompressed = BackupRig.withExif(BackupRig.jpeg(tone = 60, quality = 35), shot)
    assertThat(recompressed).isNotEqualTo(original)
    backend.put(TestNas.COMMON, "Foto/IMG_1_inviata.jpg", recompressed, lastWriteMs = takenAt)

    val a = rig(phone, backend)
    BackupRig.photo(a.store, id = 1, bytes = original, takenAt = takenAt)
    a.open()
    // L'originale lo salva il backup (area privata, copia verificata): cosi' telefono e NAS sono uno stesso media, e l'altro dispositivo
    // dello stesso account lo trova nella sua area privata.
    a.chooseFolder("Pixel di prova")
    val round = checkNotNull(a.round()) { "il giro non e' partito" }
    assertWithMessage("giro: ${round.last}").that(round.last?.done).isEqualTo(1)
    checkNotNull(a.safety())
    time.advance(3 * MINUTE)
    checkNotNull(a.nas.scan(ScanMode.Full, 5.minutes))
    assertWithMessage("catalogo: ${a.dump()}").that(a.mediaCount()).isEqualTo(2)
    val merged = a.ids(count = 2)
    // Il telefono pubblica l'indice sul NAS (D31: la share di prova lo consente): il tablet ne adotta le identita' (D7) se sono minori delle sue.
    phone.graph.trustGuard.preflight(Purpose.BACKGROUND)
    checkNotNull(a.nas.prepare(5.minutes, PrepControl.ALWAYS))
    a.nas.flushOutbox()

    // Il tablet non ha l'originale sul telefono: lo conosce solo dal NAS (la copia nell'area privata e quella ricompressa nell'area comune).
    val b = rig(tablet, backend)
    b.open(adoptIndex = true)
    assertWithMessage("catalogo del tablet: ${b.dump()}").that(b.mediaCount()).isEqualTo(2)

    // Prima del confronto sono due celle: l'originale (telefono e NAS) e la copia ricompressa del NAS.
    val before = merged
    val detector = checkNotNull(a.gallery.similarCopies)
    // Il confronto gira anche da solo all'avvio e a ogni cambiamento del catalogo: qui basta che al termine di un giro sia fatto.
    val report = detector.run(60.seconds)
    assertWithMessage("giro del confronto: $report; catalogo: ${a.dump()}").that(a.groupedCount()).isEqualTo(2)

    // Dopo, una cella sola: la copertina, e il gruppo ha le due copie (una sul telefono, una nell'area comune).
    val after = a.ids(count = 1)
    val cover = after.single()
    val group = checkNotNull(detector.groupOf(cover))
    assertThat(group.copies).hasSize(2)
    assertThat(group.copies.flatMap { it.places }.toSet()).containsAtLeast(CopyPlace.PHONE, CopyPlace.NAS_COMMON)
    assertThat(group.cover).isEqualTo(cover)
    assertThat(before).contains(cover)

    // Il viewer: l'altra copia non e' nell'indice ma si ritrova dal catalogo, con la sua posizione (telefono o NAS), per mostrarla a parte.
    val other = group.copies.map { it.mediaId }.single { it != cover }
    assertThat(a.gallery.repository.view(LibraryFilter.ALL).first().viewIndexOf(other)).isLessThan(0)
    val found = checkNotNull(a.gallery.viewerServices.outsideCopy).find(other)
    assertThat(found).isNotNull()

    // Il tablet fa lo stesso confronto: le stesse due copie, una cella.
    val tabletDetector = checkNotNull(b.gallery.similarCopies)
    val tabletReport = tabletDetector.run(60.seconds)
    assertWithMessage("tablet: $tabletReport; catalogo: ${b.dump()}").that(b.groupedCount()).isEqualTo(2)
    val tabletCover = b.ids(count = 1).single()
    assertThat(checkNotNull(tabletDetector.groupOf(tabletCover)).copies).hasSize(2)

    // "Separa" sul telefono: l'altra copia esce dal gruppo per sempre (due celle) e la scelta viaggia come dato personale.
    assertThat(detector.separate(other)).isEqualTo(ChoiceOutcome.APPLIED)
    assertThat(detector.groupOf(cover)).isNull()
    a.ids(count = 2)
    phone.graph.trustGuard.preflight(Purpose.BACKGROUND)
    val published = checkNotNull(a.nas.organizer.syncPersonal(1.minutes))
    assertWithMessage("telefono: $published").that(published.published).isAtLeast(1)

    // Il tablet la riceve: la voce e' valida e entra nel suo stato (nessuna scartata).
    tablet.graph.trustGuard.preflight(Purpose.BACKGROUND)
    val applied = checkNotNull(b.nas.organizer.syncPersonal(1.minutes))
    assertWithMessage("tablet: $applied").that(applied.applied).isAtLeast(1)
    assertThat(applied.rejected).isEmpty()

    // La scelta nomina i due media con le identita' del telefono (`similar_apart:<id>`). Il tablet la porta al catalogo se conosce entrambe:
    // le identita' dei file del NAS convergono al minore (D7) e un media con la copia sul telefono tiene la sua (D8), quindi dopo la prima
    // lettura dell'indice possono coincidere o no. Se coincidono le sue due copie tornano due celle, subito e per sempre; se no la voce
    // resta in attesa (`unresolved`) e le copie restano unite finche' il tablet non conosce l'identita' (limite dichiarato in decisioni.md).
    val catalog = b.gallery.core().catalog
    val tabletKnowsBoth = listOf(cover, other).all { catalog.resolve(it) != null }
    val rerun = tabletDetector.run(60.seconds)
    if (tabletKnowsBoth) {
      val twoCells = withTimeoutOrNull(30.seconds) { b.gallery.repository.view(LibraryFilter.ALL).first { it.size == 2 } }
      assertWithMessage("tablet dopo Separa: giro=$rerun; telefono: copertina=$cover altra=$other; tablet: ${b.allIds()}").that(twoCells).isNotNull()
      assertThat(b.groupedCount()).isEqualTo(0)
      // Il confronto non le riunisce piu': separare e' per sempre.
      tabletDetector.run(60.seconds)
      assertThat(b.groupedCount()).isEqualTo(0)
      b.ids(count = 2)
    } else {
      assertWithMessage("tablet: $applied").that(applied.unresolved).isAtLeast(1)
      assertThat(b.groupedCount()).isEqualTo(2)
    }
    assertThat(backend.fileCount(TestNas.COMMON)).isAtLeast(1)
  }

  private companion object {
    const val HOUR = 3_600_000L
    const val MINUTE = 60_000L
  }
}
