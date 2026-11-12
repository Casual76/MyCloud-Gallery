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
   * Fase 4A (P11): il NAS nella Libreria e' acceso in release e una richiesta di spegnerlo non cambia la release, come per ogni valore di
   * RELEASE.
   */
  @Test
  fun ilNasNellaLibreriaInReleaseEAccesoEUnaRichiestaNonLoCambia() {
    assertThat(FeatureFlags.RELEASE.nasLibrary).isTrue()
    assertThat(FeatureFlags.forBuild(debugBuild = false, nasLibrary = false).nasLibrary).isTrue()
    assertThat(FeatureFlags.forBuild(debugBuild = true, nasLibrary = true).nasLibrary).isTrue()
    assertThat(FeatureFlags.forBuild(debugBuild = true).nasLibrary).isFalse()
  }

  /**
   * F4-D31: la pubblicazione solo sulle share di prova e' **spenta in release dal passo unico del 12/11/2026** (D35, consenso in
   * docs/decisioni.md). La release resta quella di [FeatureFlags.RELEASE]: una richiesta della build non la cambia (nemmeno per riaccenderla);
   * in debug resta un parametro e di serie e' accesa. La decisione dentro le regole dell'indice e' provata in core-index.
   */
  @Test
  fun laPubblicazioneSoloSulleShareDiProvaInReleaseESpentaDalPassoUnico() {
    assertThat(FeatureFlags.RELEASE.indexPublishTestSharesOnly).isFalse()
    assertThat(FeatureFlags.forBuild(debugBuild = false, indexPublishTestSharesOnly = true).indexPublishTestSharesOnly).isFalse()
    assertThat(FeatureFlags.forBuild(debugBuild = false, indexPublishTestSharesOnly = true)).isEqualTo(FeatureFlags.RELEASE)
    assertThat(FeatureFlags.forBuild(debugBuild = true).indexPublishTestSharesOnly).isTrue()
    assertThat(FeatureFlags.forBuild(debugBuild = true, indexPublishTestSharesOnly = false).indexPublishTestSharesOnly).isFalse()
    val release = Regex("""val\s+RELEASE\s*:\s*FeatureFlags\s*=\s*FeatureFlags\(([^)]*)\)""").find(flagsSource())?.groupValues?.get(1)
      ?: error("RELEASE non trovata")
    assertWithMessage("F4-D31 spenta in RELEASE").that(release).contains("indexPublishTestSharesOnly = false,")
  }

  /**
   * Fase 5, D12: il backup solo sulle share di prova e' **spento dal passo unico del 12/11/2026**, in release e in debug (una costante sola).
   * `forBuild` continua a non avere il parametro (la lettura del sorgente qui sotto): la costante la cambia solo l'utente, a mano. Con i valori
   * di adesso la decisione lascia scrivere anche le share vere; con la recinzione accesa ([FencedFlags], solo nelle prove) lascia solo quelle
   * di prova scritte nel codice, come prima del passo.
   */
  @Test
  fun ilBackupSoloSulleShareDiProvaESpentoDalPassoUnicoENonSiCambiaDaFuori() {
    assertThat(FeatureFlags.RELEASE.backupTestSharesOnly).isFalse()
    for (debug in listOf(false, true)) {
      val flags = FeatureFlags.forBuild(debugBuild = debug, wol = true, nasLibrary = true, indexPublishTestSharesOnly = false)
      assertThat(flags.backupTestSharesOnly).isFalse()
      for (share in listOf("test", "test2", "TEST-comune", "alessio", "pampa", "NAS", "Public")) {
        assertWithMessage(share).that(flags.backupMayWriteTo(share)).isTrue()
      }
    }
    // Con la recinzione accesa: solo le share di prova scritte nel codice, mai una share di famiglia o pubblica.
    val fenced = FencedFlags.of()
    assertThat(fenced.backupTestSharesOnly).isTrue()
    for (share in listOf("test", "test2", "TEST-comune")) assertThat(fenced.backupMayWriteTo(share)).isTrue()
    for (share in listOf("alessio", "pampa", "NAS", "Public", "ｔｅｓｔ", "test ", "test3", "")) {
      assertWithMessage(share).that(fenced.backupMayWriteTo(share)).isFalse()
    }
    val source = flagsSource()
    val forBuild = Regex("""fun\s+forBuild\s*\(([^)]*)\)""").find(source)?.groupValues?.get(1) ?: error("forBuild non trovata")
    assertWithMessage("forBuild non deve poter cambiare D12").that(forBuild).doesNotContain("backup")
    assertWithMessage("D12 e' una costante spenta dal passo unico").that(source).contains("private const val BACKUP_TEST_SHARES_ONLY: Boolean = false")
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
    // D12 vale in debug come in release (dal passo unico spenta in entrambe): R2 non lo tocca.
    assertThat(debug.backupTestSharesOnly).isEqualTo(FeatureFlags.RELEASE.backupTestSharesOnly)
    assertThat(debug.backupMayWriteTo("test")).isTrue()
    assertThat(debug.backupMayWriteTo("alessio")).isTrue()
    // R2 resta accesa in release anche dopo il passo unico: un account di prova non scrive, nemmeno su una share che D12 lascerebbe.
    assertThat(FeatureFlags.RELEASE.backupMayWriteTo("alessio")).isTrue()
    assertThat(FeatureFlags.RELEASE.backupMayWriteAs("test")).isFalse()
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
   * Fase 7, D30: gli album condivisi solo fra le share di prova, **spento dal passo unico del 12/11/2026** in release e in debug (una costante
   * sola, cambiata con il consenso). Come D12, `forBuild` non ha il parametro: lo controlla la lettura del sorgente. Con la recinzione accesa
   * ([FencedFlags], solo nelle prove) la decisione lascia solo le share di prova.
   */
  @Test
  fun d30_gliAlbumCondivisiSoloSulleShareDiProvaSpentoDalPassoUnico() {
    assertThat(FeatureFlags.RELEASE.sharedAlbumsTestSharesOnly).isFalse()
    for (debug in listOf(false, true)) {
      val flags = FeatureFlags.forBuild(debugBuild = debug, wol = true, nasLibrary = true, indexPublishTestSharesOnly = false)
      assertThat(flags.sharedAlbumsTestSharesOnly).isFalse()
      for (share in listOf("test", "test-comune", "alessio", "Public", "NAS")) assertWithMessage(share).that(flags.sharedAlbumsMayUse(share)).isTrue()
    }
    val fenced = FencedFlags.of()
    for (share in listOf("test", "test2", "TEST-comune")) assertWithMessage(share).that(fenced.sharedAlbumsMayUse(share)).isTrue()
    for (share in listOf("alessio", "pampa", "NAS", "Public", "ｔｅｓｔ-comune", "test-comune ", "test3", "")) {
      assertWithMessage(share).that(fenced.sharedAlbumsMayUse(share)).isFalse()
    }
    val source = flagsSource()
    val forBuild = Regex("""fun\s+forBuild\s*\(([^)]*)\)""").find(source)?.groupValues?.get(1) ?: error("forBuild non trovata")
    assertWithMessage("forBuild non deve poter cambiare D30").that(forBuild).doesNotContain("haredAlbums")
    assertWithMessage("D30 e' una costante spenta dal passo unico").that(source).contains("private const val SHARED_ALBUMS_TEST_SHARES_ONLY: Boolean = false")
    // La release tiene la decisione (la regola R8 del modulo): tools/check.ps1 ci legge lo stato.
    val rules = File(repositoryRoot(), "core-common/consumer-rules.pro").readText()
    assertThat(rules).contains("public boolean getSharedAlbumsTestSharesOnly();")
    assertThat(rules).contains("public boolean sharedAlbumsMayUse(java.lang.String);")
  }

  /**
   * Fase 8, D34: i dati dei volti sul NAS solo nelle share di prova, **spento dal passo unico del 12/11/2026** in release e in debug (una
   * costante sola, cambiata con il consenso). Come D30, `forBuild` non ha il parametro e la regola R8 tiene la decisione nella release (il
   * controllo ci legge lo stato). Con la recinzione accesa ([FencedFlags], solo nelle prove) la decisione lascia solo le share di prova.
   */
  @Test
  fun d34_iVoltiSulNasSoloSulleShareDiProvaSpentoDalPassoUnico() {
    assertThat(FeatureFlags.RELEASE.facesSyncTestSharesOnly).isFalse()
    for (debug in listOf(false, true)) {
      val flags = FeatureFlags.forBuild(debugBuild = debug, wol = true, nasLibrary = true, indexPublishTestSharesOnly = false)
      assertThat(flags.facesSyncTestSharesOnly).isFalse()
      for (share in listOf("test", "TEST", "alessio", "pampa", "NAS")) assertWithMessage(share).that(flags.facesSyncMayUse(share)).isTrue()
    }
    val fenced = FencedFlags.of()
    for (share in listOf("test", "test2", "TEST")) assertWithMessage(share).that(fenced.facesSyncMayUse(share)).isTrue()
    for (share in listOf("alessio", "pampa", "NAS", "Public", "ｔｅｓｔ", "test ", "test3", "")) {
      assertWithMessage(share).that(fenced.facesSyncMayUse(share)).isFalse()
    }
    assertThat(FeatureFlags.RELEASE.toString()).contains("facesSyncTestSharesOnly=false")
    assertThat(fenced.toString()).contains("facesSyncTestSharesOnly=true")
    val source = flagsSource()
    val forBuild = Regex("""fun\s+forBuild\s*\(([^)]*)\)""").find(source)?.groupValues?.get(1) ?: error("forBuild non trovata")
    assertWithMessage("forBuild non deve poter cambiare D34").that(forBuild).doesNotContain("acesSync")
    assertWithMessage("D34 e' una costante spenta dal passo unico").that(source).contains("private const val FACES_SYNC_TEST_SHARES_ONLY: Boolean = false")
    val rules = File(repositoryRoot(), "core-common/consumer-rules.pro").readText()
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

  /**
   * Il passo unico del 12/11/2026 (D35): in [FeatureFlags.RELEASE] le quattro recinzioni "solo share di prova" sono spente insieme e il resto e'
   * com'era (R2 accesa, wol spento, NAS nella Libreria e mappa online accesi). Le decisioni lasciano scrivere le share vere; un account di prova
   * continua a non far scrivere il backup, e un account che non si conosce nemmeno (si fallisce chiusi).
   */
  @Test
  fun passoUnico_leQuattroRecinzioniSpenteInsiemeERestaR2() {
    val release = FeatureFlags.RELEASE
    assertThat(release.indexPublishTestSharesOnly).isFalse()
    assertThat(release.backupTestSharesOnly).isFalse()
    assertThat(release.sharedAlbumsTestSharesOnly).isFalse()
    assertThat(release.facesSyncTestSharesOnly).isFalse()
    assertThat(release.backupBlocksTestAccounts).isTrue()
    assertThat(release.wol).isFalse()
    assertThat(release.nasLibrary).isTrue()
    assertThat(release.mapOnline).isTrue()
    for (share in listOf("alessio", "Public")) {
      assertWithMessage(share).that(release.backupMayWriteTo(share)).isTrue()
      assertWithMessage(share).that(release.sharedAlbumsMayUse(share)).isTrue()
      assertWithMessage(share).that(release.facesSyncMayUse(share)).isTrue()
    }
    for (account in listOf("test", "test2", "TEST")) assertWithMessage(account).that(release.backupMayWriteAs(account)).isFalse()
    assertThat(release.backupMayWriteAs("alessio")).isTrue()
    assertThat(release.backupMayWriteAs(null)).isFalse()
  }

  /** L'aiutante delle prove ([FencedFlags]) cambia solo le recinzioni chieste: il resto e' quello della base. */
  @Test
  fun leRecinzioniAcceseSoloNelleProveCambianoSoloLeRecinzioni() {
    val all = FencedFlags.of()
    assertThat(all.toString()).isEqualTo(
      FeatureFlags.RELEASE.toString()
        .replace("indexPublishTestSharesOnly=false", "indexPublishTestSharesOnly=true")
        .replace("backupTestSharesOnly=false", "backupTestSharesOnly=true")
        .replace("sharedAlbumsTestSharesOnly=false", "sharedAlbumsTestSharesOnly=true")
        .replace("facesSyncTestSharesOnly=false", "facesSyncTestSharesOnly=true"),
    )
    assertThat(all).isNotEqualTo(FeatureFlags.RELEASE)
    val onlyBackup = FencedFlags.of(index = false, albums = false, faces = false)
    assertThat(onlyBackup.backupTestSharesOnly).isTrue()
    assertThat(onlyBackup.indexPublishTestSharesOnly).isFalse()
    assertThat(onlyBackup.sharedAlbumsTestSharesOnly).isFalse()
    assertThat(onlyBackup.facesSyncTestSharesOnly).isFalse()
    val debug = FencedFlags.of(FeatureFlags.forBuild(debugBuild = true, nasLibrary = true))
    assertThat(debug.backupBlocksTestAccounts).isFalse()
    assertThat(debug.nasLibrary).isTrue()
    assertThat(debug.backupTestSharesOnly).isTrue()
  }

  private fun repositoryRoot(): File = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle").isFile }

  private fun flagsSource(): String =
    File(repositoryRoot(), "core-common/src/main/kotlin/dev/pampa/mycloudgallery/core/common/featureflags/FeatureFlags.kt").readText()
}
