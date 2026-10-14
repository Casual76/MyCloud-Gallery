package dev.pampa.mycloudgallery.core.designsystem.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * I principi misurabili della revisione grafica G1 (`docs/grafica-progetto.md` §3), come numeri: il testo sopra il vetro
 * regge 4,5:1 e le icone 3:1 sul **caso peggiore** del contenuto sotto (foto bianche, nere, grigie, sature), non sul caso
 * medio. Le direzioni A e C lo garantiscono per costruzione; qui lo si verifica.
 */
class GalleryLegibilityTest {

  private val black = Color(0xFF000000)
  private val white = Color(0xFFFFFFFF)

  /** Le foto peggiori: bianca, nera, grigi medi, colori saturi (il giallo e il ciano sono chiari, il blu e' scuro). */
  private val worst = listOf(
    white, black, Color(0xFF777777), Color(0xFFBBBBBB), Color(0xFF333333),
    Color(0xFFFF0000), Color(0xFF00C800), Color(0xFF0000FF), Color(0xFFFFE600), Color(0xFF00E6FF), Color(0xFFFF00FF), Color(0xFFFF7800),
  )
  private val palette = GalleryChromeStyles.adaptivePaletteFor(black)

  @Test
  fun ilContrastoEQuelloDelleWcag() {
    assertThat(GalleryLegibility.contrast(white, black)).isWithin(0.01f).of(21f)
    assertThat(GalleryLegibility.contrast(white, white)).isWithin(0.001f).of(1f)
    assertThat(GalleryLegibility.contrast(Color(0xFF777777), white)).isWithin(0.05f).of(4.48f)
  }

  /** A: il bianco sulla sfumatura nera (>= `OverPhotoTextScrim` sotto il testo) regge 4,5:1 sopra ogni foto, anche bianca. */
  @Test
  fun aIlTestoBiancoSullaSfumaturaReggeSopraOgniFoto() {
    val scrim = black.copy(alpha = GalleryChromeStyles.OverPhotoTextScrim)
    for (photo in worst) {
      val c = GalleryLegibility.contrastOver(white, scrim, photo)
      assertWithMessage("bianco su sfumatura sopra $photo").that(c).isAtLeast(GalleryLegibility.TextContrast)
    }
  }

  /** A: la capsula scura (`darkFloatingTint`, nero al 48%) sopra la sfumatura: "Fine" e le icone reggono 4,5:1 ovunque. */
  @Test
  fun aLaCapsulaScuraSullaSfumaturaRegge() {
    val scrim = black.copy(alpha = GalleryChromeStyles.OverPhotoTextScrim)
    val film = black.copy(alpha = DarkFloatingFilm)
    for (photo in worst) {
      val under = scrim.compositeOver(photo)
      assertWithMessage("bianco sulla capsula sopra $photo").that(GalleryLegibility.contrastOver(white, film, under)).isAtLeast(GalleryLegibility.TextContrast)
    }
  }

  /** A: la barra delle azioni scura in basso (senza sfumatura) tiene le icone a 3:1 anche sopra il bianco. */
  @Test
  fun aLaBarraDelleAzioniScuraTieneLeIcone() {
    val film = black.copy(alpha = DarkFloatingFilm)
    for (photo in worst) {
      assertWithMessage("icona bianca sopra $photo").that(GalleryLegibility.contrastOver(white, film, photo)).isAtLeast(GalleryLegibility.LargeContrast)
    }
  }

  /** C: per ogni foto peggiore da sola, e per tutte insieme, il vetro adattivo arriva a 4,5:1 senza superare il tetto. */
  @Test
  fun cIlVetroAdattivoReggeIlCasoPeggiore() {
    val ranges = worst.map { ContentRange(listOf(it)) } + listOf(ContentRange(worst), palette.unknown, ContentRange(listOf(white, Color(0xFF111111))))
    for (range in ranges) {
      val choice = GalleryLegibility.adapt(range, palette)
      assertWithMessage("adattivo su ${range.samples}").that(choice.contrast).isAtLeast(GalleryLegibility.TextContrast)
      assertThat(choice.film.alpha).isAtMost(palette.ceiling + AlphaStep)
      assertThat(choice.film.alpha).isAtLeast(palette.floor - AlphaStep)
    }
  }

