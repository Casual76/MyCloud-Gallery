package dev.pampa.mycloudgallery.debugtools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * D13 (Fase 5A, P0): la recinzione del telefono del backup nella build debug. Con un account di prova solo `DCIM/mcg-prova/` e
 * `Pictures/mcg-prova/` (e le loro sottocartelle), scritture sotto `mcg-campo/`; gli altri account non sono recintati qui (vale D12). Il
 * punto di aggancio e' `DeveloperTools.backupFence()`, che in release risponde null (tools/check.ps1 cerca la classe nel dex di release).
 */
class DebugBackupFenceTest {

  private val fence = DeveloperTools.backupFence()!!

  @Test
  fun conUnAccountDiProvaSiLeggonoSoloLeCartelleSeminate() {
    for (account in listOf("test", "test2", "TEST", " test2 ")) {
      assertThat(fence.allowsPhoneFolder(account, "DCIM/mcg-prova/")).isTrue()
      assertThat(fence.allowsPhoneFolder(account, "Pictures/mcg-prova/")).isTrue()
      assertThat(fence.allowsPhoneFolder(account, "Pictures/mcg-prova/sotto/")).isTrue()
      assertThat(fence.allowsPhoneFolder(account, "DCIM/mcg-prova")).isTrue()
      // Le foto vere della famiglia no, in nessuna forma.
      for (path in listOf("DCIM/Camera/", "Pictures/", "DCIM/mcg-prova-altro/", "DCIM/mcg-prova/../Camera/", "/DCIM/mcg-prova/", "")) {
        assertThat(fence.allowsPhoneFolder(account, path)).isFalse()
      }
      assertThat(fence.destinationRoot(account)!!.value).isEqualTo("mcg-campo")
    }
  }

  @Test
  fun gliAltriAccountNonSonoRecintatiQui() {
    assertThat(fence.allowsPhoneFolder("alessio", "DCIM/Camera/")).isTrue()
    assertThat(fence.destinationRoot("alessio")).isNull()
  }
}
