package dev.pampa.mycloudgallery.core.common.featureflags

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FeatureFlagsTest {

  @Test
  fun inReleaseIlWolNonSiAccendeNemmenoChiedendolo() {
    assertThat(FeatureFlags.forBuild(debugBuild = false, wol = true).wol).isFalse()
    assertThat(FeatureFlags.forBuild(debugBuild = false, wol = true)).isEqualTo(FeatureFlags.RELEASE)
  }

  @Test
  fun inDebugSiPuoAccendereEDiDefaultESpento() {
    assertThat(FeatureFlags.forBuild(debugBuild = true).wol).isFalse()
    assertThat(FeatureFlags.forBuild(debugBuild = true, wol = true).wol).isTrue()
  }

  /** Fase 4: il NAS nella Libreria resta spento in release finche' l'integrazione non cambia RELEASE. */
  @Test
  fun ilNasNellaLibreriaInReleaseNonSiAccendeChiedendolo() {
    assertThat(FeatureFlags.RELEASE.nasLibrary).isFalse()
    assertThat(FeatureFlags.forBuild(debugBuild = false, nasLibrary = true).nasLibrary).isFalse()
    assertThat(FeatureFlags.forBuild(debugBuild = true, nasLibrary = true).nasLibrary).isTrue()
    assertThat(FeatureFlags.forBuild(debugBuild = true).nasLibrary).isFalse()
  }

  /** D31: la pubblicazione solo sulle share di prova e' una protezione; in release non si spegne. */
  @Test
  fun laPubblicazioneSoloSulleShareDiProvaInReleaseNonSiSpegne() {
    assertThat(FeatureFlags.RELEASE.indexPublishTestSharesOnly).isTrue()
    assertThat(FeatureFlags.forBuild(debugBuild = false, indexPublishTestSharesOnly = false).indexPublishTestSharesOnly).isTrue()
    assertThat(FeatureFlags.forBuild(debugBuild = true).indexPublishTestSharesOnly).isTrue()
    assertThat(FeatureFlags.forBuild(debugBuild = true, indexPublishTestSharesOnly = false).indexPublishTestSharesOnly).isFalse()
  }

  @Test
  fun leShareDiProvaSonoSoloQuelleScritteNelCodice() {
    assertThat(TestShares.isTestShare("test")).isTrue()
    assertThat(TestShares.isTestShare("TEST-comune")).isTrue()
    assertThat(TestShares.isTestShare("test2")).isTrue()
    // Pubbliche o di famiglia: mai, in nessuna forma.
    for (share in listOf("NAS", "Public", "public", "alessio", "pampa", "ｔｅｓｔ", "test ", "test3", "", "test/..")) {
      assertThat(TestShares.isTestShare(share)).isFalse()
    }
    // Per rifiutare si normalizza (NFKC): una share di famiglia scritta "a larghezza piena" resta di famiglia.
    assertThat(TestShares.isFamilyShare("ＮＡＳ")).isTrue()
    assertThat(TestShares.isFamilyShare("Public")).isTrue()
    assertThat(TestShares.isFamilyShare("test-comune")).isFalse()
    assertThat(TestShares.isTestAccount("Test2")).isTrue()
    assertThat(TestShares.isTestAccount("alessio")).isFalse()
    assertThat(TestShares.isTestAccount("admin")).isFalse()
  }
}
