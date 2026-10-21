package dev.pampa.mycloudgallery.feature.viewer.livetext

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.viewer.testing.LiveTextFixtures
import kotlin.math.abs
import org.junit.Test

/**
 * La geometria del testo dal vivo (Fase 6, P12, D43): righe dritte, inclinate e su piu' righe; parole agganciate; maniglie.
 * Su JVM puro, senza schermo.
 */
class LiveTextGeometryTest {

  private val aspect = LiveTextTestLines.ASPECT

  private fun center(geometry: LiveTextGeometry, line: Int, word: Int): Offset {
    val quad = geometry.quad(line, word, word)
    return Offset(quad.map { it.x }.average().toFloat(), quad.map { it.y }.average().toFloat())
  }

  private fun near(a: Float, b: Float, tolerance: Float = 1e-3f) = assertThat(abs(a - b)).isAtMost(tolerance)

  // --- Il telaio: lunghezza e spessore dal riquadro e dall'inclinazione ----------------------------------------------------

  @Test
  fun unaRigaDrittaHaLaLunghezzaELoSpessoreDelRiquadro() {
    val line = LiveTextTestLines.line("Via Roma", 0.5f, 0.5f, 0.30f, 0.05f, 0, aspect)
    val frame = LineFrame(line.box, 0, aspect)
    near(frame.length, 0.30f)
    near(frame.thickness, 0.05f)
  }

  @Test
  fun unaRigaInclinataRitrovaLunghezzaESpessoreDalRiquadroDritto() {
    for (angle in listOf(5, 12, 20, 30, -12, -30, 60, -60, 90, -90)) {
      val line = LiveTextTestLines.line("alfa beta gamma", 0.45f, 0.5f, 0.50f, 0.06f, angle, aspect)
      val frame = LineFrame(line.box, angle, aspect)
      near(frame.length, 0.50f, 2e-3f)
      near(frame.thickness, 0.06f, 2e-3f)
    }
  }

  @Test
  fun vicinoA45GradiIlRipiegoTieneLaRigaNelRiquadro() {
    val line = LiveTextTestLines.line("alfa beta gamma", 0.45f, 0.5f, 0.40f, 0.05f, 45, aspect)
    val frame = LineFrame(line.box, 45, aspect)
    assertThat(frame.length).isGreaterThan(0.3f)
    assertThat(frame.length).isLessThan(0.6f)
    assertThat(frame.thickness).isGreaterThan(0f)
  }

  @Test
  fun unRiquadroTroppoBassoPerLInclinazioneNonDaUnaRigaRotta() {
    // `LiveTextFixtures.tilted` di P0: 12 gradi in un riquadro alto 0,06. Non e' un rettangolo ruotato possibile: la riga resta finita.
    val box = LiveTextFixtures.tilted.lines.single().box
    val frame = LineFrame(box, 12, aspect)
    assertThat(frame.length).isGreaterThan(0f)
    assertThat(frame.thickness).isGreaterThan(0f)
    assertThat(frame.length.isFinite() && frame.thickness.isFinite()).isTrue()
  }

  @Test
  fun puntoELocalSonoInversi() {
    val line = LiveTextTestLines.line("alfa beta", 0.4f, 0.5f, 0.4f, 0.06f, 15, aspect)
    val frame = LineFrame(line.box, 15, aspect)
    val point = frame.point(0.3f, -0.2f)
    val local = frame.local(point)
    near(local.x, 0.3f, 1e-4f)
    near(local.y, -0.2f, 1e-4f)
  }

  // --- Dove cade un dito --------------------------------------------------------------------------------------------------------

  @Test
  fun ilCentroDiOgniParolaDiOgniFixtureEQuellaParola() {
    for (layout in listOf(LiveTextTestLines.centered, LiveTextTestLines.tilted, LiveTextTestLines.steep, LiveTextTestLines.multiline)) {
      val geometry = LiveTextGeometry(layout, aspect)
      for (line in layout.lines.indices) {
        for (word in layout.lines[line].words.indices) {
          assertThat(geometry.wordAt(center(geometry, line, word), tolerance = 0.002f)).isEqualTo(TextPosition(line, word))
          assertThat(geometry.nearestWord(center(geometry, line, word))).isEqualTo(TextPosition(line, word))
        }
      }
    }
  }

  @Test
  fun unPuntoLontanoDalTestoNonEUnaParola() {
    val geometry = LiveTextGeometry(LiveTextTestLines.centered, aspect)
    assertThat(geometry.wordAt(Offset(0.05f, 0.9f), tolerance = 0.02f)).isNull()
    assertThat(geometry.wordAt(Offset(0.5f, 0.2f), tolerance = 0.02f)).isNull()
  }

