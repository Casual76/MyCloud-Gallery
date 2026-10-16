package dev.pampa.mycloudgallery.core.backup.plan

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.time.LocalDate
import java.util.Random
import org.junit.Test

/**
 * Le regole dei percorsi del backup (Fase 5A, P4; `docs/fase5-progetto.md` D6, Q8, E-B17, E-N6, E-P6): un esempio per caso, piu' una
 * prova a campione (nomi qualunque) che il risultato sia sempre un nome che `SharePath` accetta e sempre lo stesso.
 */
class BackupPathsTest {

  private val device = DeviceFolder("S25 di Mario")

  /** Mezzanotte UTC di un giorno, in millisecondi dall'epoca (un'ora del NAS). */
  private fun midnight(year: Int, month: Int, day: Int): Long = LocalDate.of(year, month, day).toEpochDay() * 86_400_000L

  /** 16/10/2026 00:00 UTC: l'ora del NAS dei fake. Domani = 17/10/2026. */
  private val nasNow = 1_792_108_800_000L
  private val latest = BackupPaths.tomorrowOf(nasNow)

  private fun path(
    source: BackupSource = BackupSource.Camera,
    day: Int? = 20261012,
    name: String = "IMG_1.jpg",
    latestDay: Int? = latest,
    fenceRoot: SharePath? = null,
    folder: DeviceFolder = device,
  ): String = BackupPaths.destination(folder, source, day, latestDay, name, fenceRoot).value

  // --- La destinazione: un esempio per caso -------------------------------------------------------------------------------

  @Test
  fun fotocamera_AAAA_MM_sottoLaCartellaDelDispositivo() {
    assertThat(path()).isEqualTo("Backup/S25 di Mario/2026/10/IMG_1.jpg")
    assertThat(path(day = 20240229)).isEqualTo("Backup/S25 di Mario/2024/02/IMG_1.jpg") // 29 febbraio di un anno bisestile
    assertThat(path(day = 19900101)).isEqualTo("Backup/S25 di Mario/1990/01/IMG_1.jpg") // il primo giorno credibile
  }

  @Test
  fun screenshot_inScreenshot_AAAA_MM_Q8() {
    assertThat(path(BackupSource.Screenshots, name = "Screenshot_20261012.png"))
      .isEqualTo("Backup/S25 di Mario/Screenshot/2026/10/Screenshot_20261012.png")
  }

