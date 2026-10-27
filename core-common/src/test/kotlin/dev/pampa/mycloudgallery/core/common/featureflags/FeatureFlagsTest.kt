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

  /**
   * R2 della revisione di sicurezza della 5A: D13 (la recinzione del telefono) esiste solo in debug, quindi **in release** un account di
   * prova non fa scrivere il backup in nessuna share, nemmeno di prova. In debug la recinzione c'e' e la decisione non blocca.
   */
  @Test
  fun r2_inReleaseUnAccountDiProvaNonScriveInNessunaShare() {
    val release = FeatureFlags.RELEASE
    assertThat(release.backupBlocksTestAccounts).isTrue()
    assertThat(FeatureFlags.forBuild(debugBuild = false, wol = true, nasLibrary = false, indexPublishTestSharesOnly = false)).isEqualTo(release)
    for (account in listOf("test", "test2", "TEST", "Test2")) assertWithMessage(account).that(release.backupMayWriteAs(account)).isFalse()
    // Un account vero puo' (poi decide D12 per share); un account che non si conosce non si dice "non di prova": si fallisce chiusi.
    for (account in listOf("alessio", "pampa", "admin", "mario", "")) assertWithMessage(account).that(release.backupMayWriteAs(account)).isTrue()
    assertThat(release.backupMayWriteAs(null)).isFalse()
  }

  @Test
  fun r2_inDebugLaRecinzioneDelTelefonoCeEIlBackupDiProvaPuoGirare() {
    val debug = FeatureFlags.forBuild(debugBuild = true)
    assertThat(debug.backupBlocksTestAccounts).isFalse()
    for (account in listOf("test", "test2", "alessio", null)) assertThat(debug.backupMayWriteAs(account)).isTrue()
    // D12 resta in debug come in release: R2 non lo tocca.
    assertThat(debug.backupTestSharesOnly).isTrue()
    assertThat(debug.backupMayWriteTo("test")).isTrue()
    assertThat(debug.backupMayWriteTo("alessio")).isFalse()
  }

  /** R2 non si chiede da fuori: `forBuild` non ha un parametro per spegnere la protezione, come per D12 (la lettura del sorgente). */
  @Test
  fun r2_laProtezioneNonSiSpegneDaFuori() {
    val root = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle").isFile }
    val source = File(root, "core-common/src/main/kotlin/dev/pampa/mycloudgallery/core/common/featureflags/FeatureFlags.kt").readText()
    val forBuild = Regex("""fun\s+forBuild\s*\(([^)]*)\)""").find(source)?.groupValues?.get(1) ?: error("forBuild non trovata")
    assertWithMessage("forBuild non deve poter spegnere R2").that(forBuild).doesNotContain("Account")
    // Anche le build di release di un valore diverso da RELEASE non esistono: il costruttore e' privato.
    assertWithMessage("RELEASE accende R2").that(source).contains("backupBlocksTestAccounts = true")
  }

  /**
   * Fase 7, D30: gli album condivisi solo fra le share di prova, acceso in release **e in debug** (risposta Q7 (a): si spegne a fine
   * Fase 7, con il consenso, cambiando la costante). Come D12, `forBuild` non ha il parametro: lo controlla la lettura del sorgente.
   */
  @Test
  fun d30_gliAlbumCondivisiSoloSulleShareDiProvaAncheInRelease() {
    assertThat(FeatureFlags.RELEASE.sharedAlbumsTestSharesOnly).isTrue()
    for (debug in listOf(false, true)) {
      val flags = FeatureFlags.forBuild(debugBuild = debug, wol = true, nasLibrary = true, indexPublishTestSharesOnly = false)
      assertThat(flags.sharedAlbumsTestSharesOnly).isTrue()
      for (share in listOf("test", "test2", "TEST-comune")) assertWithMessage(share).that(flags.sharedAlbumsMayUse(share)).isTrue()
      for (share in listOf("alessio", "pampa", "NAS", "Public", "ｔｅｓｔ-comune", "test-comune ", "test3", "")) {
        assertWithMessage(share).that(flags.sharedAlbumsMayUse(share)).isFalse()
      }
    }
    val root = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle").isFile }
    val source = File(root, "core-common/src/main/kotlin/dev/pampa/mycloudgallery/core/common/featureflags/FeatureFlags.kt").readText()
    val forBuild = Regex("""fun\s+forBuild\s*\(([^)]*)\)""").find(source)?.groupValues?.get(1) ?: error("forBuild non trovata")
    assertWithMessage("forBuild non deve poter spegnere D30").that(forBuild).doesNotContain("haredAlbums")
    assertWithMessage("D30 e' una costante accesa").that(source).contains("private const val SHARED_ALBUMS_TEST_SHARES_ONLY: Boolean = true")
    // La release la tiene anche prima che il cancello degli album sia legato (P13): la regola R8 del modulo.
    val rules = File(root, "core-common/consumer-rules.pro").readText()
    assertThat(rules).contains("public boolean getSharedAlbumsTestSharesOnly();")
    assertThat(rules).contains("public boolean sharedAlbumsMayUse(java.lang.String);")
  }

  /**
   * Fase 8, D34: i dati dei volti sul NAS solo nelle share di prova, acceso in release **e in debug** (risposta Q5 (a): si spegne a fine
   * Fase 8, con il consenso, cambiando la costante). Come D30, `forBuild` non ha il parametro e la regola R8 lo tiene nella release.
   */
  @Test
  fun d34_iVoltiSulNasSoloSulleShareDiProvaAncheInRelease() {
    assertThat(FeatureFlags.RELEASE.facesSyncTestSharesOnly).isTrue()
    for (debug in listOf(false, true)) {
      val flags = FeatureFlags.forBuild(debugBuild = debug, wol = true, nasLibrary = true, indexPublishTestSharesOnly = false)
      assertThat(flags.facesSyncTestSharesOnly).isTrue()
      for (share in listOf("test", "test2", "TEST")) assertWithMessage(share).that(flags.facesSyncMayUse(share)).isTrue()
      for (share in listOf("alessio", "pampa", "NAS", "Public", "ｔｅｓｔ", "test ", "test3", "")) {
        assertWithMessage(share).that(flags.facesSyncMayUse(share)).isFalse()
      }
    }
    assertThat(FeatureFlags.RELEASE.toString()).contains("facesSyncTestSharesOnly=true")
    val root = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle").isFile }
    val source = File(root, "core-common/src/main/kotlin/dev/pampa/mycloudgallery/core/common/featureflags/FeatureFlags.kt").readText()
    val forBuild = Regex("""fun\s+forBuild\s*\(([^)]*)\)""").find(source)?.groupValues?.get(1) ?: error("forBuild non trovata")
    assertWithMessage("forBuild non deve poter spegnere D34").that(forBuild).doesNotContain("acesSync")
    assertWithMessage("D34 e' una costante accesa").that(source).contains("private const val FACES_SYNC_TEST_SHARES_ONLY: Boolean = true")
    val rules = File(root, "core-common/consumer-rules.pro").readText()
    assertThat(rules).contains("public boolean getFacesSyncTestSharesOnly();")
    assertThat(rules).contains("public boolean facesSyncMayUse(java.lang.String);")
  }

  /**
   * Fase 9, D24 e risposta Q1 (c) del 27/10: la mappa online c'e' in release (accesa di default su ogni dispositivo; ognuno la spegne per se'
   * in Impostazioni). E' un interruttore di build: in debug `forBuild` la puo' solo **spegnere** (prove a mappa spenta), e una costante spenta
   * non si riaccende da nessuna build. Con la mappa online o senza, il cancello di rete resta (lo provano i test di core-map e feature-map).
   */
  @Test
  fun d24_laMappaOnlineCeInReleaseEInDebugSiPuoSoloSpegnere() {
    assertThat(FeatureFlags.RELEASE.mapOnline).isTrue()
    assertThat(FeatureFlags.forBuild(debugBuild = false, mapOnline = false).mapOnline).isTrue()
    assertThat(FeatureFlags.forBuild(debugBuild = false, mapOnline = false)).isEqualTo(FeatureFlags.RELEASE)
    assertThat(FeatureFlags.forBuild(debugBuild = true).mapOnline).isTrue()
    assertThat(FeatureFlags.forBuild(debugBuild = true, mapOnline = false).mapOnline).isFalse()
    assertThat(FeatureFlags.forBuild(debugBuild = true, mapOnline = false)).isNotEqualTo(FeatureFlags.forBuild(debugBuild = true))
    assertThat(FeatureFlags.RELEASE.toString()).contains("mapOnline=true")
    val root = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle").isFile }
    val source = File(root, "core-common/src/main/kotlin/dev/pampa/mycloudgallery/core/common/featureflags/FeatureFlags.kt").readText()
    assertWithMessage("D24: la costante di build").that(source).contains("private const val MAP_ONLINE: Boolean = true")
    // Una richiesta della debug non accende una build senza mappa online: il valore e' sempre in "e" con la costante.
    assertWithMessage("D24: forBuild puo' solo spegnere").that(source).contains("mapOnline = mapOnline && MAP_ONLINE")
  }
}
