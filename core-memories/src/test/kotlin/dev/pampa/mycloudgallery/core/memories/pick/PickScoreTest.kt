package dev.pampa.mycloudgallery.core.memories.pick

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.CandidateCell
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryRules
import dev.pampa.mycloudgallery.core.memories.contract.PhotoScore
import dev.pampa.mycloudgallery.core.memories.contract.PickSignals
import dev.pampa.mycloudgallery.core.memories.contract.PickSpec
import dev.pampa.mycloudgallery.core.memories.contract.PickWeights
import dev.pampa.mycloudgallery.core.memories.contract.PixelProbe
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import org.junit.Test

/**
 * Il punteggio con i pesi in tabella (D6): ogni peso da solo, ai due lati di ogni soglia, e l'effetto sulla scelta. I numeri qui sono
 * quelli di D6 scritti a mano: se P11 cambia la tabella `PickWeights`, questa prova dice quali pesi sono cambiati.
 */
class PickScoreTest {

  private val cells = Cells()
  private val none = PickSignals()

  private fun score(cell: CandidateCell, signals: PickSignals = none) = PhotoScore.of(cell, signals)

  @Test
  fun laTabellaDeiPesiEQuellaDiD6() {
    assertThat(PickWeights.FAVORITE).isEqualTo(3.0)
    assertThat(PickWeights.EDITED).isEqualTo(1.0)
    assertThat(PickWeights.IN_ALBUM).isEqualTo(1.0)
    assertThat(PickWeights.NAMED_PERSON).isEqualTo(1.0)
    assertThat(PickWeights.PANORAMA).isEqualTo(0.5)
    assertThat(PickWeights.MOTION).isEqualTo(0.3)
    assertThat(PickWeights.VIDEO_SHORT).isEqualTo(0.5)
    assertThat(PickWeights.VIDEO_LONG).isEqualTo(-0.5)
    assertThat(PickWeights.WHATSAPP).isEqualTo(-1.5)
    assertThat(PickWeights.BAD_LIGHT).isEqualTo(-1.0)
    assertThat(PickWeights.BLURRY).isEqualTo(-1.5)
    assertThat(PickWeights.DARK_LUMA).isEqualTo(0.12)
    assertThat(PickWeights.BURNT_LUMA).isEqualTo(0.92)
    assertThat(PickWeights.DUPLICATE_COSINE).isEqualTo(0.92)
    assertThat(PickWeights.DUPLICATE_WINDOW_MS).isEqualTo(180_000L)
    assertThat(PickWeights.MMR_LAMBDA).isEqualTo(0.5)
    assertThat(PickWeights.WHATSAPP_MAX_SHARE).isEqualTo(0.25)
  }

  @Test
  fun unaFotoSenzaSegnaliValeZero() {
    assertThat(score(cells.cell(0))).isEqualTo(0.0)
  }

  @Test
  fun ognunDeiSegnaliDellUtenteDaSolo() {
    assertThat(score(cells.cell(0, flags = CellFlags.FAVORITE))).isEqualTo(3.0)
    assertThat(score(cells.cell(0, flags = CellFlags.EDITED))).isEqualTo(1.0)
    val inAlbum = cells.cell(0)
    assertThat(score(inAlbum, PickSignals(albums = setOf(inAlbum.id)))).isEqualTo(1.0)
    val withPerson = cells.cell(0)
    assertThat(score(withPerson, PickSignals(people = mapOf(withPerson.id to setOf(person(1)))))).isEqualTo(1.0)
    assertThat(score(cells.cell(0, flags = CellFlags.PANORAMA))).isEqualTo(0.5)
    assertThat(score(cells.cell(0, flags = CellFlags.MOTION))).isEqualTo(0.3)
  }

  /** Una persona vale una volta sola, non una per volto. */
  @Test
  fun lePersoneValgonoUnaVoltaSola() {
    val group = cells.cell(0)
    assertThat(score(group, PickSignals(people = mapOf(group.id to setOf(person(1), person(2), person(3)))))).isEqualTo(1.0)
  }

  @Test
  fun iVideoValgonoSecondoLaDurata() {
    fun video(ms: Long) = cells.cell(0, kind = MediaKind.VIDEO, durationMs = ms)
    assertThat(score(video(2_999))).isEqualTo(0.0)
    assertThat(score(video(3_000))).isEqualTo(0.5)
    assertThat(score(video(60_000))).isEqualTo(0.5)
    assertThat(score(video(60_001))).isEqualTo(0.0)
    assertThat(score(video(300_000))).isEqualTo(0.0)
    assertThat(score(video(300_001))).isEqualTo(-0.5)
    // La durata di una foto non conta.
    assertThat(score(cells.cell(0, durationMs = 10_000))).isEqualTo(0.0)
  }

  @Test
  fun whatsappPesaMenoUnoEMezzo() {
    assertThat(score(cells.cell(0, source = MediaSource.WHATSAPP))).isEqualTo(-1.5)
    assertThat(score(cells.cell(0, source = MediaSource.QUICK_SHARE))).isEqualTo(0.0)
  }

