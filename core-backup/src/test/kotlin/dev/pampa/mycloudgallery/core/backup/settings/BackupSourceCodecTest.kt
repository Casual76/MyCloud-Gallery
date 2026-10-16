package dev.pampa.mycloudgallery.core.backup.settings

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import org.junit.Test

class BackupSourceCodecTest {

  @Test
  fun ogniSorgente_siSalvaESiRilegge() {
    val sources =
      BackupSources(
        setOf(
          BackupSource.Camera,
          BackupSource.Screenshots,
          BackupSource.Folder("external_primary", "Pictures/Telegram/"),
          BackupSource.Folder("1A2B-3C4D", "DCIM/Camera/"), // la microSD (E-P6)
          BackupSource.Folder("external_primary", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"),
          BackupSource.Folder("external_primary", "Pictures/a:b/"), // il percorso puo' avere i due punti, il volume no
        ),
      )

    val saved = BackupSourceCodec.encode(sources)

    assertThat(saved).hasSize(6)
    assertThat(BackupSourceCodec.decode(saved)).isEqualTo(sources)
  }

  @Test
  fun idCheNonSiCapiscono_siIgnoranoSenzaEccezioni() {
    for (id in listOf("", "boh", "camera ", "Camera", "folder", "folder:", "folder:vol", "folder::path", "folder:vol:", "folder:vol: ", "folder:: ")) {
      assertThat(BackupSourceCodec.decodeOne(id)).isNull()
    }
    assertThat(BackupSourceCodec.decode(setOf("camera", "boh", "screenshots"))).isEqualTo(BackupSources.DEFAULT)
  }
}
