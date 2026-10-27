package dev.pampa.mycloudgallery.core.albums.eval

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import java.io.File
import java.util.Locale
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * LA SPECIFICA DEI VIAGGI SINTETICI E I NUMERI ATTESI (riga P12 del §4.4, F87): le 20 storie di P6 scritte come testo per il generatore
 * (`tools/fixtures/trips/generate_trips.py`) e i numeri che la JVM misura **sullo stesso testo riletto**. Scrive in `core-albums/build/trips-fixtures/`:
 * `spec-media.tsv` (una riga per foto), `spec-album.tsv` (gli album di ogni storia con titolo e genere) e `attesi.tsv` (per album e per storia: sicuri,
 * giusti, suggeriti, coperte, precisione e copertura, in due varianti: **completo** -- telefono, tablet e familiari, come in `SmartQualityTest` -- e
 * **telefono** -- solo le foto che si caricano in `mcg-prova`). La prova controlla che il testo sia rileggibile, che le coordinate arrotondate non
 * cambino la misura e che i video disegnati come foto diano gli stessi sicuri e suggeriti.
 */
class TripFixtureExportTest {

  private class Expected(
    val story: Int,
    val album: TripFixtureSpec.Album,
    val variant: String,
    val metrics: TripMetrics,
    val sure: Set<String>,
    val suggested: Set<String>,
  )

  private fun build(): TripFixtureSpec.Spec {
    val stories = SyntheticEvalKit.stories()
    val rows = stories.withIndex().flatMap { (i, s) -> TripFixtureSpec.rowsOf(i, s) }
    val albums = stories.withIndex().flatMap { (i, s) -> TripFixtureSpec.albumsOf(i, s) }
    return TripFixtureSpec.Spec(rows, albums)
  }

  /** La spec scritta e riletta dal testo: e' da questa, mai dalla memoria, che si misura. */
  private fun reread(spec: TripFixtureSpec.Spec): TripFixtureSpec.Spec {
    val (media, albums) = TripFixtureSpec.write(spec)
    return TripFixtureSpec.parse(media, albums)
  }

  private suspend fun measure(spec: TripFixtureSpec.Spec, variant: String, asPhotos: Boolean = false): List<Expected> {
    val out = ArrayList<Expected>()
    for (story in spec.rows.map { it.story }.distinct()) {
      val rows = spec.rows.filter { it.story == story && (variant == "completo" || it.onPhone) }
      val world = TripFixtureSpec.world(rows, asPhotos)
      val runner = AlbumsEvalRunner(SyntheticEvalKit.ports(world))
      for (album in spec.albums.filter { it.story == story }) {
        val truth = rows.filter { album.index in it.albums }.map { dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary.id(it.n).value }.toSet()
        if (truth.isEmpty()) continue
        val run = runner.run("v00", album.title)
        val wrong = (run.sure + run.suggested).filter { it !in truth }.toSet()
        out += Expected(story, album, variant, AlbumsEvalMetrics.judge(run, truth, wrong), run.sure.toSet(), run.suggested.toSet())
      }
    }
    return out
  }

  private fun line(story: String, album: String, title: String, kind: String, variant: String, m: TripMetrics): String =
    listOf(story, album, title, kind, variant, m.truth, m.sure, m.sureRight, m.suggested, m.covered, pct(m.precision), pct(m.coverage)).joinToString("\t")

  private fun pct(v: Double): String = String.format(Locale.ROOT, "%.4f", v * 100)

  @Test
  fun laSpecificaSiScriveESiRilegge() {
    val spec = build()
    val (media, albums) = TripFixtureSpec.write(spec)
    val back = TripFixtureSpec.parse(media, albums)
    assertThat(back.rows).hasSize(spec.rows.size)
    assertThat(back.albums).hasSize(95)
    for ((a, b) in spec.rows.zip(back.rows)) {
      assertThat(b.story).isEqualTo(a.story)
      assertThat(b.n).isEqualTo(a.n)
      assertThat(b.day).isEqualTo(a.day)
      assertThat(b.minute).isEqualTo(a.minute)
      assertThat(b.zone).isEqualTo(a.zone)
      assertThat(b.lat).isEqualTo(a.lat)
      assertThat(b.lon).isEqualTo(a.lon)
      assertThat(b.device).isEqualTo(a.device)
      assertThat(b.source).isEqualTo(a.source)
      assertThat(b.albums).isEqualTo(a.albums)
    }
    // Le 20 storie ci sono tutte e il numero della foto e' unico dentro ogni storia.
    assertThat(spec.rows.map { it.story }.distinct()).containsExactlyElementsIn(0..19)
    for ((_, rows) in spec.rows.groupBy { it.story }) assertThat(rows.map { it.n }.distinct()).hasSize(rows.size)
    println("SPECIFICA: ${spec.rows.size} foto in 20 storie, ${spec.albums.size} album")
    val dir = File("build/trips-fixtures").apply { mkdirs() }
    File(dir, "spec-media.tsv").writeText(media, Charsets.UTF_8)
    File(dir, "spec-album.tsv").writeText(albums, Charsets.UTF_8)
  }