  /** La luce dal `ThumbHash` (livello su 63): sotto 0,12 o sopra 0,92 costa 1; sulle soglie no. */
  @Test
  fun laLuceDalThumbHash() {
    fun lit(level: Int) = score(cells.cell(0, thumbHash = thumbWithLuma(level)))
    assertThat(lit(0)).isEqualTo(-1.0)
    assertThat(lit(7)).isEqualTo(-1.0) // 7/63 = 0,111
    assertThat(lit(8)).isEqualTo(0.0) // 8/63 = 0,127
    assertThat(lit(32)).isEqualTo(0.0)
    assertThat(lit(57)).isEqualTo(0.0) // 57/63 = 0,905
    assertThat(lit(58)).isEqualTo(-1.0) // 58/63 = 0,921
    assertThat(lit(63)).isEqualTo(-1.0)
    // Senza ThumbHash ne' misura: nessun giudizio sulla luce.
    assertThat(score(cells.cell(0))).isEqualTo(0.0)
  }

  /** La luce misurata sulla miniatura locale vince su quella del `ThumbHash`. */
  @Test
  fun laMisuraDeiPixelVinceSulThumbHash() {
    val cell = cells.cell(0, thumbHash = thumbWithLuma(0))
    assertThat(score(cell, PickSignals(pixels = mapOf(cell.id to PixelProbe(sharpness = 500f, luma = 0.5f))))).isEqualTo(0.0)
    val bright = cells.cell(0, thumbHash = thumbWithLuma(32))
    assertThat(score(bright, PickSignals(pixels = mapOf(bright.id to PixelProbe(sharpness = 500f, luma = 0.95f))))).isEqualTo(-1.0)
  }

  @Test
  fun laLuceAiDueLatiDelleSogliaMisurata() {
    fun lit(luma: Float): Double {
      val cell = cells.cell(0)
      return score(cell, PickSignals(pixels = mapOf(cell.id to PixelProbe(500f, luma))))
    }
    assertThat(lit(0.119f)).isEqualTo(-1.0)
    assertThat(lit(0.5f)).isEqualTo(0.0)
    assertThat(lit(0.93f)).isEqualTo(-1.0)
  }

  /** La nitidezza: sotto 100 costa 1,5, a 100 e oltre no. */
  @Test
  fun laNitidezzaAiDueLatiDellaSoglia() {
    fun sharp(value: Float): Double {
      val cell = cells.cell(0)
      return score(cell, PickSignals(pixels = mapOf(cell.id to PixelProbe(value, 0.5f))))
    }
    assertThat(PickWeights.SHARPNESS_MIN).isEqualTo(100f)
    assertThat(sharp(0f)).isEqualTo(-1.5)
    assertThat(sharp(99.9f)).isEqualTo(-1.5)
    assertThat(sharp(100f)).isEqualTo(0.0)
    assertThat(sharp(400f)).isEqualTo(0.0)
  }

  /** I segnali si sommano. */
  @Test
  fun iSegnaliSiSommano() {
    val cell = cells.cell(0, source = MediaSource.WHATSAPP, flags = CellFlags.FAVORITE or CellFlags.EDITED or CellFlags.PANORAMA)
    val signals = PickSignals(
      albums = setOf(cell.id),
      people = mapOf(cell.id to setOf(person(1))),
      pixels = mapOf(cell.id to PixelProbe(10f, 0.99f)),
    )
    // 3 + 1 (modificata) + 1 (album) + 1 (persona) + 0,5 (panorama) - 1,5 (WhatsApp) - 1 (luce) - 1,5 (mossa)
    assertThat(score(cell, signals)).isWithin(1e-9).of(2.5)
  }

  /** Effetto sulla scelta: i preferiti, le modifiche e gli album passano davanti alle foto qualunque quando il posto manca. */
  @Test
  fun ilPunteggioDecideChiEntra() {
    val spec = PickSpec(MemoryKind.ON_THIS_DAY, minSize = 3, maxSize = 5, perDayMin = 0, perDayMax = 24, perHourMax = 24, dateBound = true)
    val plain = cells.spaced(20, from = 0)
    val edited = cells.cell(500, flags = CellFlags.EDITED)
    val favorite = cells.cell(510, flags = CellFlags.FAVORITE)
    val album = cells.cell(520)
    val result = MemoryPhotoPicker().pick(plain + listOf(edited, favorite, album), spec, PickSignals(albums = setOf(album.id)))
    assertThat(result.members).containsAtLeast(edited.id, favorite.id, album.id)
    assertThat(result.members).hasSize(5)
  }

  /** Una foto buia o mossa passa dopo le altre ma non e' esclusa: se il posto c'e' entra. */
  @Test
  fun unaFotoBruttaNonEEsclusa() {
    val dark = cells.cell(0, thumbHash = thumbWithLuma(0))
    val blurry = cells.cell(10)
    val signals = PickSignals(pixels = mapOf(blurry.id to PixelProbe(5f, 0.5f)))
    val result = MemoryPhotoPicker().pick(cells.spaced(6, from = 100) + dark + blurry, MemoryRules.spec(MemoryKind.ON_THIS_DAY), signals)
    assertThat(result.members).containsAtLeast(dark.id, blurry.id)
    assertThat(result.dropped).isEmpty()
  }

  /** Con il posto contato le brutte restano fuori. */
  @Test
  fun conIlPostoContatoLeBruttePassanoDopo() {
    val spec = PickSpec(MemoryKind.ON_THIS_DAY, minSize = 3, maxSize = 5, perDayMin = 0, perDayMax = 24, perHourMax = 24, dateBound = true)
    val dark = cells.cell(0, thumbHash = thumbWithLuma(0))
    val blurry = cells.cell(10)
    val fine = cells.spaced(5, from = 100)
    val result = MemoryPhotoPicker().pick(listOf(dark, blurry) + fine, spec, PickSignals(pixels = mapOf(blurry.id to PixelProbe(5f, 0.5f))))
    assertThat(result.members).containsExactlyElementsIn(fine.map { it.id })
  }
}