  @Test
  fun cartelleAggiuntive_inUnaCartellaConIlLoroNome_Q8() {
    val telegram = BackupSource.Folder("external_primary", "Pictures/Telegram/")
    val whatsapp = BackupSource.Folder("external_primary", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/")
    val quickShare = BackupSource.Folder("external_primary", "Download/Quick Share")

    assertThat(path(telegram)).isEqualTo("Backup/S25 di Mario/Telegram/2026/10/IMG_1.jpg")
    assertThat(path(whatsapp)).isEqualTo("Backup/S25 di Mario/WhatsApp Images/2026/10/IMG_1.jpg")
    assertThat(path(quickShare)).isEqualTo("Backup/S25 di Mario/Quick Share/2026/10/IMG_1.jpg") // anche senza barra finale
  }

  @Test
  fun microSD_E_P6_hailSuoVolume_esottoMicroSD() {
    val card = BackupSource.Folder("1A2B-3C4D", "DCIM/Camera/")
    val cardAlbum = BackupSource.Folder("1A2B-3C4D", "Pictures/Vacanze/")

    assertThat(path(card)).isEqualTo("Backup/S25 di Mario/microSD/Camera/2026/10/IMG_1.jpg")
    assertThat(path(cardAlbum)).isEqualTo("Backup/S25 di Mario/microSD/Vacanze/2026/10/IMG_1.jpg")
    // La stessa cartella sul volume principale non si mescola con quella della scheda.
    assertThat(path(BackupSource.Folder("external_primary", "DCIM/Camera/"))).isEqualTo("Backup/S25 di Mario/Camera/2026/10/IMG_1.jpg")
  }

  @Test
  fun nomiDiCartellaCheSharePathRifiuta_siRipuliscono() {
    assertThat(path(BackupSource.Folder("external_primary", "Pictures/CON/"))).isEqualTo("Backup/S25 di Mario/_CON/2026/10/IMG_1.jpg")
    assertThat(path(BackupSource.Folder("external_primary", "Pictures/Foo:Bar/"))).isEqualTo("Backup/S25 di Mario/Foo_Bar/2026/10/IMG_1.jpg")
    // Nessun nome utilizzabile: "Altro".
    assertThat(path(BackupSource.Folder("external_primary", "/"))).isEqualTo("Backup/S25 di Mario/Altro/2026/10/IMG_1.jpg")
  }

  @Test
  fun conLaRecinzioneDiProva_laRadiceECampo_D13() {
    assertThat(path(fenceRoot = SharePath.of("mcg-campo"))).isEqualTo("mcg-campo/Backup/S25 di Mario/2026/10/IMG_1.jpg")
    assertThat(BackupPaths.root().value).isEqualTo("Backup")
    assertThat(BackupPaths.root(SharePath.of("mcg-campo")).value).isEqualTo("mcg-campo/Backup")
  }

  // --- Data assente o assurda: Senza data (E-B17) ---------------------------------------------------------------------------

  @Test
  fun dataAssente_SenzaData() {
    assertThat(path(day = null)).isEqualTo("Backup/S25 di Mario/Senza data/IMG_1.jpg")
    // Anche per screenshot e cartelle aggiuntive: dentro la loro cartella.
    assertThat(path(BackupSource.Screenshots, day = null)).isEqualTo("Backup/S25 di Mario/Screenshot/Senza data/IMG_1.jpg")
    assertThat(path(BackupSource.Folder("external_primary", "Pictures/Telegram/"), day = null))
      .isEqualTo("Backup/S25 di Mario/Telegram/Senza data/IMG_1.jpg")
  }

  @Test
  fun dataPrimaDel1990_SenzaData_maiUnaCartella1979() {
    for (day in listOf(19790101, 19700101, 19891231, 10101, 0, -20261012)) {
      assertWithMessage("$day").that(path(day = day)).isEqualTo("Backup/S25 di Mario/Senza data/IMG_1.jpg")
    }
  }

  @Test
  fun dataImpossibile_SenzaData() {
    // Un mese 13, un giorno 0, un 31 febbraio, un 29 febbraio di un anno non bisestile, un giorno 32.
    for (day in listOf(20261301, 20261000, 20260231, 20250229, 20261032, 20260001)) {
      assertWithMessage("$day").that(path(day = day)).isEqualTo("Backup/S25 di Mario/Senza data/IMG_1.jpg")
    }
  }

  @Test
  fun oltreDomani_conLOraDelNas_SenzaData_domaniEOggiNo() {
    assertThat(latest).isEqualTo(20261017)
    assertThat(path(day = 20261016)).isEqualTo("Backup/S25 di Mario/2026/10/IMG_1.jpg") // oggi
    assertThat(path(day = 20261017)).isEqualTo("Backup/S25 di Mario/2026/10/IMG_1.jpg") // domani: un altro fuso, e' credibile
    assertThat(path(day = 20261018)).isEqualTo("Backup/S25 di Mario/Senza data/IMG_1.jpg") // oltre domani
    assertThat(path(day = 20300101)).isEqualTo("Backup/S25 di Mario/Senza data/IMG_1.jpg")
  }

  @Test
  fun domani_siCalcolaDalGiornoUtcDelNas_ancheAlConfineDelGiornoEDellAnno() {
    val endOfDay = nasNow + 86_399_999L // 16/10/2026 23:59:59.999 UTC
    assertThat(BackupPaths.tomorrowOf(endOfDay)).isEqualTo(20261017)
    assertThat(BackupPaths.tomorrowOf(endOfDay + 1)).isEqualTo(20261018)
    assertThat(BackupPaths.tomorrowOf(midnight(2026, 12, 31))).isEqualTo(20270101) // cambio d'anno
    assertThat(BackupPaths.tomorrowOf(midnight(2026, 12, 30))).isEqualTo(20261231)
    assertThat(BackupPaths.tomorrowOf(midnight(2024, 2, 28))).isEqualTo(20240229) // anno bisestile
    assertThat(BackupPaths.tomorrowOf(midnight(2025, 2, 28))).isEqualTo(20250301)
    assertThat(BackupPaths.tomorrowOf(-1L)).isEqualTo(19700101) // un istante prima dell'epoca: 31/12/1969 -> 1/1/1970
  }

  @Test
  fun senzaLOraDelNas_nessunOrologio_siScartanoSoloLeDateChePossonoEsserlo() {
    // Mai l'orologio del telefono: senza l'ora del NAS vale una regola che non dipende da nessun orologio.
    assertThat(path(day = 20261018, latestDay = null)).isEqualTo("Backup/S25 di Mario/2026/10/IMG_1.jpg")
    assertThat(path(day = 21001231, latestDay = null)).isEqualTo("Backup/S25 di Mario/2100/12/IMG_1.jpg")
    assertThat(path(day = 21010101, latestDay = null)).isEqualTo("Backup/S25 di Mario/Senza data/IMG_1.jpg")
    // Il resto (prima del 1990, date impossibili) vale come sempre.
    assertThat(path(day = 19790101, latestDay = null)).isEqualTo("Backup/S25 di Mario/Senza data/IMG_1.jpg")
    assertThat(path(day = 20261301, latestDay = null)).isEqualTo("Backup/S25 di Mario/Senza data/IMG_1.jpg")
  }

  // --- I nomi dei file (E-N6) --------------------------------------------------------------------------------------------------

  @Test
  fun nomiChiSharePathAccetta_restanoIdentici() {
    for (name in listOf("IMG_1.jpg", "Foto è già così.heic", "写真 2026.jpg", "🎉 Compleanno 😀.mp4", "a b  c.png", ".nomedia", "x".repeat(251) + ".jpg")) {
      assertThat(SharePath.isValidComponent(name)).isTrue()
      assertWithMessage(name).that(BackupPaths.fileName(name)).isEqualTo(name)
    }
  }

  @Test
  fun nomiRifiutatiDaSharePath_siRipulisconoSenzaPerdereIlFile() {
    val cases =
      mapOf(
        "CON.jpg" to "_CON.jpg", // nome riservato di Windows, anche con l'estensione
        "nul" to "_nul",
        "COM1.mp4" to "_COM1.mp4",
        "a:b.jpg" to "a_b.jpg",
        "IMG<1>|2?.jpg" to "IMG_1__2_.jpg",
        "dir\\file.jpg" to "dir_file.jpg",
        "dir/file.jpg" to "dir_file.jpg",
        "photo." to "photo",
        "photo.jpg  " to "photo.jpg",
        "photo ​.jpg" to "photo _.jpg", // spazio a larghezza zero (invisibile)
        "a\u0000b.jpg" to "a_b.jpg", // carattere di controllo
        "a‮b.jpg" to "a_b.jpg", // inversione bidirezionale (nomi che mentono)
        "a／b.jpg" to "a_b.jpg", // barra a larghezza piena: in forma NFKC sarebbe un separatore
        "a：b.jpg" to "a_b.jpg", // due punti a larghezza piena
        "foto．" to "foto", // punto finale a larghezza piena
        "foto　" to "foto", // spazio ideografico finale
        "\uD800x.jpg" to "_x.jpg", // surrogato spaiato
      )
    for ((raw, expected) in cases) {
      assertThat(SharePath.isValidComponent(raw)).isFalse()
      val clean = BackupPaths.fileName(raw)
      assertWithMessage(raw).that(clean).isEqualTo(expected)
      assertWithMessage(raw).that(SharePath.isValidComponent(clean)).isTrue()
    }
  }

  @Test
  fun nomiSenzaNulla_diventanoFile() {
    for (raw in listOf("", ".", "..", "...", "   ", ". .")) {
      assertWithMessage("'$raw'").that(BackupPaths.fileName(raw)).isEqualTo(BackupPaths.FALLBACK_FILE)
    }
  }

  @Test
  fun nomiTroppoLunghi_siAccorcianoAUnConfineDiCarattere_tenendoLEstensione() {
    val long = "a".repeat(300) + ".jpg"
    val clean = BackupPaths.fileName(long)
    assertThat(clean).endsWith(".jpg")
    assertThat(clean.toByteArray(Charsets.UTF_8).size).isAtMost(TrustLimits.MAX_COMPONENT_UTF8_BYTES)
    assertThat(clean).isEqualTo("a".repeat(251) + ".jpg")

    // Caratteri a piu' byte (accenti: 2, emoji: 4 e due unita' UTF-16): mai spezzati a meta'.
    for (raw in listOf("è".repeat(200) + ".jpg", "😀".repeat(100) + ".mp4", "写".repeat(100) + ".heic")) {
      val cut = BackupPaths.fileName(raw)
      assertWithMessage(raw.take(4)).that(SharePath.isValidComponent(cut)).isTrue()
      assertThat(cut.toByteArray(Charsets.UTF_8).size).isAtMost(TrustLimits.MAX_COMPONENT_UTF8_BYTES)
      assertThat(cut.substringAfterLast('.')).isEqualTo(raw.substringAfterLast('.'))
    }
    // Troppo lungo e riservato: il prefisso non deve far sforare i 255 byte.
    val reserved = "CON" + ".".padEnd(300, 'x')
    val cutReserved = BackupPaths.fileName(reserved)
    assertThat(SharePath.isValidComponent(cutReserved)).isTrue()
    assertThat(cutReserved.toByteArray(Charsets.UTF_8).size).isAtMost(TrustLimits.MAX_COMPONENT_UTF8_BYTES)
  }

  @Test
  fun ilNomeRipulito_ePuroEStabile_lostessoFileDaSempreLaStessaDestinazione() {
    val random = Random(20261016)
    val alphabet =
      "abcXYZ019 ._-:/\\*?\"<>|\u0000\u0007​‮　．／：😀é写CONNULcom1 𐀀".toCharArray()
    repeat(5_000) {
      val raw = buildString { repeat(1 + random.nextInt(40)) { append(alphabet[random.nextInt(alphabet.size)]) } }
      val clean = BackupPaths.fileName(raw)
      assertWithMessage("'$raw'").that(SharePath.isValidComponent(clean)).isTrue()
      assertWithMessage("'$raw'").that(BackupPaths.fileName(raw)).isEqualTo(clean) // deterministico
      assertWithMessage("'$raw'").that(BackupPaths.fileName(clean)).isEqualTo(clean) // idempotente
      if (SharePath.isValidComponent(raw)) assertWithMessage("'$raw'").that(clean).isEqualTo(raw) // cio' che va bene non si tocca
    }
  }

  @Test
  fun unaDestinazioneConUnNomeOstile_siCostruisceComunque_nonFaSaltareIlFile() {
    for (name in listOf("CON.jpg", "a:b.jpg", "..", "x".repeat(400) + ".heic", "‮exe.gpj")) {
      val destination = BackupPaths.destination(device, BackupSource.Camera, 20261012, latest, name)
      assertThat(destination.components.size).isEqualTo(5)
      assertThat(destination.components.take(4)).containsExactly("Backup", "S25 di Mario", "2026", "10").inOrder()
    }
  }
}