  /** C: sopra il chiaro inchiostro scuro, sopra lo scuro inchiostro chiaro; e una foto uniforme chiede la pellicola minima. */
  @Test
  fun cSceglieLaPolaritaCheLasciaPassarePiuFoto() {
    assertThat(GalleryLegibility.adapt(ContentRange(listOf(white)), palette).darkInk).isTrue()
    assertThat(GalleryLegibility.adapt(ContentRange(listOf(black)), palette).darkInk).isFalse()
    assertThat(GalleryLegibility.adapt(ContentRange(listOf(white)), palette).film.alpha).isWithin(AlphaStep).of(palette.floor)
    // Sopra il nero, inchiostro scuro costerebbe molta piu' pellicola di quello chiaro.
    val onBlack = GalleryLegibility.adapt(ContentRange(listOf(black)), palette)
    assertThat(onBlack.film.alpha).isWithin(AlphaStep).of(palette.floor)
  }

  /** C: l'isteresi. Un contenuto quasi neutro non fa cambiare polarita' a ogni riga che passa. */
  @Test
  fun cNonLampeggiaFraChiaroEScuro() {
    val gray = ContentRange(listOf(Color(0xFF8A8A8A)))
    val free = GalleryLegibility.adapt(gray, palette)
    val keptDark = GalleryLegibility.adapt(gray, palette, previousDark = true)
    val keptLight = GalleryLegibility.adapt(gray, palette, previousDark = false)
    assertThat(keptDark.darkInk).isTrue()
    assertThat(keptLight.darkInk).isFalse()
    assertThat(keptDark.contrast).isAtLeast(GalleryLegibility.TextContrast)
    assertThat(keptLight.contrast).isAtLeast(GalleryLegibility.TextContrast)
    assertThat(free.contrast).isAtLeast(GalleryLegibility.TextContrast)
  }

  @Test
  fun laPellicolaMinimaEDavveroMinima() {
    val range = ContentRange(listOf(black))
    val alpha = GalleryLegibility.minimalFilmAlpha(palette.darkInk, white, range, GalleryLegibility.TextContrast, 0f, 1f)
    assertThat(GalleryLegibility.worstContrast(palette.darkInk, white.copy(alpha = alpha), range)).isAtLeast(GalleryLegibility.TextContrast)
    assertThat(GalleryLegibility.worstContrast(palette.darkInk, white.copy(alpha = alpha - 0.01f), range)).isLessThan(GalleryLegibility.TextContrast)
  }

  /** Il velo sotto un vetro altrui (la pillola): con il velo minimo l'etichetta regge sopra il bianco e il nero. */
  @Test
  fun ilVeloSottoLaPillolaGarantisceLeEtichette() {
    val darkPage = Color(0xFF141218)
    val label = Color(0xFFE6E1E5)
    val pillFilm = Color(0xFF3F3E44).copy(alpha = 0.48f)
    val range = ContentRange(listOf(white, black))
    val alpha = GalleryLegibility.minimalUnderlayAlpha(label, pillFilm, darkPage, range, GalleryLegibility.TextContrast, 0f, 1f)
    for (photo in listOf(white, black)) {
      val under = darkPage.copy(alpha = alpha).compositeOver(photo)
      assertThat(GalleryLegibility.contrastOver(label, pillFilm, under)).isAtLeast(GalleryLegibility.TextContrast - 0.01f)
    }
    // Senza velo, sopra il bianco, l'etichetta chiara sulla pellicola grigia non regge: il velo serve davvero.
    assertThat(GalleryLegibility.contrastOver(label, pillFilm, white)).isLessThan(GalleryLegibility.TextContrast)
    assertThat(alpha).isGreaterThan(0.3f)
  }

  /** Il rosso distruttivo su una pellicola scura si schiarisce quanto basta per 3:1, e resta un rosso. */
  @Test
  fun ilRossoDistruttivoSiLeggeSullaPellicolaScura() {
    val error = Color(0xFFBA1A1A)
    val film = black.copy(alpha = DarkFloatingFilm)
    val range = ContentRange(listOf(white))
    val ink = GalleryChromeStyles.legibleTowards(error, white, film, range, GalleryLegibility.LargeContrast)
    assertThat(GalleryLegibility.worstContrast(ink, film, range)).isAtLeast(GalleryLegibility.LargeContrast)
    assertThat(ink.red).isGreaterThan(ink.green)
    assertThat(ink.red).isGreaterThan(ink.blue)
  }

  private companion object {
    /** L'opacita' di `GlassDefaults.darkFloatingTint()` dell'engine 2.13.0. */
    const val DarkFloatingFilm = 0.48f

    /** L'opacita' di un `Color` ha 8 bit: un passo e' 1/255. */
    const val AlphaStep = 1f / 255f + 1e-4f
  }
}
