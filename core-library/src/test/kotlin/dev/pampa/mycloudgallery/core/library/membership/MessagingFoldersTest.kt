package dev.pampa.mycloudgallery.core.library.membership

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import org.junit.Test

/** G2b: le cartelle di Telegram e Signal (Messaggistica) e la parte dei due filtri nuovi che la cella sa dire da sola. */
class MessagingFoldersTest {

  @Test
  fun leCartelleDiTelegramSulTelefono() {
    listOf(
      "Pictures/Telegram/", "Movies/Telegram/", "DCIM/Telegram/", "Download/Telegram/",
      "Telegram/Telegram Images/", "Telegram/Telegram Video/",
      "Android/media/org.telegram.messenger/Telegram/Telegram Images/", "Android/media/org.telegram.messenger.web/Telegram/",
      "Android/media/org.telegram.plus/Plus/", "Android/media/org.thunderdog.challegram/Telegram X/",
      "/pictures/telegram", "PICTURES\\TELEGRAM\\",
    ).forEach { path -> assertWithMessage(path).that(MessagingFolders.isPhoneFolder(path)).isTrue() }
  }

  @Test
  fun leCartelleDiSignalSulTelefono() {
    listOf("Pictures/Signal/", "Movies/Signal/", "Download/Signal/", "Signal/", "Android/media/org.thoughtcrime.securesms/").forEach { path ->
      assertWithMessage(path).that(MessagingFolders.isPhoneFolder(path)).isTrue()
    }
  }

  @Test
  fun leAltreCartelleNo() {
    listOf(
      "DCIM/Camera/", "Pictures/Screenshots/", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", "Pictures/Signals/",
      "Pictures/Vacanze Telegram/", "Download/", "", "Telegram/Telegram Stickers/", "Pictures/Telegram/stickers/",
    ).forEach { path -> assertWithMessage(path).that(MessagingFolders.isPhoneFolder(path)).isFalse() }
    assertThat(MessagingFolders.isPhoneFolder(null)).isFalse()
  }

  @Test
  fun leCopieDelBackupDelleCartelleDiMessaggistica() {
    listOf(
      "Backup/S25/Telegram/2025/05/IMG_1.jpg", "Backup/S25/Telegram Images/2025/05/IMG_1.jpg", "Backup/S25/Telegram Video/Senza data/V.mp4",
      "Backup/S25/Signal/2024/12/signal-2024.jpg", "backup/tablet/microSD/Telegram/2023/01/x.jpg",
    ).forEach { path -> assertWithMessage(path).that(MessagingFolders.isBackupFile(path)).isTrue() }
  }

  @Test
  fun lAltroDelNasNo() {
    listOf(
      // La fotocamera nel backup sta sotto anno e mese, senza cartella: mai Messaggistica.
      "Backup/S25/2025/05/IMG_1.jpg",
      // Una cartella "Telegram" fuori dal backup e' dell'utente: l'app non ne sa niente.
      "Foto/Telegram/IMG_1.jpg",
      // Il dispositivo si chiama "Telegram": conta la cartella sotto, non il nome del dispositivo.
      "Backup/Telegram/2025/05/IMG_1.jpg",
      "Backup/S25/Screenshot/2025/05/Screenshot_1.png",
      "Backup/S25/Telegram",
      "Backup/S25/Telegram/Stickers/x.webp",
    ).forEach { path -> assertWithMessage(path).that(MessagingFolders.isBackupFile(path)).isFalse() }
    assertThat(MessagingFolders.isBackupFile(null)).isFalse()
  }

  @Test
  fun quelloCheLaCellaSaDeiDueFiltriNuovi() {
    assertThat(LibraryFilter.CAMERA.accepts(MediaKind.PHOTO, MediaSource.CAMERA, 0)).isTrue()
    assertThat(LibraryFilter.CAMERA.accepts(MediaKind.VIDEO, MediaSource.CAMERA, CellFlags.ON_NAS)).isTrue()
    // I backup degli altri dispositivi stanno sotto anno e mese: il NAS li classifica OTHER, e la Fotocamera non li prende.
    assertThat(LibraryFilter.CAMERA.accepts(MediaKind.PHOTO, MediaSource.OTHER, CellFlags.ON_NAS)).isFalse()
    assertThat(LibraryFilter.CAMERA.accepts(MediaKind.PHOTO, MediaSource.QUICK_SHARE, CellFlags.ON_PHONE)).isFalse()
    assertThat(LibraryFilter.MESSAGING.accepts(MediaKind.PHOTO, MediaSource.WHATSAPP, 0)).isTrue()
    assertThat(LibraryFilter.MESSAGING.accepts(MediaKind.PHOTO, MediaSource.OTHER, 0)).isFalse()
    assertThat(LibraryFilter.entries.filter { it.needsMembership }).containsExactly(LibraryFilter.CAMERA, LibraryFilter.MESSAGING)
    // I codici sono nuovi e stabili; quelli di prima non cambiano.
    assertThat(LibraryFilter.fromCode("camera")).isEqualTo(LibraryFilter.CAMERA)
    assertThat(LibraryFilter.fromCode("messaging")).isEqualTo(LibraryFilter.MESSAGING)
    assertThat(LibraryFilter.fromCode("whatsapp")).isEqualTo(LibraryFilter.WHATSAPP)
  }

  @Test
  fun laCartellaDiQuestoDispositivoInFormaDiConfronto() {
    assertThat(CatalogFilterMembership.originLabel("S25 di Alessio")).isEqualTo("backup:s25 di alessio")
    assertThat(CatalogFilterMembership.originLabel("Backup/S25")).isEqualTo("backup:s25")
    assertThat(CatalogFilterMembership.originLabel("/Backup/S25/")).isEqualTo("backup:s25")
    assertThat(CatalogFilterMembership.originLabel("  ")).isNull()
    assertThat(CatalogFilterMembership.originLabel("/")).isNull()
    assertThat(CatalogFilterMembership.originLabel(null)).isNull()
  }
}
