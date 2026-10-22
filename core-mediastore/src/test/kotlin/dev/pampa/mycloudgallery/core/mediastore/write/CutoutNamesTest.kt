package dev.pampa.mycloudgallery.core.mediastore.write

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.contract.SaveRequest
import org.junit.Test

/** Fase 6 (P13, D47): i nomi dei file dei ritagli e le cartelle in cui nascono. */
class CutoutNamesTest {

  @Test
  fun ilNomeVieneDalFileDiOrigineSenzaEstensione() {
    assertThat(CutoutNames.displayName("IMG_0042.jpg")).isEqualTo("IMG_0042-ritaglio.png")
    assertThat(CutoutNames.displayName("foto.di.famiglia.heic")).isEqualTo("foto.di.famiglia-ritaglio.png")
    assertThat(CutoutNames.displayName("senza estensione")).isEqualTo("senza estensione-ritaglio.png")
    assertThat(CutoutNames.displayName("DCIM/Camera/IMG_1.jpg")).isEqualTo("IMG_1-ritaglio.png")
    assertThat(CutoutNames.displayName("C:\\Foto\\vacanza.png")).isEqualTo("vacanza-ritaglio.png")
  }

  @Test
  fun senzaNomeUtilizzabileSiChiamaRitaglio() {
    assertThat(CutoutNames.displayName(null)).isEqualTo("Ritaglio.png")
    assertThat(CutoutNames.displayName("")).isEqualTo("Ritaglio.png")
    assertThat(CutoutNames.displayName("   ")).isEqualTo("Ritaglio.png")
    assertThat(CutoutNames.displayName(".nascosto")).isEqualTo(".nascosto-ritaglio.png")
    assertThat(CutoutNames.displayName("???.jpg")).isEqualTo("Ritaglio.png")
  }

  @Test
  fun iCaratteriVietatiEIControlloSiTolgono() {
    assertThat(CutoutNames.displayName("a:b*c?d\"e<f>g|h.jpg")).isEqualTo("abcdefgh-ritaglio.png")
    assertThat(CutoutNames.displayName("nome\u0000con\u0007controllo.jpg")).isEqualTo("nomeconcontrollo-ritaglio.png")
  }

  @Test
  fun ilNomeNonSuperaIlLimiteDelMediaStore() {
    val name = CutoutNames.displayName("x".repeat(400) + ".jpg")
    assertThat(name.length).isAtMost(255)
    assertThat(name).endsWith("-ritaglio.png")
    // E la richiesta di salvataggio lo accetta (niente barre, al piu' 255 caratteri).
    SaveRequest(name, "image/png", 10)
  }

  @Test
  fun leCartelleDeiRitagliSonoQuelleDichiarate() {
    assertThat(PhoneMediaFolders.CUTOUTS).isEqualTo("Pictures/MyCloud Gallery/Ritagli/")
    assertThat(PhoneMediaFolders.CUTOUTS).startsWith(PhoneMediaFolders.DOWNLOADS)
    assertThat(PhoneMediaFolders.FENCED_CUTOUTS).isEqualTo("Pictures/mcg-prova/Ritagli/")
    assertThat(PhoneMediaFolders.FENCED_CUTOUTS).startsWith(PhoneMediaFolders.FENCED_ROOT)
    assertThat(PhoneMediaFolders.cutouts(fenced = true)).isEqualTo(PhoneMediaFolders.FENCED_CUTOUTS)
    assertThat(PhoneMediaFolders.cutouts(fenced = false)).isEqualTo(PhoneMediaFolders.CUTOUTS)
  }
}
