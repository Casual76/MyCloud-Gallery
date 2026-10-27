package dev.pampa.mycloudgallery.core.security.faces

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.security.noop.NoFacesKeys
import dev.pampa.mycloudgallery.core.security.testing.FacesKeysContract
import dev.pampa.mycloudgallery.core.security.testing.FakeFacesKeys
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 8 (P0, D14, §5.2): le chiavi dei volti finte passano la suite di contratto della chiave vera (P6 la fa girare sul Keystore), e
 * simulano cio' che il banco della cancellazione deve vedere: la chiave sparita (E-F19), il Keystore che non risponde, l'ordine delle
 * distruzioni (la chiave prima dei file, D29). Il null-object fallisce chiuso.
 */
class FacesKeysTest {

  @Test
  fun laChiaveFintaPassaLaSuiteDiContratto() = runTest {
    FacesKeysContract.verify(FakeFacesKeys())
  }

  @Test
  fun chiaveSparitaGuastiEOrdineDelleOperazioni() = runTest {
    val keys = FakeFacesKeys()
    val g = FaceGeneration.random()
    assertThat(keys.create(g)).isTrue()
    val sealed = keys.open(g)!!.use { it.seal(byteArrayOf(1, 2, 3), byteArrayOf(9)) }
    keys.lose(g)
    assertThat(keys.open(g)).isNull()
    assertThat(keys.exists(g)).isFalse()
    // Una chiave nuova per la stessa generazione non rilegge i dati di prima: si cancellano e si riscaricano (E-F19).
    keys.create(g)
    assertThat(keys.open(g)!!.use { it.open(sealed, byteArrayOf(9)) }).isNull()
    keys.failNext = true
    assertThat(keys.destroy(g)).isFalse()
    assertThat(keys.destroy(g)).isTrue()
    assertThat(keys.events.map { it.substringBefore(':') }).containsExactly("create", "lost", "create", "destroy").inOrder()
    assertThat(keys.events.all { it.endsWith(FacesKeyAliases.aliasOf(g)) }).isTrue()
    assertThat(keys.openKeys).isEqualTo(0)
  }

  @Test
  fun gliAliasDeiVoltiENessunaChiaveSenzaKeystore() = runTest {
    val g = FaceGeneration.random()
    val alias = FacesKeyAliases.aliasOf(g)
    assertThat(alias).isEqualTo("mcg-faces-${g.base32}")
    assertThat(FacesKeyAliases.generationOf(alias)).isEqualTo(g)
    assertThat(FacesKeyAliases.generationOf("mcg-credentials")).isNull()
    assertThat(FacesKeyAliases.generationOf("mcg-faces-nonvalida")).isNull()
    assertThat(NoFacesKeys.create(g)).isFalse()
    assertThat(NoFacesKeys.open(g)).isNull()
    assertThat(NoFacesKeys.destroy(g)).isTrue()
    assertThat(NoFacesKeys.destroyAll()).isTrue()
  }
}
