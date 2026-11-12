package dev.pampa.mycloudgallery.albums

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.FencedFlags
import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteBlock
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.index.contract.IndexWriteGate
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.nas.session.ShareGuard
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Fase 7 (P13; D30, D31, F4-D33, §1.4 "Cancelli"): il cancello delle scritture degli album in `:app` (`AppAlbumWriteGate`) e' D30 **nella release**
 * (album condivisi solo sulle share di prova: una share di famiglia e' `TEST_SHARES_ONLY` prima di ogni altra domanda), poi la recinzione delle prove
 * in debug (`ShareFence`), poi le regole dell'indice (congelamento, salto dell'ora, sola lettura, emergenza, fiducia) tradotte una per una. D31
 * ("indice solo locale" sulle share vere) non e' la recinzione degli album e non li ferma: per loro decide D30. Senza account o senza il cancello
 * dell'indice non si scrive (fallisce chiuso).
 *
 * **Dal passo unico del 12/11/2026** (D35) D30 e' spenta in `FeatureFlags`: le prove di D30 usano flag con la recinzione accesa ([FencedFlags],
 * solo nelle prove); `passoUnico_*` usa i valori di release di adesso (una share vera passa, le altre regole restano).
 */
class AlbumWriteGateTest {

  private fun gate(
    flags: FeatureFlags = FencedFlags.of(),
    guard: ShareGuard? = null,
    user: String? = "test",
    index: IndexWriteGate? = IndexWriteGate { null },
  ) = AppAlbumWriteGate(flags, guard, { user }, { index })

  @Test
  fun conLaRecinzioneAccesaUnaShareDiFamigliaEFermaPrimaDiTutto() = runBlocking<Unit> {
    assertThat(FencedFlags.of().sharedAlbumsTestSharesOnly).isTrue()
    assertThat(gate().blockFor("Famiglia")).isEqualTo(AlbumWriteBlock.TEST_SHARES_ONLY)
    // Anche con tutto il resto che direbbe di si'.
    assertThat(gate(guard = { _, _ -> true }).blockFor("Comune")).isEqualTo(AlbumWriteBlock.TEST_SHARES_ONLY)
    assertThat(gate().blockFor("test-comune")).isNull()
  }

  @Test
  fun laRecinzioneDelleProveFermaLaShareNonAmmessa() = runBlocking<Unit> {
    assertThat(gate(guard = { _, share -> share == "test" }).blockFor("test-comune")).isEqualTo(AlbumWriteBlock.FENCE)
    assertThat(gate(guard = { _, share -> share == "test-comune" }).blockFor("test-comune")).isNull()
  }

  @Test
  fun leRegoleDellIndiceSiTraduconoUnaPerUnaED31NonFermaGliAlbum() = runBlocking<Unit> {
    val expected = mapOf(
      WriteBlock.FROZEN to AlbumWriteBlock.FROZEN,
      WriteBlock.CLOCK_JUMP to AlbumWriteBlock.CLOCK_JUMP,
      WriteBlock.READ_ONLY_AREA to AlbumWriteBlock.READ_ONLY,
      WriteBlock.EMERGENCY_STOP to AlbumWriteBlock.EMERGENCY_STOP,
      WriteBlock.NOT_TRUSTED to AlbumWriteBlock.NOT_TRUSTED,
      WriteBlock.LOCAL_ONLY to null,
    )
    assertThat(expected.keys).containsExactlyElementsIn(WriteBlock.entries)
    for ((block, album) in expected) assertThat(gate(index = { block }).blockFor("test-comune")).isEqualTo(album)
  }

  @Test
  fun senzaAccountOSenzaIlCancelloDellIndiceNonSiScrive() = runBlocking<Unit> {
    assertThat(gate(user = null).blockFor("test-comune")).isEqualTo(AlbumWriteBlock.NO_NAS)
    assertThat(gate(index = null).blockFor("test-comune")).isEqualTo(AlbumWriteBlock.NO_NAS)
  }

  @Test
  fun passoUnico_conLaReleaseDiAdessoUnaShareVeraPassaELeAltreRegoleRestano() = runBlocking<Unit> {
    val now = FeatureFlags.RELEASE
    assertThat(now.sharedAlbumsTestSharesOnly).isFalse()
    for (share in listOf("Public", "Famiglia", "alessio")) assertThat(gate(flags = now, user = "alessio").blockFor(share)).isNull()
    assertThat(gate(flags = now, user = "alessio", index = { WriteBlock.EMERGENCY_STOP }).blockFor("Public")).isEqualTo(AlbumWriteBlock.EMERGENCY_STOP)
    assertThat(gate(flags = now, user = "alessio", index = { WriteBlock.FROZEN }).blockFor("Public")).isEqualTo(AlbumWriteBlock.FROZEN)
    assertThat(gate(flags = now, user = "alessio", index = { WriteBlock.READ_ONLY_AREA }).blockFor("Public")).isEqualTo(AlbumWriteBlock.READ_ONLY)
    assertThat(gate(flags = now, user = null).blockFor("Public")).isEqualTo(AlbumWriteBlock.NO_NAS)
    assertThat(gate(flags = now, user = "alessio", index = null).blockFor("Public")).isEqualTo(AlbumWriteBlock.NO_NAS)
  }
}
