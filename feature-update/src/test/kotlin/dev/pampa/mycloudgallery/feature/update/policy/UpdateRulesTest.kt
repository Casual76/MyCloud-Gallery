package dev.pampa.mycloudgallery.feature.update.policy

import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.foundation.compareVersions
import dev.antigravity.fluidengine.foundation.isStableVersionNewer
import dev.pampa.mycloudgallery.feature.update.source.UpdateSourceConfig
import org.junit.Test

/**
 * Pacchetto U: le regole dell'app sopra l'engine (C480-C489). Ogni controllo di sicurezza ha qui una prova che cade se lo si toglie (mutazioni, con i
 * comandi, in docs/aggiornamenti-in-app.md, «Prove e mutazioni»).
 */
class UpdateRulesTest {

  private val sha = "a".repeat(64)

  /** Un aggiornamento con la forma che `EngineAppUpdater.check` costruisce da un elenco di `publisher.py` (tag `<canale>-<app-id>-v<versione>`). */
  private fun offered(
    version: String = "1.1.0",
    tag: String = "stable-mycloud-gallery-v$version",
    asset: String = "mycloud-gallery-$version.apk",
    url: String = UpdateSourceConfig.downloadUrl(tag, asset),
    size: Long = 115_000_000L,
    sha256: String = sha,
    notes: String = "",
  ) = AvailableAppUpdate(version = version, changelog = notes, releaseTag = tag, apkAsset = asset, downloadUrl = url, sizeBytes = size, sha256 = sha256)

  private val installed = PackageFacts("dev.pampa.mycloudgallery", versionCode = 5, versionName = "1.1.0-beta.02", signers = setOf("ab".repeat(32)))

  // --- L'offerta ------------------------------------------------------------------------------------------------------------------------------------

  @Test
  fun unAggiornamentoBenPubblicatoPassa() {
    assertThat(UpdateRules.offerProblem(offered())).isNull()
    // I nomi che scrive publisher.py con --app-id mycloud-gallery, anche per una beta.
    assertThat(UpdateRules.offerProblem(offered(version = "1.1.0-beta.03", tag = "beta-mycloud-gallery-v1.1.0-beta.03"))).isNull()
  }

  @Test
  fun senzaSha256NonSiScarica() {
    assertThat(UpdateRules.offerProblem(offered(sha256 = ""))).isEqualTo(UpdateProblem.CHECKSUM_MISSING)
    assertThat(UpdateRules.offerProblem(offered(sha256 = "   "))).isEqualTo(UpdateProblem.CHECKSUM_MISSING)
    assertThat(UpdateRules.offerProblem(offered(sha256 = "abc123"))).isEqualTo(UpdateProblem.CHECKSUM_MISSING)
    assertThat(UpdateRules.offerProblem(offered(sha256 = "g".repeat(64)))).isEqualTo(UpdateProblem.CHECKSUM_MISSING)
  }

  @Test
  fun senzaPesoNonSiScaricaEOltreIlTettoNemmeno() {
    assertThat(UpdateRules.offerProblem(offered(size = 0))).isEqualTo(UpdateProblem.SIZE_MISSING)
    assertThat(UpdateRules.offerProblem(offered(size = -1))).isEqualTo(UpdateProblem.SIZE_MISSING)
    assertThat(UpdateRules.offerProblem(offered(size = UpdateSourceConfig.MAX_APK_BYTES + 1))).isEqualTo(UpdateProblem.TOO_LARGE)
    assertThat(UpdateRules.offerProblem(offered(size = UpdateSourceConfig.MAX_APK_BYTES))).isNull()
  }