  @Test
  fun laToleranzaAllargaLaParolaDelDito() {
    val geometry = LiveTextGeometry(LiveTextTestLines.centered, aspect)
    val above = Offset(0.5f, 0.5f - 0.025f - 0.015f)
    assertThat(geometry.wordAt(above, tolerance = 0.005f)).isNull()
    assertThat(geometry.wordAt(above, tolerance = 0.02f)).isNotNull()
  }

  @Test
  fun suUnaRigaInclinataLaParolaSegueLInclinazione() {
    // La riga scende verso destra: la fine sta piu' in basso dell'inizio. Un punto sopra la fine, alla quota dell'inizio, non e' sulla fine.
    val geometry = LiveTextGeometry(LiveTextTestLines.tilted, aspect)
    val first = center(geometry, 0, 0)
    val last = center(geometry, 0, 2)
    assertThat(last.y).isGreaterThan(first.y + 0.05f)
    assertThat(geometry.wordAt(last, 0.002f)).isEqualTo(TextPosition(0, 2))
    assertThat(geometry.wordAt(Offset(last.x, first.y), 0.002f)).isNull()
  }

  @Test
  fun suPiuRigheLaParolaPiuVicinaEQuellaDellaRigaGiusta() {
    val geometry = LiveTextGeometry(LiveTextTestLines.multiline, aspect)
    assertThat(geometry.nearestWord(Offset(0.20f, 0.56f))).isEqualTo(TextPosition(2, 0))
    // Sotto tutto il testo, sotto l'ultima riga: l'ultima parola; sopra tutto, a sinistra: la prima.
    assertThat(geometry.nearestWord(Offset(0.22f, 0.99f))).isEqualTo(geometry.last())
    assertThat(geometry.nearestWord(Offset(0.01f, 0.01f))).isEqualTo(geometry.first())
  }

  // --- Maniglie e rettangoli --------------------------------------------------------------------------------------------------------

  @Test
  fun laManigliaDiInizioStaAllInizioDellaParolaEQuellaDiFineAllaSuaFine() {
    val geometry = LiveTextGeometry(LiveTextTestLines.centered, aspect)
    val start = geometry.handle(TextPosition(0, 0), atEnd = false)
    val end = geometry.handle(TextPosition(0, 0), atEnd = true)
    // "Via" occupa i primi 3 caratteri su 8 della riga, che e' larga 0,30 altezze (in x normalizzato diviso per l'aspetto).
    val left = 0.5f - 0.30f / 2f / aspect
    near(start.anchor.x, left, 1e-3f)
    near(end.anchor.x, left + 0.30f / aspect * 3f / 8f, 1e-3f)
    // La punta sta sul bordo basso del testo, l'aggancio nel mezzo.
    assertThat(start.tip.y).isGreaterThan(start.anchor.y)
  }

  @Test
  fun leManiglieSeguonoLInclinazioneDellaRiga() {
    val geometry = LiveTextGeometry(LiveTextTestLines.tilted, aspect)
    val start = geometry.handle(TextPosition(0, 0), atEnd = false)
    val end = geometry.handle(TextPosition(0, 2), atEnd = true)
    assertThat(end.anchor.y).isGreaterThan(start.anchor.y + 0.1f)
    assertThat(start.angleDegrees).isEqualTo(12)
  }

  @Test
  fun ilRettangoloDiPiuParoleCopreDallInizioDellaPrimaAllaFineDellUltima() {
    val geometry = LiveTextGeometry(LiveTextTestLines.centered, aspect)
    val whole = geometry.quad(0, 0, 1)
    val xs = whole.map { it.x }
    near(xs.min(), 0.5f - 0.30f / 2f / aspect)
    near(xs.max(), 0.5f + 0.30f / 2f / aspect)
    assertThat(geometry.lineQuad(0)).isEqualTo(whole)
  }

  @Test
  fun leFixtureDiP0LeggonoIPrimiEUltimiPunti() {
    val geometry = LiveTextGeometry(LiveTextFixtures.multiline, aspect)
    assertThat(geometry.first()).isEqualTo(TextPosition(0, 0))
    assertThat(geometry.last()).isEqualTo(TextPosition(2, 0))
    assertThat(LiveTextGeometry(ViewerTextLayout(emptyList()), aspect).first()).isNull()
    assertThat(LiveTextGeometry(ViewerTextLayout(emptyList()), aspect).nearestWord(Offset(0.5f, 0.5f))).isNull()
  }
}
