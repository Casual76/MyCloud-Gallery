package dev.pampa.mycloudgallery.core.metadata

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.metadata.contract.FileNameDate
import dev.pampa.mycloudgallery.core.metadata.contract.FileNameDates
import dev.pampa.mycloudgallery.core.metadata.contract.FileNamePattern
import dev.pampa.mycloudgallery.core.metadata.contract.SourceClassifier
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.MediaTrait
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Test

/** La tabella dei nomi dei file (§2.2) e la classificazione delle sorgenti. */
class FileNameAndSourceTest {

  private fun parsed(name: String): FileNameDate = FileNameDates.parse(name) ?: error("nome non riconosciuto: $name")

  private fun check(name: String, pattern: FileNamePattern, date: String, time: String?, utc: Boolean = false) {
    val p = parsed(name)
    assertThat(p.pattern).isEqualTo(pattern)
    assertThat(p.date).isEqualTo(LocalDate.parse(date))
    assertThat(p.time).isEqualTo(time?.let { LocalTime.parse(it) })
    assertThat(p.isUtc).isEqualTo(utc)
  }

  // ---- nomi ------------------------------------------------------------------------------------------------------------

  @Test
  fun whatsApp_ConIlSoloGiorno() {
    check("IMG-20240312-WA0001.jpg", FileNamePattern.WHATSAPP, "2024-03-12", null)
    check("VID-20240312-WA0123.mp4", FileNamePattern.WHATSAPP, "2024-03-12", null)
    check("img-20240312-wa0001.JPG", FileNamePattern.WHATSAPP, "2024-03-12", null)
    check("IMG-20240312-WA0001(1).jpg", FileNamePattern.WHATSAPP, "2024-03-12", null)
    // Gli adesivi non sono foto della galleria.
    assertThat(FileNameDates.parse("STK-20240312-WA0001.webp")).isNull()
  }

  @Test
  fun screenshotERegistrazioniDelloSchermo() {
    check("Screenshot_20240312_101530_Chrome.jpg", FileNamePattern.SCREENSHOT, "2024-03-12", "10:15:30")
    check("Screenshot_20240312-101530.png", FileNamePattern.SCREENSHOT, "2024-03-12", "10:15:30")
    check("Screenshot_20240312-101530_Instagram.png", FileNamePattern.SCREENSHOT, "2024-03-12", "10:15:30")
    check("Screenshot_2024-03-12-10-15-30-123_com.app.jpg", FileNamePattern.SCREENSHOT, "2024-03-12", "10:15:30")
    check("Screen_Recording_20240312-101530_Chrome.mp4", FileNamePattern.SCREEN_RECORDING, "2024-03-12", "10:15:30")
    check("Screen_Recording_20240312_101530_Chrome.mp4", FileNamePattern.SCREEN_RECORDING, "2024-03-12", "10:15:30")
    check("Screenrecorder-2024-03-12-10-15-30-123.mp4", FileNamePattern.SCREEN_RECORDING, "2024-03-12", "10:15:30")
  }

  @Test
  fun fotocamereSamsungEAosp() {
    check("20240312_101530.jpg", FileNamePattern.SAMSUNG_CAMERA, "2024-03-12", "10:15:30")
    check("20240312_101530_001.jpg", FileNamePattern.SAMSUNG_CAMERA, "2024-03-12", "10:15:30")
    check("20240312_101530(1).jpg", FileNamePattern.SAMSUNG_CAMERA, "2024-03-12", "10:15:30")
    check("20240312_101530.mp4", FileNamePattern.SAMSUNG_CAMERA, "2024-03-12", "10:15:30")
    check("IMG_20240312_101530.jpg", FileNamePattern.AOSP_CAMERA, "2024-03-12", "10:15:30")
    check("VID_20240312_101530.mp4", FileNamePattern.AOSP_CAMERA, "2024-03-12", "10:15:30")
    check("IMG_20240312_101530_BURST003_COVER.jpg", FileNamePattern.AOSP_CAMERA, "2024-03-12", "10:15:30")
    check("PANO_20240312_101530.jpg", FileNamePattern.AOSP_CAMERA, "2024-03-12", "10:15:30")
    check("MVIMG_20240312_101530.jpg", FileNamePattern.AOSP_CAMERA, "2024-03-12", "10:15:30")
    check("BURST20240312101530123_COVER.jpg", FileNamePattern.AOSP_CAMERA, "2024-03-12", "10:15:30.123")
    check("00001IMG_00001_BURST20240312101530123.jpg", FileNamePattern.AOSP_CAMERA, "2024-03-12", "10:15:30.123")
  }

  @Test
  fun pixelEInUtcConIMillisecondi() {
    check("PXL_20240312_101530123.jpg", FileNamePattern.PIXEL, "2024-03-12", "10:15:30.123", utc = true)
    check("PXL_20240312_101530123.MP.jpg", FileNamePattern.PIXEL, "2024-03-12", "10:15:30.123", utc = true)
    check("PXL_20240312_101530123.NIGHT.jpg", FileNamePattern.PIXEL, "2024-03-12", "10:15:30.123", utc = true)
    check("PXL_20240312_101530.jpg", FileNamePattern.PIXEL, "2024-03-12", "10:15:30", utc = true)
  }