  /** Le storie hanno di che provare il generatore: fusi, giorni senza GPS, screenshot, tablet, familiari, video. */
  @Test
  fun leStorieHannoTuttoCioCheIlGeneratoreDeveSaperScrivere() {
    val rows = build().rows
    assertThat(rows.map { it.zone }.distinct()).containsAtLeast(0, 60, 120, -300, -480, 540, 660, 240)
    assertThat(rows.count { it.lat == null }).isGreaterThan(500)
    assertThat(rows.count { it.screen }).isGreaterThan(100)
    assertThat(rows.count { it.device == TripFixtureSpec.TABLET }).isGreaterThan(50)
    assertThat(rows.count { it.source == SmartSource.FAMILY }).isGreaterThan(50)
    assertThat(rows.count { it.video }).isGreaterThan(100)
    assertThat(rows.count { it.lat != null && it.lat < 0 }).isGreaterThan(0)
    assertThat(rows.count { it.lon != null && it.lon < 0 }).isGreaterThan(0)
    // Mai una foto del familiare con il dispositivo del telefono, mai un tablet che non sia nel backup.
    assertThat(rows.filter { it.source == SmartSource.FAMILY }.map { it.device }.distinct()).containsExactly("-")
    assertThat(rows.filter { it.device == TripFixtureSpec.TABLET }.map { it.source }.distinct()).containsExactly(SmartSource.MY_PRIVATE)
    assertThat(rows.filter { it.onPhone }.map { it.source }.distinct()).containsExactly(SmartSource.MY_PHONE)
  }

  @Test
  fun iNumeriAttesiSulleStorieRilette() = runTest {
    val spec = reread(build())
    val full = measure(spec, "completo")
    val phone = measure(spec, "telefono")
    val fullTotal = AlbumsEvalMetrics.total(full.map { it.metrics })
    val phoneTotal = AlbumsEvalMetrics.total(phone.map { it.metrics })
    println("ATTESI completo: album=${full.size} sicuri=${fullTotal.sure} giusti=${fullTotal.sureRight} verita=${fullTotal.truth} suggeriti=${fullTotal.suggested} coperte=${fullTotal.covered} precisione=${pct(fullTotal.precision)} copertura=${pct(fullTotal.coverage)}")
    println("ATTESI telefono: album=${phone.size} sicuri=${phoneTotal.sure} giusti=${phoneTotal.sureRight} verita=${phoneTotal.truth} suggeriti=${phoneTotal.suggested} coperte=${phoneTotal.covered} precisione=${pct(phoneTotal.precision)} copertura=${pct(phoneTotal.coverage)}")
    val out = StringBuilder("#storia\talbum\ttitolo\tgenere\tvariante\tfoto_viaggio\tsicuri\tgiusti\tsuggeriti\tcoperte\tprecisione\tcopertura\n")
    for (variant in listOf(full, phone)) {
      for (e in variant) out.append(line(e.story.toString(), e.album.index.toString(), e.album.title, e.album.kind, e.variant, e.metrics)).append('\n')
      for ((story, group) in variant.groupBy { it.story }) {
        out.append(line(story.toString(), "*", "totale della storia", "-", group.first().variant, AlbumsEvalMetrics.total(group.map { it.metrics }))).append('\n')
      }
      out.append(line("*", "*", "totale", "-", variant.first().variant, AlbumsEvalMetrics.total(variant.map { it.metrics }))).append('\n')
    }
    File("build/trips-fixtures").apply { mkdirs() }.let { File(it, "attesi.tsv").writeText(out.toString(), Charsets.UTF_8) }

    // Come SmartQualityTest: 95 album, e le coordinate arrotondate a sei decimali non cambiano la misura.
    assertThat(full).hasSize(95)
    assertThat(fullTotal.truth).isEqualTo(14_162)
    assertWithMessage("sicuri").that(fullTotal.sure).isEqualTo(12_411)
    assertWithMessage("giusti").that(fullTotal.sureRight).isEqualTo(12_406)
    // R16: centro su griglia e senza le foto di casa, 14.003 -> 13.999 (vedi SyntheticAlbumsEvalTest).
    assertWithMessage("coperte").that(fullTotal.covered).isEqualTo(13_999)
    assertThat(fullTotal.precision).isAtLeast(0.99)
    assertThat(fullTotal.coverage).isAtLeast(0.95)
    // Solo il telefono (quello che si carica in mcg-prova senza passare dal NAS): le soglie sintetiche valgono ancora.
    assertThat(phone.size).isAtLeast(90)
    assertThat(phoneTotal.truth).isLessThan(fullTotal.truth)
    assertWithMessage("precisione, solo telefono").that(phoneTotal.precision).isAtLeast(0.99)
    assertWithMessage("copertura, solo telefono").that(phoneTotal.coverage).isAtLeast(0.95)
  }

  @Test
  fun iVideoDisegnatiComeFotoDannoGliStessiSicuriEGliStessiSuggeriti() = runTest {
    val spec = reread(build())
    val asVideo = measure(spec, "completo").associateBy { it.story to it.album.index }
    val asPhoto = measure(spec, "completo", asPhotos = true).associateBy { it.story to it.album.index }
    assertThat(asPhoto.keys).isEqualTo(asVideo.keys)
    for ((key, e) in asVideo) {
      assertWithMessage("album $key").that(asPhoto.getValue(key).sure).isEqualTo(e.sure)
      assertWithMessage("album $key").that(asPhoto.getValue(key).suggested).isEqualTo(e.suggested)
    }
  }
}