  @Test
  fun versioniFuoriFormaNonSiOffrono() {
    // US-3: la forma ammessa e' corta ed esplicita; tutto il resto (anche cio' che il confronto dell'engine metterebbe in un ordine sbagliato) no.
    for (version in listOf("1.0.0", "0.9.12", "12.3.4", "1.1.0-beta.01", "1.1.0-beta.99", "9999.9999.9999")) {
      assertThat(UpdateRules.VERSION.matches(version)).isTrue()
    }
    val bad = listOf(
      "v1.1.0", "1.1.0+build.5", "1.1.0.0", "1.1", "01.1.0", "1.1.0-beta.3", "1.1.0-beta.100", "1.1.0-rc.01", "1.1.0-beta", " 1.1.0",
      "1.1.0 ", "1.1.0‮", "1.1.0\n", "10000.0.0", "1.1.0-beta.01-x", "9".repeat(400),
    )
    for (version in bad) {
      assertThat(UpdateRules.offerProblem(offered(version = version, tag = "stable-mycloud-gallery-v1.1.0", asset = "mycloud-gallery-1.1.0.apk")))
        .isEqualTo(UpdateProblem.BAD_VERSION)
    }
  }

  @Test
  fun nomiConPercorsiOCaratteriStraniNonPassano() {
    for (asset in listOf("../../files/x.apk", "dir/x.apk", "x .apk", "x.zip", "..apk", "", ".hidden.apk")) {
      assertThat(UpdateRules.offerProblem(offered(asset = asset))).isEqualTo(UpdateProblem.BAD_NAME)
    }
    for (tag in listOf("v1/../../other", "", "a b", "../x")) {
      assertThat(UpdateRules.offerProblem(offered(tag = tag))).isEqualTo(UpdateProblem.BAD_NAME)
    }
  }

  @Test
  fun lIndirizzoDeveEssereQuelloDelRepositoryDelleRelease() {
    val other = "https://github.com/qualcuno/altro/releases/download/stable-mycloud-gallery-v1.1.0/mycloud-gallery-1.1.0.apk"
    assertThat(UpdateRules.offerProblem(offered(url = other))).isEqualTo(UpdateProblem.OTHER_SOURCE)
    val http = offered().downloadUrl.replace("https://", "http://")
    assertThat(UpdateRules.offerProblem(offered(url = http))).isEqualTo(UpdateProblem.OTHER_SOURCE)
  }

  // --- Le note --------------------------------------------------------------------------------------------------------------------------------------

  @Test
  fun leNoteSiTaglianoPrimaEPerdonoIControlliEIlBidi() {
    // Il taglio viene prima di ogni trasformazione: un elenco con mezzo megabyte di note ne passa al piu' MAX_NOTES_CHARS.
    val huge = "x".repeat(500_000)
    assertThat(UpdateRules.cleanNotes(huge)).hasLength(UpdateRules.MAX_NOTES_CHARS)
    // Via i caratteri che girano o nascondono il testo, e i controlli; l'a capo resta, il tab diventa uno spazio.
    val tricky = "Novità\n- Album più veloci‮.kpa‬​⁦x⁩\u0007\u0000\tok"
    val clean = UpdateRules.cleanNotes(tricky)
    assertThat(clean).isEqualTo("Novità\n- Album più veloci.kpax ok")
    for (ch in listOf('‮', '‬', '​', '⁦', '⁩', '\u0007', '\u0000')) assertThat(clean).doesNotContain(ch.toString())
    // L'offerta pulita tiene il resto com'e', con lo sha256 in minuscolo.
    val cleaned = UpdateRules.cleanOffer(offered(sha256 = "A".repeat(64), notes = tricky))
    assertThat(cleaned.changelog).isEqualTo(clean)
    assertThat(cleaned.sha256).isEqualTo(sha)
    assertThat(cleaned.version).isEqualTo("1.1.0")
  }

  // --- Il file scaricato ---------------------------------------------------------------------------------------------------------------------------

  @Test
  fun loSha256DelFileDeveEssereQuelloDellElenco() {
    assertThat(UpdateRules.checksumProblem(sha, sha)).isNull()
    assertThat(UpdateRules.checksumProblem(sha.uppercase(), " $sha ")).isNull()
    assertThat(UpdateRules.checksumProblem(sha, "b".repeat(64))).isEqualTo(UpdateProblem.CHECKSUM_MISMATCH)
    assertThat(UpdateRules.checksumProblem(sha, "")).isEqualTo(UpdateProblem.CHECKSUM_MISMATCH)
  }

