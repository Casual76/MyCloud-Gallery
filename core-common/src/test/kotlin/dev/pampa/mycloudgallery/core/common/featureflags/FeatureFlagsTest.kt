package dev.pampa.mycloudgallery.core.common.featureflags

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
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

  /**
   * Fase 4A (P11): il NAS nella Libreria e' acceso in release (D31 resta attivo: sulle share vere solo locale) e una richiesta
   * di spegnerlo non cambia la release, come per ogni valore di RELEASE.
   */
  @Test
  fun ilNasNellaLibreriaInReleaseEAccesoEUnaRichiestaNonLoCambia() {
    assertThat(FeatureFlags.RELEASE.nasLibrary).isTrue()
    assertThat(FeatureFlags.forBuild(debugBuild = false, nasLibrary = false).nasLibrary).isTrue()
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

  /**
   * Fase 5, D12: il backup solo sulle share di prova e' acceso in release **e in debug**, e nessuna richiesta lo spegne: `forBuild`
   * non ha nemmeno il parametro (lo controlla la lettura del sorgente qui sotto, perche' un parametro aggiunto con il default
   * acceso passerebbe il resto del test).
   */
  @Test
  fun ilBackupSoloSulleShareDiProvaEAccesoSempreENonSiSpegne() {
    assertThat(FeatureFlags.RELEASE.backupTestSharesOnly).isTrue()
    for (debug in listOf(false, true)) {
      val flags = FeatureFlags.forBuild(debugBuild = debug, wol = true, nasLibrary = true, indexPublishTestSharesOnly = false)
      assertThat(flags.backupTestSharesOnly).isTrue()
      // La decisione per share: solo le share di prova scritte nel codice, mai una share di famiglia o pubblica.
      for (share in listOf("test", "test2", "TEST-comune")) assertThat(flags.backupMayWriteTo(share)).isTrue()
      for (share in listOf("alessio", "pampa", "NAS", "Public", "ｔｅｓｔ", "test ", "test3", "")) {
        assertThat(flags.backupMayWriteTo(share)).isFalse()
      }
    }
    val root = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle").isFile }
    val source = File(root, "core-common/src/main/kotlin/dev/pampa/mycloudgallery/core/common/featureflags/FeatureFlags.kt").readText()
    val forBuild = Regex("""fun\s+forBuild\s*\(([^)]*)\)""").find(source)?.groupValues?.get(1) ?: error("forBuild non trovata")
    assertWithMessage("forBuild non deve poter spegnere D12").that(forBuild).doesNotContain("backup")
    assertWithMessage("D12 e' una costante accesa").that(source).contains("private const val BACKUP_TEST_SHARES_ONLY: Boolean = true")
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
