package dev.pampa.mycloudgallery.nas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.domain.Observation
import dev.pampa.mycloudgallery.core.nas.session.ClockJumpGuard
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le correzioni della revisione di sicurezza della Fase 4B (`docs/review/4b-fase4b.md`) che stanno in `:app`: R1 (la fine dell'ultimo
 * salto dell'ora, salvata con la guardia), R3 (il catalogo che vede un file del cestino fuori dal cestino), R11 (i nascosti fra gli
 * elementi di uno spostamento).
 */
@RunWith(AndroidJUnit4::class)
class NasReview4bTest {

  private val me = AccountId("test")
  private val now = 1_800_000_000_000L
  private var db: GalleryDatabase? = null

  @After
  fun close() {
    db?.close()
  }

  private fun database(): GalleryDatabase = GalleryDatabase.build(ApplicationProvider.getApplicationContext<Context>(), name = null).also { db = it }

  @Test
  fun r1_laFineDellUltimoSaltoDellOraSiSalvaESopravviveAUnProcessoNuovo() {
    val dir = Files.createTempDirectory("nas-clock").toFile()
    try {
      val file = File(File(dir, "replica"), NasGraph.CLOCK_FILE)
      val store = FileClockJumpStore(file)
      val guard = ClockJumpGuard(store)
      val day = 24L * 60 * 60 * 1000
      guard.onAnchor(now, ClockJumpGuard.Reference(1_000, 4, now))
      assertThat(store.trustedSinceNasMs()).isNull()
      // Il NAS torna indietro di un anno: salto, sospensione.
      guard.onAnchor(now - 365 * day, ClockJumpGuard.Reference(2_000, 4, now + 1_000))
      assertThat(guard.blocked).isTrue()
      assertThat(store.trustedSinceNasMs()).isNull()
      // 24 ore di ora stabile: la sospensione finisce, e se ne ricorda l'ora (del NAS).
      val end = now - 365 * day + day + 1
      guard.onAnchor(end, ClockJumpGuard.Reference(2_000 + day + 1, 4, now + day + 2))
      assertThat(guard.blocked).isFalse()
      assertThat(store.trustedSinceNasMs()).isEqualTo(end)
      // Un processo nuovo la rilegge dal file.
      assertThat(FileClockJumpStore(file).trustedSinceNasMs()).isEqualTo(end)
      // Il gesto dell'utente dopo un altro salto la sposta in avanti.
      val reborn = ClockJumpGuard(FileClockJumpStore(file))
      reborn.onAnchor(now + 2 * day, ClockJumpGuard.Reference(3_000 + day, 4, now + 2 * day))
      assertThat(reborn.blocked).isTrue()
      assertThat(reborn.accept()).isTrue()
      assertThat(FileClockJumpStore(file).trustedSinceNasMs()).isEqualTo(now + 2 * day)
    } finally {
      dir.deleteRecursively()
    }
  }

  private fun nasObs(share: String, path: String, fileId: Long): Observation {
    val content = path.toByteArray()
    val fp = Fingerprint.ofContent(content).copy(fullHash = null)
    return Observation(
      Location.Nas(me, share, NasPath.of(path), fileId = fileId),
      FileStamp(content.size.toLong(), now - 86_400_000L),
      fp,
      MediaKind.PHOTO,
      MediaSource.OTHER,
    )
  }

  @Test
  fun r3_ilCatalogoDiceQualiFileDelCestinoSonoAncoraPresentiEDove() = runBlocking {
    val database = database()
    val catalog = MediaCatalog(database, me, zone = { ZoneOffset.UTC }) { now }
    catalog.record(nasObs("test", "Foto/Album/nonna.jpg", fileId = 41L), MediaCatalog.WhenUndecided.KEEP_SEPARATE)
    catalog.record(nasObs("test-comune", "Famiglia/nonno.jpg", fileId = 42L), MediaCatalog.WhenUndecided.KEEP_SEPARATE)
    val presence = CatalogPresenceSql(database, me)
    val found = presence.presentPaths("test", listOf(41L, 42L, 43L))
    assertThat(found.keys).containsExactly(41L)
    assertThat(found.getValue(41L)).containsExactly(SharePath.parse("Foto/Album/nonna.jpg"))
    assertThat(presence.presentPaths("test", emptyList())).isEmpty()
  }

  @Test
  fun r11_iNascostiFraLePosizioniDiUnoSpostamento() = runBlocking {
    val database = database()
    val catalog = MediaCatalog(database, me, zone = { ZoneOffset.UTC }) { now }
    val shown = catalog.record(nasObs("test", "Documenti/a.jpg", fileId = 1L), MediaCatalog.WhenUndecided.KEEP_SEPARATE)
    val hidden = catalog.record(nasObs("test", "Documenti/b.jpg", fileId = 2L), MediaCatalog.WhenUndecided.KEEP_SEPARATE)
    val hiddenId = checkNotNull(hidden.mediaId)
    catalog.setPersonalFlags(listOf(hiddenId)) { it.copy(hidden = true) }
    val keys = listOf("Documenti/a.jpg", "Documenti/b.jpg").map { Location.Nas(me, "test", NasPath.of(it)).key.value }
    assertThat(CatalogHidden.hiddenAmong(database, keys)).isEqualTo(1)
    assertThat(CatalogHidden.isHidden(database, hiddenId)).isTrue()
    assertThat(CatalogHidden.hiddenAmong(database, emptyList())).isEqualTo(0)
    assertThat(CatalogHidden.isHidden(database, checkNotNull(shown.mediaId))).isFalse()
  }
}