  @Test
  fun signalETelegram() {
    check("signal-2024-03-12-101530.jpg", FileNamePattern.SIGNAL, "2024-03-12", "10:15:30")
    check("signal-2024-03-12-10-15-30-123.jpg", FileNamePattern.SIGNAL, "2024-03-12", "10:15:30")
    check("photo_2024-03-12_10-15-30.jpg", FileNamePattern.TELEGRAM, "2024-03-12", "10:15:30")
    check("photo_2024-03-12_10-15-30 (2).jpg", FileNamePattern.TELEGRAM, "2024-03-12", "10:15:30")
    check("video_2024-03-12_10-15-30.mp4", FileNamePattern.TELEGRAM, "2024-03-12", "10:15:30")
  }

  @Test
  fun nomiCheNonSonoInTabellaODateImpossibiliDannoNull() {
    for (name in listOf(
      "IMG_20241313_101530.jpg", // mese 13
      "IMG_20240312_251530.jpg", // ora 25
      "20240230_101530.jpg", // 30 febbraio
      "Screenshot_20240312_106030.png", // 60 minuti
      "foto di Marco.jpg",
      "DSC_0123.JPG",
      "IMG_1234.jpg",
      "2024-03-12 10.15.30.jpg",
      "20240312.jpg", // il solo giorno senza ora non e' in tabella
      "",
      "IMG_20240312_1015301.jpg", // una cifra in piu': non e' un'ora
    )) {
      assertThat(FileNameDates.parse(name)).isNull()
    }
  }

  @Test
  fun unNomeAssurdoSiRiconoscePerLaTabellaELoGiudicaIlResolver() {
    // 1970 e' una data valida per la tabella (le date assurde le scarta il resolver, E-M9).
    check("IMG_19700101_000000.jpg", FileNamePattern.AOSP_CAMERA, "1970-01-01", "00:00:00")
  }

  // ---- sorgenti ---------------------------------------------------------------------------------------------------------

  private fun source(path: String, name: String, owner: String? = null, mime: String? = "image/jpeg", volume: String = "external_primary") =
    SourceClassifier.classify(volume, path, name, owner, mime)

  @Test
  fun screenshotPerCartellaPerNomeEPerRegistrazione() {
    assertThat(source("DCIM/Screenshots/", "a.png").source).isEqualTo(MediaSource.SCREENSHOT)
    assertThat(source("Pictures/Screenshots/", "a.png").source).isEqualTo(MediaSource.SCREENSHOT)
    assertThat(source("Pictures/Screenshots/Instagram/", "a.png").source).isEqualTo(MediaSource.SCREENSHOT)
    assertThat(source("Download/", "Screenshot_20240312_101530_App.jpg").source).isEqualTo(MediaSource.SCREENSHOT)
    assertThat(source("DCIM/Screenshots/", "a.png").traits).isEmpty()
    val recording = source("DCIM/Screen recordings/", "Screen_Recording_20240312-101530_App.mp4", mime = "video/mp4")
    assertThat(recording.source).isEqualTo(MediaSource.SCREENSHOT)
    assertThat(recording.traits).containsExactly(MediaTrait.SCREEN_RECORDING)
    // Il solo nome, senza tipo noto: l'estensione dice che e' un video.
    assertThat(source("Movies/", "Screen_Recording_20240312.mp4", mime = null).traits).containsExactly(MediaTrait.SCREEN_RECORDING)
    // Una registrazione fuori dalla cartella resta una registrazione se il nome lo dice.
    assertThat(source("Movies/Screen recordings/", "x.mp4", mime = "video/mp4").traits).containsExactly(MediaTrait.SCREEN_RECORDING)
  }

  @Test
  fun whatsApp_ComprendeBusinessSentEIlPacchettoProprietario() {
    val images = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"
    assertThat(source(images, "x.jpg").source).isEqualTo(MediaSource.WHATSAPP)
    assertThat(source(images + "Sent/", "x.jpg").source).isEqualTo(MediaSource.WHATSAPP)
    assertThat(source("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/", "x.mp4", mime = "video/mp4").source).isEqualTo(MediaSource.WHATSAPP)
    assertThat(source("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Animated Gifs/", "x.mp4", mime = "video/mp4").source).isEqualTo(MediaSource.WHATSAPP)
    assertThat(source("Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images/", "x.jpg").source).isEqualTo(MediaSource.WHATSAPP)
    assertThat(source("WhatsApp/Media/WhatsApp Images/", "x.jpg").source).isEqualTo(MediaSource.WHATSAPP)
    assertThat(source("Pictures/", "x.jpg", owner = "com.whatsapp").source).isEqualTo(MediaSource.WHATSAPP)
    assertThat(source("Pictures/", "x.jpg", owner = "com.whatsapp.w4b").source).isEqualTo(MediaSource.WHATSAPP)
    // Il nome basta anche se il file e' stato spostato.
    assertThat(source("DCIM/Camera/", "IMG-20240312-WA0001.jpg").source).isEqualTo(MediaSource.WHATSAPP)
    assertThat(source("Pictures/", "x.jpg", owner = "com.signal").source).isEqualTo(MediaSource.OTHER)
  }