  @Test
  fun unApkIllegibileODiUnAltraAppNonSiInstalla() {
    assertThat(UpdateRules.archiveProblem(installed, null)).isEqualTo(UpdateProblem.UNREADABLE)
    assertThat(UpdateRules.archiveProblem(installed, installed.copy(packageName = "com.altra.app", versionCode = 9)))
      .isEqualTo(UpdateProblem.OTHER_APP)
  }

  @Test
  fun laVersioneDellApkDeveEssereQuellaAnnunciata() {
    val newer = installed.copy(versionCode = 6, versionName = "1.1.0")
    assertThat(UpdateRules.archiveProblem(installed, newer, expectedVersionName = "1.1.0")).isNull()
    assertThat(UpdateRules.archiveProblem(installed, newer, expectedVersionName = "1.2.0")).isEqualTo(UpdateProblem.WRONG_VERSION)
    // Come l'engine: un APK senza versionName non e' un motivo di rifiuto (lo decide il versionCode).
    assertThat(UpdateRules.archiveProblem(installed, newer.copy(versionName = null), expectedVersionName = "1.2.0")).isNull()
  }

  @Test
  fun versionCodeStrettamenteMaggiore_nienteRitorniIndietro() {
    assertThat(UpdateRules.archiveProblem(installed, installed.copy(versionCode = 5))).isEqualTo(UpdateProblem.NOT_NEWER)
    assertThat(UpdateRules.archiveProblem(installed, installed.copy(versionCode = 4))).isEqualTo(UpdateProblem.NOT_NEWER)
    assertThat(UpdateRules.archiveProblem(installed, installed.copy(versionCode = 6))).isNull()
  }

  @Test
  fun laFirmaDeveEssereQuellaDellAppInstallata() {
    val other = installed.copy(versionCode = 6, signers = setOf("cd".repeat(32)))
    assertThat(UpdateRules.archiveProblem(installed, other)).isEqualTo(UpdateProblem.OTHER_SIGNATURE)
    val more = installed.copy(versionCode = 6, signers = installed.signers + "cd".repeat(32))
    assertThat(UpdateRules.archiveProblem(installed, more)).isEqualTo(UpdateProblem.OTHER_SIGNATURE)
    assertThat(UpdateRules.archiveProblem(installed, installed.copy(versionCode = 6, signers = emptySet()))).isEqualTo(UpdateProblem.OTHER_SIGNATURE)
    assertThat(UpdateRules.archiveProblem(installed.copy(signers = emptySet()), installed.copy(versionCode = 6, signers = emptySet())))
      .isEqualTo(UpdateProblem.OTHER_SIGNATURE)
    // La stessa impronta scritta in maiuscolo e' la stessa firma.
    assertThat(UpdateRules.archiveProblem(installed, installed.copy(versionCode = 6, signers = setOf("AB".repeat(32))))).isNull()
  }

  @Test
  fun riprovaSoloDoveServe_eIlFileRestaSoloPerIGuastiDiAndroid() {
    // UU-6: la rete, il file rovinato, lo spazio e cio' che non si riconosce si riprovano; il resto fallirebbe uguale.
    assertThat(UpdateProblem.entries.filter { it.retryable })
      .containsExactly(UpdateProblem.DOWNLOAD_FAILED, UpdateProblem.CHECKSUM_MISMATCH, UpdateProblem.NO_SPACE, UpdateProblem.INSTALL_FAILED)
    assertThat(UpdateProblem.entries.filter { it.keepsFile }).containsExactly(UpdateProblem.NO_SPACE, UpdateProblem.INSTALL_FAILED)
  }

  // --- Quando si controlla e quando si avvisa -------------------------------------------------------------------------------------------------------

