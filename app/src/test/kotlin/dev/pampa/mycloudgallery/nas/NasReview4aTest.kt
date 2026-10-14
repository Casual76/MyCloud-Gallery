package dev.pampa.mycloudgallery.nas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.mediastore.contract.PendingCopy
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.domain.Observation
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.session.ClockJumpGuard
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.ZoneOffset
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le correzioni della revisione di sicurezza della Fase 4A (`docs/review/4a-fase4a.md`) che stanno in `:app`:
 * R7 (l'ora del NAS salvata fra un processo e l'altro), R8 ("Scarica" con timbro e impronta), R10 (area persa).
 */
@RunWith(AndroidJUnit4::class)
class NasReview4aTest {

  private val me = AccountId("test")
  private val now = 1_800_000_000_000L
  private var db: GalleryDatabase? = null

  @After
  fun close() {
    db?.close()
  }

  private fun catalog(): MediaCatalog {
    val database = GalleryDatabase.build(ApplicationProvider.getApplicationContext<Context>(), name = null).also { db = it }
    return MediaCatalog(database, me, zone = { ZoneOffset.UTC }) { now }
  }

  private fun nasObs(share: String, path: String, content: ByteArray, withFullHash: Boolean = false): Observation {
    val fp = Fingerprint.ofContent(content).let { if (withFullHash) it else it.copy(fullHash = null) }
    return Observation(Location.Nas(me, share, NasPath.of(path)), FileStamp(content.size.toLong(), now - 86_400_000L), fp, MediaKind.PHOTO, MediaSource.OTHER)
  }

  // --- R7 ------------------------------------------------------------------------------------------------------------

  @Test
  fun r7_ilSaltoDellOraSiSalvaSuFileESopravviveAUnProcessoNuovo() {
    val dir = Files.createTempDirectory("nas-clock").toFile()
    try {
      val file = File(File(dir, "replica"), NasGraph.CLOCK_FILE)
      val first = ClockJumpGuard(FileClockJumpStore(file))
      first.onAnchor(now, ClockJumpGuard.Reference(1_000, 4, now - 55_000))
      // Un'ora dopo, stesso avvio del telefono, il NAS e' tornato indietro di 20 minuti (l'NTP sistemato).
      first.onAnchor(now + 40 * 60_000, ClockJumpGuard.Reference(1_000 + 60 * 60_000, 4, now - 55_000 + 60 * 60_000))
      assertThat(first.blocked).isTrue()

      // L'app riparte: la sospensione e' ancora li', con il salto per la Diagnostica.
      val reborn = ClockJumpGuard(FileClockJumpStore(file))
      assertThat(reborn.blocked).isTrue()
      assertThat(reborn.jump.value).isEqualTo(-20 * 60_000L)
      assertThat(reborn.accept()).isTrue()
      assertThat(ClockJumpGuard(FileClockJumpStore(file)).blocked).isFalse()

      // Un file rovinato vale "nessuna ancora", mai un'eccezione.
      file.writeText("nas=abc\nelapsed=")
      assertThat(FileClockJumpStore(file).load()).isNull()
    } finally {
      dir.deleteRecursively()
    }
  }

  // --- R8 ------------------------------------------------------------------------------------------------------------

  private fun nasWith(content: ByteArray, mtime: Long): Pair<InMemoryNasMedia, Long> {
    val nas = InMemoryNasMedia(nasNow = { now })
    nas.addShare("test")
    val id = nas.putFile("test", "Foto/a.jpg", NasContent.of(content), mtime)
    return nas to id
  }

  private suspend fun download(reader: CatalogCheckedReader): NasResult<Sha256> {
    val file = when (val opened = reader.open("test", SharePath.parse("Foto/a.jpg"), Purpose.INTERACTIVE)) {
      is NasResult.Err -> return NasResult.Err(opened.error)
      is NasResult.Ok -> opened.value
    }
    return try {
      val out = ByteArrayOutputStream()
      reader.readAll(file, { buffer, length -> out.write(buffer, 0, length) })
    } finally {
      file.close()
    }
  }

  @Test
  fun r8_unFileCambiatoDopoLUltimaVerificaNonSiScarica() = runBlocking<Unit> {
    val content = Random(1).nextBytes(300_000)
    val (nas, id) = nasWith(content, now - 1_000)
    val catalogStamp = NasStamp(content.size.toLong(), now - 1_000, id)
    val quick = QuickHashAlgorithm.ofContent(content)

    // Il timbro e' quello del catalogo e i byte danno la sua impronta: si scarica.
    val ok = CatalogCheckedReader(nas.reader) { _, _ -> CatalogExpectation(catalogStamp, quick, null) }
    assertThat(download(ok)).isEqualTo(NasResult.Ok(Sha256.of(content)))

    // Modificato sul posto dal PC (stessa dimensione, data nuova): il catalogo non lo sa ancora.
    val edited = content.copyOf().also { it[0] = (it[0] + 1).toByte() }
    nas.putFile("test", "Foto/a.jpg", NasContent.of(edited), now)
    assertThat(download(ok)).isEqualTo(NasResult.Err(NasError.NotFound))

    // Stesso timbro ma testa diversa (data ripristinata): l'impronta rapida dei byte arrivati non e' quella della versione.
    nas.putFile("test", "Foto/a.jpg", NasContent.of(edited), now - 1_000)
    assertThat(download(ok)).isEqualTo(NasResult.Err(NasError.NotFound))

    // Solo il mezzo diverso (stesso `qh1`) ma l'hash completo del catalogo non torna.
    val middle = content.copyOf().also { it[150_000] = (it[150_000] + 1).toByte() }
    nas.putFile("test", "Foto/a.jpg", NasContent.of(middle), now - 1_000)
    val withHash = CatalogCheckedReader(nas.reader) { _, _ -> CatalogExpectation(catalogStamp, quick, Sha256.of(content)) }
    assertThat(download(withHash)).isEqualTo(NasResult.Err(NasError.NotFound))
  }

