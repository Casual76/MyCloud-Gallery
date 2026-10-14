package dev.pampa.mycloudgallery.core.nasmedia

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.domain.LinkDecision
import dev.pampa.mycloudgallery.core.model.domain.Observation
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nasmedia.contract.Freeze
import dev.pampa.mycloudgallery.core.nasmedia.contract.MassChangeRule
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanCursor
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasSeen
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanDiff
import dev.pampa.mycloudgallery.core.nasmedia.contract.UserGesture
import dev.pampa.mycloudgallery.core.nasmedia.testing.CatalogNasScanStore
import dev.pampa.mycloudgallery.core.nasmedia.testing.FakeFreezeState
import dev.pampa.mycloudgallery.core.nasmedia.testing.SyntheticNas
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I contratti di core-nasmedia sui fake di P0 (con un catalogo vero in memoria): cio' su cui P5 costruisce la scansione
 * senza aspettare lo schema v3 di P2 ne' l'accesso SMB di P1.
 */
@RunWith(AndroidJUnit4::class)
class NasMediaContractTest {

  private val me = AccountId("test")
  private var now = 1_800_000_000_000L
  private lateinit var db: GalleryDatabase
  private lateinit var catalog: MediaCatalog
  private lateinit var store: CatalogNasScanStore

  @Before
  fun open() {
    db = GalleryDatabase.build(ApplicationProvider.getApplicationContext<Context>(), name = null)
    catalog = MediaCatalog(db, me, zone = { ZoneId.of("Europe/Rome") }) { now }
    store = CatalogNasScanStore(catalog)
  }

  @After
  fun close() {
    db.close()
  }

  private fun observation(path: String, content: ByteArray, mtime: Long, fileId: Long): Observation {
    val location = Location.Nas(me, "test", NasPath.of(path), fileId)
    val fp = Fingerprint(content.size.toLong(), QuickHashAlgorithm.ofContent(content))
    return Observation(location, NasStamp(content.size.toLong(), mtime, fileId).toFileStamp(), fp, MediaKind.PHOTO, MediaSource.OTHER)
  }

  @Test
  fun leSoglieDelCongelamentoSonoQuelleDiD10() {
    assertThat(MassChangeRule.threshold(0)).isEqualTo(50)
    assertThat(MassChangeRule.threshold(999)).isEqualTo(50)
    assertThat(MassChangeRule.threshold(1001)).isEqualTo(51)
    assertThat(MassChangeRule.threshold(12_244)).isEqualTo(613)
  }

  @Test
  fun registraConfermaEsegnaLeAssenzeSoloDopoUnGiroCompleto() = runTest {
    val epoch1 = now
    val outcomes = store.recordAll(
      listOf(observation("Foto/a.jpg", byteArrayOf(1, 2, 3), epoch1, 11), observation("Foto/b.jpg", byteArrayOf(4, 5, 6), epoch1, 12)),
      MediaCatalog.WhenUndecided.KEEP_SEPARATE,
      NasScanCursor(me, "test", epoch = epoch1, resumeAfter = NasPath.of("Foto")),
      seenAt = epoch1,
    )
    assertThat(outcomes.map { it.decision }).containsExactly(LinkDecision.NewMedia, LinkDecision.NewMedia)
    assertThat(store.cursor(me, "TEST")!!.resumeAfter).isEqualTo(NasPath.of("Foto"))
    assertThat(store.knownIn(me, "test", NasPath.of("foto"))).hasSize(2)
    assertThat(store.countPresent(me, "test")).isEqualTo(2)

    // Secondo giro: a.jpg uguale (scorciatoia del timbro), b.jpg sparito.
    now += 3_600_000
    val epoch2 = now
    val confirmed = store.confirmAllPresent(
      listOf(NasSeen(Location.Nas(me, "test", NasPath.of("FOTO/A.JPG")), NasStamp(3, epoch1, 11))),
      seenAt = epoch2,
      cursor = null,
    )
    assertThat(confirmed).containsExactly(true)
    assertThat(store.markVanishedInShare(me, "test", epoch2, cap = 10)).isEqualTo(1)
    assertThat(store.countPresent(me, "test")).isEqualTo(1)
    val b = catalog.media(outcomes[1].mediaId!!)!!
    assertThat(b.locations.single().state).isEqualTo(LocationState.ABSENT)
  }

  @Test
  fun unaCartellaRinominataDalPcESpostamentoNonMediaNuovo() = runTest {
    val content = ByteArray(300) { 3 }
    val first = store.recordAll(listOf(observation("Viaggi/2024/a.jpg", content, now, 77)), MediaCatalog.WhenUndecided.KEEP_SEPARATE, null, now)
    // E-N1: stesso fileId, stessa data, posto nuovo.
    store.markVanishedInShare(me, "test", now + 1, cap = 10)
    val moved = store.recordAll(listOf(observation("Viaggi/Spagna 2024/a.jpg", content, now, 77)), MediaCatalog.WhenUndecided.KEEP_SEPARATE, null, now + 2)
    assertThat(moved.single().decision).isInstanceOf(LinkDecision.Moved::class.java)
    assertThat(moved.single().mediaId).isEqualTo(first.single().mediaId)
  }

  @Test
  fun ilCongelamentoSiTogliesoloConIlConteggioGiusto() = runTest {
    val freeze = Freeze(FreezeReason.NOT_MEDIA, now, ScanDiff(knownPresent = 1000, vanished = 0, changed = 0, notMedia = 500))
    val state = FakeFreezeState(freeze)
    assertThat(state.release(UserGesture(confirmedCount = 499))).isFalse()
    assertThat(state.frozen.value).isEqualTo(freeze)
    assertThat(state.release(UserGesture(confirmedCount = 500))).isTrue()
    assertThat(state.frozen.value).isNull()
  }

  @Test
  fun ilNasSinteticoHaICasiDifficiliESiLeggeDalContratto() = runTest {
    val nas = InMemoryNasMedia()
    val synthetic = SyntheticNas(nas, seed = 7)
    synthetic.populate(SyntheticNas.Profile.PICCOLO)

    assertThat(synthetic.generated.keys).containsAtLeast("foto", "video", "copertine", "zero", "arrivo", "sistema", "maiuscole")
    var entries = 0
    nas.reader.list("test", dev.pampa.mycloudgallery.core.trust.nas.SharePath.of("Camera"), Purpose.BACKGROUND, 50) { entries += it.size }
    assertThat(entries).isAtLeast(120)
    val renamed = synthetic.ransomware("test", synthetic.generated.getValue("foto"), count = 60)
    assertThat(renamed).hasSize(60)
    assertThat(nas.exists("test", renamed.first())).isTrue()
  }
}