  @Test
  fun gliAdesiviDiWhatsAppSonoEsclusi() {
    assertThat(source("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers/", "x.webp", owner = "com.whatsapp", mime = "image/webp").source)
      .isEqualTo(MediaSource.OTHER)
    assertThat(source("Pictures/", "STK-20240312-WA0001.webp", owner = "com.whatsapp", mime = "image/webp").source).isEqualTo(MediaSource.OTHER)
    assertThat(source("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Profile Photos/", "x.jpg", owner = "com.whatsapp").source)
      .isEqualTo(MediaSource.OTHER)
  }

  @Test
  fun quickShareFotocameraEResto() {
    assertThat(source("Download/Quick Share/", "x.jpg").source).isEqualTo(MediaSource.QUICK_SHARE)
    assertThat(source("Download/Nearby Share/", "x.jpg").source).isEqualTo(MediaSource.QUICK_SHARE)
    assertThat(source("DCIM/Camera/", "x.jpg").source).isEqualTo(MediaSource.CAMERA)
    assertThat(source("DCIM/Camera/", "x.mp4", mime = "video/mp4").source).isEqualTo(MediaSource.CAMERA)
    assertThat(source("Pictures/Telegram/", "x.jpg").source).isEqualTo(MediaSource.OTHER)
    assertThat(source("Download/", "x.jpg").source).isEqualTo(MediaSource.OTHER)
    assertThat(source("", "x.jpg").source).isEqualTo(MediaSource.OTHER)
  }

  @Test
  fun maiuscoleSeparatoriEVolumeNonCambianoLEsito() {
    assertThat(source("dcim/camera", "x.jpg").source).isEqualTo(MediaSource.CAMERA)
    assertThat(source("DCIM\\Camera\\", "x.jpg").source).isEqualTo(MediaSource.CAMERA)
    assertThat(source("/DCIM/CAMERA/", "x.jpg").source).isEqualTo(MediaSource.CAMERA)
    assertThat(source("DCIM/Camera/", "x.jpg", volume = "1A2B-3C4D").source).isEqualTo(MediaSource.CAMERA)
    assertThat(source("DCIM/CameraRoll/", "x.jpg").source).isEqualTo(MediaSource.OTHER)
    assertThat(source("Backup/DCIM/Camera/", "x.jpg").source).isEqualTo(MediaSource.OTHER)
  }

  @Test
  fun lePrecedenzeSonoScreenshotWhatsAppQuickShareFotocamera() {
    // Uno screenshot nella cartella di WhatsApp resta uno screenshot; un file di WhatsApp in DCIM/Camera resta WhatsApp.
    assertThat(source("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", "Screenshot_20240312_101530.png").source).isEqualTo(MediaSource.SCREENSHOT)
    assertThat(source("DCIM/Camera/", "x.jpg", owner = "com.whatsapp").source).isEqualTo(MediaSource.WHATSAPP)
    assertThat(source("Download/Quick Share/", "IMG-20240312-WA0001.jpg").source).isEqualTo(MediaSource.WHATSAPP)
  }

  /**
   * O-2 (prove sul campo): le cartelle che hanno il loro interruttore nel backup si riconoscono dal percorso, non dal nome del file. Uno
   * "Screenshot_….png" dentro `DCIM/mcg-prova/` e' uno screenshot, ma la sua cartella resta una cartella che l'utente puo' scegliere.
   */
  @Test
  fun leCartelleDellaFotocameraEDegliScreenshotSiRiconosconoDalPercorso() {
    assertThat(SourceClassifier.folderOwner("DCIM/Camera/")).isEqualTo(MediaSource.CAMERA)
    assertThat(SourceClassifier.folderOwner("/dcim/camera/sottocartella")).isEqualTo(MediaSource.CAMERA)
    assertThat(SourceClassifier.folderOwner("Pictures/Screenshots/")).isEqualTo(MediaSource.SCREENSHOT)
    assertThat(SourceClassifier.folderOwner("DCIM/Screenshots/")).isEqualTo(MediaSource.SCREENSHOT)
    assertThat(SourceClassifier.folderOwner("Movies/Screen recordings/")).isEqualTo(MediaSource.SCREENSHOT)
    assertThat(SourceClassifier.folderOwner("DCIM/mcg-prova/")).isNull()
    assertThat(SourceClassifier.folderOwner("Pictures/mcg-prova/")).isNull()
    assertThat(SourceClassifier.folderOwner("DCIM/CameraRoll/")).isNull()
    assertThat(source("DCIM/mcg-prova/", "Screenshot_20240312_101530_Chrome.png").source).isEqualTo(MediaSource.SCREENSHOT)
  }
}