  @Test
  fun alPiuUnaVoltaOgni24Ore() {
    val day = UpdateSourceConfig.CHECK_INTERVAL_MILLIS
    val now = 1_000_000_000_000L
    assertThat(UpdateRules.isDue(null, now)).isTrue()
    assertThat(UpdateRules.isDue(now - day + 1, now)).isFalse()
    assertThat(UpdateRules.isDue(now - day, now)).isTrue()
    // L'orologio del telefono e' tornato indietro: l'ora salvata e' nel futuro, si controlla (altrimenti mai piu' fino a quel giorno).
    assertThat(UpdateRules.isDue(now + 5 * day, now)).isTrue()
  }

  @Test
  fun dopoRimandaDiTreGiorni() {
    val now = 1_000_000_000_000L
    val snooze = UpdateRules.SNOOZE_MILLIS
    assertThat(UpdateRules.shouldPrompt(hasOffer = false, now = now, snoozedUntil = null, declines = 0)).isFalse()
    assertThat(UpdateRules.shouldPrompt(hasOffer = true, now = now, snoozedUntil = null, declines = 0)).isTrue()
    // «Dopo»: rimandato di tre giorni, per qualunque versione (chi pubblica due nomi a giorni alterni non lo fa tornare ogni giorno, US-3).
    assertThat(UpdateRules.declined(now, snoozedUntil = null, declines = 0)).isEqualTo(UpdateRules.Snooze(now + snooze, 1))
    assertThat(UpdateRules.shouldPrompt(hasOffer = true, now = now + 1, snoozedUntil = now + snooze, declines = 1)).isFalse()
    assertThat(UpdateRules.shouldPrompt(hasOffer = true, now = now + snooze, snoozedUntil = now + snooze, declines = 1)).isTrue()
    // L'orologio e' tornato indietro di piu' del rimando: vale come scaduto.
    assertThat(UpdateRules.shouldPrompt(hasOffer = true, now = now, snoozedUntil = now + 2 * snooze, declines = 1)).isTrue()
  }

  @Test
  fun alTerzoDopo_trentaGiorniDiSilenzio_poiIlContoRiparte_maiPerSempre() {
    // Giro 2 (decisione dell'orchestratore, al posto di «mai piu'»): tre avvisi al piu' in circa 39 giorni, e la voce in cima alle Impostazioni resta.
    val start = 1_000_000_000_000L
    val snooze = UpdateRules.SNOOZE_MILLIS
    val quiet = UpdateRules.QUIET_MILLIS
    var now = start
    var until: Long? = null
    var declines = 0
    // Tre «Dopo», ognuno appena l'avviso torna.
    val waits = mutableListOf<Long>()
    repeat(UpdateRules.MAX_DECLINES) {
      val next = UpdateRules.declined(now, until, declines)
      waits += next.until - now
      until = next.until
      declines = next.declines
      now = next.until
    }
    assertThat(waits).containsExactly(snooze, snooze, quiet).inOrder()
    assertThat(declines).isEqualTo(UpdateRules.MAX_DECLINES)
    // Il silenzio: un attimo prima dei trenta giorni no, allo scadere si'.
    val silenceStart = until!! - quiet
    assertThat(UpdateRules.shouldPrompt(hasOffer = true, now = silenceStart + quiet - 1, snoozedUntil = until, declines = declines)).isFalse()
    assertThat(UpdateRules.shouldPrompt(hasOffer = true, now = until!!, snoozedUntil = until, declines = declines)).isTrue()
    // Il conto riparte: il primo «Dopo» dopo il silenzio vale di nuovo tre giorni.
    assertThat(UpdateRules.declined(until!!, until, declines)).isEqualTo(UpdateRules.Snooze(until!! + snooze, 1))
    // Mai spento per sempre: dieci giri di «Dopo», l'avviso torna sempre.
    var t = until!!
    var u: Long? = until
    var d = declines
    repeat(10) {
      assertThat(UpdateRules.shouldPrompt(hasOffer = true, now = t, snoozedUntil = u, declines = d)).isTrue()
      val next = UpdateRules.declined(t, u, d)
      assertThat(next.until - t).isAtMost(quiet)
      u = next.until
      d = next.declines
      t = next.until
    }
  }

