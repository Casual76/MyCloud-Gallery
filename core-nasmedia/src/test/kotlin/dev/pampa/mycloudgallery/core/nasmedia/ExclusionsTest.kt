package dev.pampa.mycloudgallery.core.nasmedia

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nas.contract.NasAttributes
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nasmedia.exclude.Exclusions
import dev.pampa.mycloudgallery.core.nasmedia.exclude.FileClass
import dev.pampa.mycloudgallery.core.nasmedia.exclude.MediaTypes
import dev.pampa.mycloudgallery.core.nasmedia.exclude.SystemNames
import dev.pampa.mycloudgallery.core.nasmedia.exclude.TemporaryNames
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import org.junit.Test

/** D11, E-N8, E-N14, E-N15, E-N16, E-M12: cosa la scansione guarda e cosa no. Puro, senza NAS. */
class ExclusionsTest {

  private fun file(name: String, size: Long = 5_000_000, attributes: Int = NasAttributes.ARCHIVE) =
    NasEntry(name, false, size, 1_700_000_000_000, null, 42, attributes)

  @Test
  fun leEstensioniDeiMediaSonoSoloQuelleChePossiamoRiconoscereDaiByte() {
    listOf("a.jpg", "a.JPEG", "a.png", "a.heic", "a.webp", "a.gif", "a.dng", "a.tiff", "a.avif").forEach {
      assertThat(MediaTypes.classOf(it)).isEqualTo(FileClass.IMAGE)
    }
    listOf("a.mp4", "a.MOV", "a.mkv", "a.webm", "a.avi", "a.3gp", "a.m4v").forEach {
      assertThat(MediaTypes.classOf(it)).isEqualTo(FileClass.VIDEO)
    }
    listOf("a.mp3", "a.FLAC", "a.m4a", "a.wav").forEach { assertThat(MediaTypes.classOf(it)).isEqualTo(FileClass.AUDIO) }
    listOf("a.cue", "a.log", "a.m3u", "a.txt", "a", ".jpg", "a.").forEach { assertThat(MediaTypes.classOf(it)).isNull() }
    assertThat(MediaTypes.mimeOf("IMG.HEIC")).isEqualTo("image/heic")
    assertThat(MediaTypes.isMedia("x.mp3")).isFalse()
  }

  @Test
  fun leCartelleDiSistemaEIPuntiNonSiScansionanoMai() {
    // E-N16: i cestini del NAS, gli indici, le cartelle col punto.
    listOf(".recycle", ".Recycle Bin", "\$RECYCLE.BIN", ".wdmc", "@eaDir", ".AppleDouble", ".mycloudgallery", ".qualunque", "#recycle", "System Volume Information", "lost+found")
      .forEach { assertThat(SystemNames.isSystemFolder(it)).isTrue() }
    listOf("Camera", "2024", "Vacanze", "Recycle", "eaDir").forEach { assertThat(SystemNames.isSystemFolder(it)).isFalse() }
  }

  @Test
  fun iFileNascostiPerConvenzioneSiSaltano() {
    listOf("._IMG_0001.jpg", ".DS_Store", "Thumbs.db", "desktop.ini").forEach { assertThat(SystemNames.isSystemFile(it)).isTrue() }
    assertThat(SystemNames.isSystemFile("IMG_0001.jpg")).isFalse()
  }

  @Test
  fun iNomiTemporaneiSonoMediaInArrivoSoloSeIlNomeVeroEUnMedia() {
    assertThat(TemporaryNames.isTemporary("video.mp4.part")).isTrue()
    assertThat(TemporaryNames.isArrivingMedia("video.mp4.part")).isTrue()
    assertThat(TemporaryNames.isArrivingMedia("foto.JPG.crdownload")).isTrue()
    assertThat(TemporaryNames.isTemporary("~\$relazione.docx")).isTrue()
    assertThat(TemporaryNames.isArrivingMedia("~\$relazione.docx")).isFalse()
    assertThat(TemporaryNames.isArrivingMedia("dati.tmp")).isFalse()
    assertThat(TemporaryNames.isTemporary("video.mp4")).isFalse()
  }

  @Test
  fun unaCopertinaEUnImmagineInUnaCartellaConMusicaCheSiChiamaComeUnaCopertinaOPesaPoco() {
    val tracks = listOf(file("01 Traccia.mp3", 8_000_000))
    // Per nome (anche grande).
    listOf("cover.jpg", "Folder.JPG", "front.png", "AlbumArtSmall.jpg", "AlbumArt_{ABC}_Large.jpg", "Cover-1.jpeg").forEach {
      assertThat(Exclusions.isAlbumCover(file(it, 2_000_000), tracks + file(it))).isTrue()
    }
    // Per dimensione: sotto 300 KB qualunque nome.
    assertThat(Exclusions.isAlbumCover(file("scan0001.jpg", 120_000), tracks)).isTrue()
    assertThat(Exclusions.isAlbumCover(file("scan0001.jpg", Exclusions.COVER_MAX_BYTES - 1), tracks)).isTrue()
    // Una foto grande con un nome qualunque, in una cartella di musica, e' una foto.
    assertThat(Exclusions.isAlbumCover(file("IMG_2024.jpg", 3_000_000), tracks)).isFalse()
    assertThat(Exclusions.isAlbumCover(file("scan0001.jpg", Exclusions.COVER_MAX_BYTES), tracks)).isFalse()
    // Senza musica nella cartella non e' una copertina, nemmeno se piccola.
    assertThat(Exclusions.isAlbumCover(file("cover.jpg", 40_000), listOf(file("IMG.jpg")))).isFalse()
    // Un video non e' una copertina.
    assertThat(Exclusions.isAlbumCover(file("cover.mp4", 40_000), tracks)).isFalse()
  }

  @Test
  fun conLeCopertineAccesseNonSiEscludeNulla() {
    val shown = Exclusions.build(emptyList(), emptyList(), showCovers = true)
    val hidden = Exclusions.build(emptyList(), emptyList(), showCovers = false)
    assertThat(shown.isCover(file("cover.jpg", 40_000), folderHasAudio = true)).isFalse()
    assertThat(hidden.isCover(file("cover.jpg", 40_000), folderHasAudio = true)).isTrue()
    assertThat(hidden.isCover(file("cover.jpg", 40_000), folderHasAudio = false)).isFalse()
  }

  @Test
  fun leCartelleEscluseValgonoConTuttoCioCheCEDentroSenzaDistinguereLeMaiuscole() {
    val set = Exclusions.build(
      config = listOf(SharePath.of("Musica")),
      local = listOf(SharePath.of("Film", "Serie TV")),
      showCovers = false,
    )
    assertThat(set.size).isEqualTo(2)
    assertThat(set.excludes(SharePath.of("Musica"))).isTrue()
    assertThat(set.excludes(SharePath.of("musica", "Artista 1", "Album 3"))).isTrue()
    assertThat(set.excludes(SharePath.of("Film", "serie tv"))).isTrue()
    assertThat(set.excludes(SharePath.of("Film", "Serie TV", "S01"))).isTrue()
    assertThat(set.excludes(SharePath.of("Film"))).isFalse()
    assertThat(set.excludes(SharePath.of("Musicale"))).isFalse()
    assertThat(set.excludes(SharePath.of("Foto", "Musica"))).isFalse()
  }
}
