package dev.pampa.mycloudgallery.core.backup.chaos

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.GuardScope
import dev.pampa.mycloudgallery.core.backup.free.FreeSpaceCursor
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.Sha256
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Il catalogo unico del banco della 5B ([ChaosMediaCatalog]): le tre viste (versioni, "Libera spazio", guardiano) sullo stesso dato, e le due cose
 * che il catalogo vero fa e quello di P8 no (una copia del telefono che Android ha tolto non e' piu' presente; una rilettura che non torna toglie
 * la verifica). Se il catalogo del banco mentisse, il banco darebbe falsi rossi o falsi verdi.
 */
class ChaosMediaCatalogTest {

  @get:Rule val folder = TemporaryFolder()

  private fun newWorld(): FiveBWorld {
    val clock = FakeBackupClock()
    return FiveBWorld(BackupChaosWorld(clock, FakeNasSide(clock, BackupChaosWorld.DEVICE)), Backup5BConfig(), Random(3), folder.newFolder())
  }

  @Test
  fun unMediaAlSicuroHaLeSueVersioniEPosizioniNelGrafoDiLiberaSpazio() = runTest {
    val fb = newWorld()
    val rec = fb.seed(FiveBCategory.EDITED_SAFE)
    val free = fb.catalog.media(listOf(rec.mediaId)).single()
    val media = free.media
    assertThat(media.versions).hasSize(2)
    assertThat(media.currentVersionId).isEqualTo(fb.catalog.inner.currentOf(rec.mediaId))
    // Due copie private verificate (originale in Versioni/, attuale al solito percorso) e la riga del telefono presente.
    val nas = media.locations.filter { it.isNas }
    assertThat(nas).hasSize(2)
    assertThat(nas.all { it.verifiedAtMillis != null && it.isPresent }).isTrue()
    assertThat(media.locations.single { it.isPhone }.isPresent).isTrue()
    assertThat(free.mime).isNotNull()
  }

  @Test
  fun laRilletturaCheNonTornaTogliELaVerificaEQuellaGiustaLaRinnova() = runTest {
    val fb = newWorld()
    val rec = fb.seed(FiveBCategory.SAFE_PHONE)
    val free = fb.catalog.media(listOf(rec.mediaId)).single().media
    val copy = free.locations.single { it.isNas }
    val sha = free.version(copy.versionId).fingerprint.fullHash!!
    // Uno SHA-256 diverso da quello della versione: la verifica cade (E-B8).
    assertThat(fb.catalog.markVerified(copy.id, Sha256.of(byteArrayOf(1, 2, 3)), 5_000L)).isFalse()
    assertThat(fb.catalog.media(listOf(rec.mediaId)).single().media.location(copy.id).verifiedAtMillis).isNull()
    // Quello giusto: verificata adesso (l'istante e' quello della rilettura).
    assertThat(fb.catalog.markVerified(copy.id, sha, 9_000L)).isTrue()
    assertThat(fb.catalog.media(listOf(rec.mediaId)).single().media.location(copy.id).verifiedAtMillis).isEqualTo(9_000L)
  }

  @Test
  fun unaRigaTolta_daAndroid_nonEPiuPresenteNeiMediaNeNelleVersioni() = runTest {
    val fb = newWorld()
    val rec = fb.seed(FiveBCategory.SAFE_PHONE)
    val before = fb.catalog.media(listOf(rec.mediaId)).single().media
    val phone = before.locations.single { it.isPhone }
    fb.catalog.freedFromPhone(listOf(phone.id))
    val after = fb.catalog.media(listOf(rec.mediaId)).single().media
    assertThat(after.location(phone.id).state).isEqualTo(LocationState.ABSENT)
    assertThat(fb.catalog.media(rec.mediaId)!!.current.phone.single().present).isFalse()
    // Senza riga presente il media non e' piu' in coda per "Libera spazio".
    assertThat(fb.catalog.phoneMedia(null, 10).map { it.id }).doesNotContain(rec.mediaId)
  }

  @Test
  fun leDuePagineDelCatalogoVannoDalPiuRecenteSenzaRipetersi() = runTest {
    val fb = newWorld()
    repeat(5) { fb.seed(FiveBCategory.SAFE_PHONE) }
    val first = fb.catalog.phoneMedia(null, 2)
    val second = fb.catalog.phoneMedia(FreeSpaceCursor(first.last().sortTime, first.last().id), 10)
    assertThat(first.map { it.id } + second.map { it.id }).containsNoDuplicates()
    assertThat(first.size + second.size).isEqualTo(5)
    assertThat((first + second).map { it.sortTime }).isInOrder(Comparator.reverseOrder<Long>())
  }

  @Test
  fun ilGuardianoVedeSoloIMediaPropriConLaSolaCopiaComune() = runTest {
    val fb = newWorld()
    val common = fb.seed(FiveBCategory.COMMON_ONLY)
    fb.seed(FiveBCategory.SAFE_PHONE)
    val candidates = fb.catalog.onlyCommonCopies(GuardScope.All, 10)
    assertThat(candidates.map { it.common.mediaId }).containsExactly(common.mediaId)
    assertThat(fb.catalog.onlyCommonCopies(GuardScope.Media(setOf(common.mediaId)), 10)).hasSize(1)
    assertThat(fb.catalog.onlyCommonCopies(GuardScope.Media(emptySet()), 10)).isEmpty()
    // Con una copia privata (anche in Versioni/) il media non e' piu' un caso del guardiano.
    val v = fb.catalog.inner.currentOf(common.mediaId)
    fb.catalog.inner.putNasCopy(common.mediaId, v, "test", dev.pampa.mycloudgallery.core.trust.nas.SharePath.of("Backup", "x.jpg"), fb.stampOf("test-comune", dev.pampa.mycloudgallery.core.trust.nas.SharePath.of("Foto condivise", common.name))!!, null)
    assertThat(fb.catalog.onlyCommonCopies(GuardScope.All, 10)).isEmpty()
  }

  @Test
  fun lAreaDelCatalogoDiceSAlMediaSePossibileOLaSolaCopiaComune() = runTest {
    val fb = newWorld()
    val safe = fb.seed(FiveBCategory.SAFE_PHONE)
    val common = fb.seed(FiveBCategory.COMMON_ONLY)
    val phoneOnly = fb.seed(FiveBCategory.PHONE_ONLY)
    assertThat(fb.catalog.mediaSafety(safe.mediaId)!!.status.code).isEqualTo("safe")
    assertThat(fb.catalog.mediaSafety(common.mediaId)!!.status.code).isEqualTo("only_common_copy")
    assertThat(fb.catalog.mediaSafety(phoneOnly.mediaId)!!.status.code).isEqualTo("no_nas_copy")
  }
}