  @Test
  fun dentroIlSilenzio_unDopoNonLoAccorcia_eLOrologioIndietroNonLoAllunga() {
    val now = 1_000_000_000_000L
    val quiet = UpdateRules.QUIET_MILLIS
    // Un «Dopo» sull'avviso del permesso o di un guasto, durante i trenta giorni: resta il silenzio com'era.
    val inside = UpdateRules.declined(now + 1, snoozedUntil = now + quiet, declines = UpdateRules.MAX_DECLINES)
    assertThat(inside).isEqualTo(UpdateRules.Snooze(now + quiet, UpdateRules.MAX_DECLINES))
    // Un rimando che scade oltre i trenta giorni da adesso: l'orologio e' tornato indietro, vale come scaduto.
    assertThat(UpdateRules.shouldPrompt(hasOffer = true, now = now, snoozedUntil = now + quiet + 1, declines = UpdateRules.MAX_DECLINES)).isTrue()
    assertThat(UpdateRules.shouldPrompt(hasOffer = true, now = now, snoozedUntil = now + quiet, declines = UpdateRules.MAX_DECLINES)).isFalse()
  }

  @Test
  fun unTentativoSiRiprendeSoloSeEDiPocoFa() {
    val now = 1_000_000_000_000L
    assertThat(UpdateRules.attemptFresh(now - 1_000, now)).isTrue()
    assertThat(UpdateRules.attemptFresh(now - UpdateRules.ATTEMPT_TTL_MILLIS - 1, now)).isFalse()
    assertThat(UpdateRules.attemptFresh(now + 1_000, now)).isFalse()
  }

  // --- La numerazione delle versioni con le regole dell'engine (docs/aggiornamenti-in-app.md, «Le versioni») ----------------------------------------

  @Test
  fun laBetaConIlVersionNameInteroVedeLaStabileOmonima() {
    // La versione corrente e' il versionName INTERO: una 1.1.0-beta.02 riceve la 1.1.0.
    assertThat(isStableVersionNewer("1.1.0", "1.1.0-beta.02")).isTrue()
    // Tagliato il suffisso (come nel KDoc del controllore dell'engine) non la vedrebbe mai: la trappola che qui si evita.
    assertThat(isStableVersionNewer("1.1.0", "1.1.0-beta.02".substringBefore("-"))).isFalse()
    // Il canale Stabile non propone mai una versione con il trattino.
    assertThat(isStableVersionNewer("1.2.0-beta.01", "1.1.0")).isFalse()
  }

  @Test
  fun lePreReleaseSiConfrontanoComeTesto_dueCifreDallInizio() {
    // La trappola di `compareVersions` (engine-foundation): beta.10 viene prima di beta.9. Per questo la forma ammessa vuole due cifre.
    assertThat(compareVersions("1.1.0-beta.10", "1.1.0-beta.9")).isLessThan(0)
    assertThat(compareVersions("1.1.0-beta.10", "1.1.0-beta.09")).isGreaterThan(0)
    assertThat(compareVersions("1.1.0-beta.02", "1.1.0-beta.01")).isGreaterThan(0)
    // La prima beta di oggi si chiama 1.0.0 (versionCode 1, canale beta): una «1.0.0-beta.NN» sembrerebbe un passo indietro. La prossima e'
    // 1.1.0-beta.01 (versionCode 2), che il canale Beta vede.
    assertThat(compareVersions("1.0.0-beta.02", "1.0.0")).isLessThan(0)
    assertThat(compareVersions("1.1.0-beta.01", "1.0.0")).isGreaterThan(0)
    assertThat(UpdateRules.VERSION.matches("1.1.0-beta.01")).isTrue()
  }
}