  @Test
  fun r8_unFileNatoDallIndiceConUnaDimensionePiccolaNonSiScaricaSeIlFileVeroEGrande() = runBlocking<Unit> {
    val content = Random(2).nextBytes(500_000)
    val (nas, _) = nasWith(content, now - 1_000)
    // Il record dell'indice dichiarava 10 KB (il consenso sui dati mobili si chiede con questa dimensione).
    val reader = CatalogCheckedReader(nas.reader) { _, _ -> CatalogExpectation(NasStamp(10_000, now - 1_000, null), QuickHashAlgorithm.ofContent(content), null) }
    assertThat(download(reader)).isEqualTo(NasResult.Err(NasError.NotFound))
  }

  @Test
  fun r8_ilCollegamentoRifiutaUnaCopiaConUnHashCompletoDiversoDaQuelloNoto() = runBlocking<Unit> {
    val c = catalog()
    val content = Random(3).nextBytes(200_000)
    val outcome = c.recordAll(listOf(nasObs("alessio", "Foto/a.jpg", content, withFullHash = true)), MediaCatalog.WhenUndecided.KEEP_SEPARATE, seenAt = now).single()
    val ref = MediaRef.Nas(outcome.locationId!!.value, ContentKey.of(QuickHashAlgorithm.ofContent(content), content.size.toLong()).short, "image/jpeg")
    val linker = CatalogCopyLinker { c }
    val phone = Location.Phone("external_primary", "Pictures/MyCloud Gallery/", "a.jpg", mediaStoreId = 7)

    val other = Random(4).nextBytes(content.size)
    assertThrows(IOException::class.java) { runBlocking { linker.link(ref, PendingCopy(phone, Sha256.of(other), content.size.toLong())) } }
    // Con gli stessi byte il collegamento c'e' (due posizioni, un media).
    linker.link(ref, PendingCopy(phone, Sha256.of(content), content.size.toLong()))
    assertThat(c.media(outcome.mediaId!!)!!.locations).hasSize(2)
  }

  // --- R10 -----------------------------------------------------------------------------------------------------------

  @Test
  fun r10_unAreaPersaTogliePosizioniEMiniatureProvvisorieDeiMediaSenzaAltrePosizioni() = runBlocking<Unit> {
    val c = catalog()
    val onlyHere = Random(5).nextBytes(10_000)
    val alsoCommon = Random(6).nextBytes(10_000)
    c.recordAll(
      listOf(nasObs("alessio", "Foto/solo.jpg", onlyHere), nasObs("alessio", "Foto/anche.jpg", alsoCommon)),
      MediaCatalog.WhenUndecided.KEEP_SEPARATE,
      seenAt = now,
    )
    c.recordAll(listOf(nasObs("comune", "Foto/anche.jpg", alsoCommon)), MediaCatalog.WhenUndecided.KEEP_SEPARATE, seenAt = now)
    val forgotten = HashSet<Long>()
    val port = CatalogReplicaPort(c, { me }, { it }) { keys -> forgotten += keys }

    port.dropShare("alessio")

    // Le posizioni viste dalla scansione escono dalla Libreria (assenti), non solo quelle nate dall'indice.
    assertThat(c.nasLocationAt(Location.Nas(me, "alessio", NasPath.of("Foto/solo.jpg")))!!.state).isEqualTo(LocationState.ABSENT)
    assertThat(c.nasLocationAt(Location.Nas(me, "alessio", NasPath.of("Foto/anche.jpg")))!!.state).isEqualTo(LocationState.ABSENT)
    assertThat(c.nasLocationAt(Location.Nas(me, "comune", NasPath.of("Foto/anche.jpg")))!!.state).isEqualTo(LocationState.PRESENT)
    // La miniatura provvisoria va via solo per la chiave che nessun'altra posizione usa.
    val keySolo = ContentKey.of(QuickHashAlgorithm.ofContent(onlyHere), onlyHere.size.toLong()).short
    val keyAnche = ContentKey.of(QuickHashAlgorithm.ofContent(alsoCommon), alsoCommon.size.toLong()).short
    assertThat(forgotten).containsExactly(keySolo)
    assertThat(forgotten).doesNotContain(keyAnche)
  }
}
