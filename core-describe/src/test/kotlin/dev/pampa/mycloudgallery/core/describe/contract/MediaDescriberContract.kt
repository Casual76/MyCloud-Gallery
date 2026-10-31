package dev.pampa.mycloudgallery.core.describe.contract

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.describe.testing.FakeA11yActivity
import dev.pampa.mycloudgallery.core.describe.testing.FakeDescribePeople
import dev.pampa.mycloudgallery.core.describe.testing.FakeDescribePlaces
import dev.pampa.mycloudgallery.core.describe.testing.FakeDescribeText
import dev.pampa.mycloudgallery.core.describe.testing.ItalianDescribeStrings
import dev.pampa.mycloudgallery.core.describe.testing.ReferenceDescriber
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.PresenceBadge
import java.time.LocalTime
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La suite di contratto delle descrizioni (Fase 10, P0; `docs/fase10-progetto.md` D22, §2.6, E-AC1-E-AC3, E-AC11, E-AC12). Gira sul riferimento e,
 * da P7, su `DefaultMediaDescriber` (con le parole delle risorse). Nomi e testo sono di prova.
 */
abstract class MediaDescriberContract {

  class Rig(
    val people: FakeDescribePeople = FakeDescribePeople(),
    val text: FakeDescribeText = FakeDescribeText(),
    val a11y: FakeA11yActivity = FakeA11yActivity(),
    val places: FakeDescribePlaces = FakeDescribePlaces(mapOf(OSLO to "Oslo", LISBONA to "Lisbona")),
  )

  protected abstract fun describer(rig: Rig): MediaDescriber

  companion object {
    const val OSLO: Int = 3_143_244
    const val LISBONA: Int = 2_267_057
  }

  private fun id(n: Int) = MediaId(UUID(30L, n.toLong()).toString())

  private val photo = MediaFacts(
    MediaKind.PHOTO, 20240312, LocalTime.of(10, 15), OSLO, null, CellFlags.FAVORITE, 1, false, PresenceBadge.NAS_ONLY,
  )

  @Test
  fun iFattiDiOggiDannoIlTestoDiOggi() {
    val d = describer(Rig())
    assertThat(d.describe(id(1), photo, DescribeSurface.IN_APP)).isEqualTo("Foto, 12 marzo 2024, 10:15, Oslo, preferita, solo sul NAS")
    val video = MediaFacts(MediaKind.VIDEO, 20250803, null, LISBONA, 42_000, CellFlags.EDITED, 1, false, null)
    assertThat(d.describe(id(2), video, DescribeSurface.IN_APP)).isEqualTo("Video, 3 agosto 2025, Lisbona, durata 0:42, modificato")
    val burst = MediaFacts(MediaKind.PHOTO, 20240101, null, null, null, CellFlags.DATE_ESTIMATED, 5, false, PresenceBadge.SAFE)
    assertThat(d.describe(id(3), burst, DescribeSurface.IN_APP)).isEqualTo("Foto, 1 gennaio 2024, data stimata, 5 scatti, al sicuro sul NAS")
  }

  @Test
  fun ilWidgetSoloTipoEDataLaNotificaNiente() = runTest {
    val rig = Rig(people = FakeDescribePeople(mapOf(id(1) to listOf("Anna"))), text = FakeDescribeText(mapOf(id(1) to "Biglietto del treno")))
    val d = describer(rig)
    d.warm(listOf(id(1)))
    assertThat(d.describe(id(1), photo, DescribeSurface.WIDGET)).isEqualTo("Foto, 12 marzo 2024")
    assertThat(d.describe(id(1), photo, DescribeSurface.NOTIFICATION)).isEmpty()
    assertThat(d.describe(id(1), photo, DescribeSurface.HIDDEN_AREA)).isEqualTo(d.describe(id(1), photo, DescribeSurface.IN_APP))
  }

  @Test
  fun personeETestoSoloDopoWarmEConUnoStrumento() = runTest {
    val rig = Rig(
      people = FakeDescribePeople(mapOf(id(1) to listOf("Anna", "Marco"), id(2) to listOf("Anna", "Marco", "Luca", "Sara", "Ugo"))),
      text = FakeDescribeText(mapOf(id(1) to "Biglietto ferroviario Firenze Roma andata e ritorno del 12 marzo con posto 14 carrozza 3")),
    )
    val d = describer(rig)
    assertWithMessage("prima di warm: solo i fatti").that(d.describe(id(1), photo, DescribeSurface.IN_APP)).doesNotContain("Anna")
    rig.a11y.enabled = false
    d.warm(listOf(id(1), id(2)))
    assertWithMessage("senza strumento: niente (E-AC1)").that(d.describe(id(1), photo, DescribeSurface.IN_APP)).doesNotContain("Anna")
    assertThat(rig.people.calls).isEqualTo(0)
    rig.a11y.enabled = true
    d.warm(listOf(id(1), id(2)))
    assertThat(d.describe(id(1), photo, DescribeSurface.IN_APP)).isEqualTo(
      "Foto, 12 marzo 2024, 10:15, Oslo, con Anna e Marco, " +
        "con la scritta «Biglietto ferroviario Firenze Roma andata e ritorno del 12 marzo con posto…», preferita, solo sul NAS",
    )
    assertThat(d.describe(id(2), photo, DescribeSurface.IN_APP)).contains("con Anna, Marco, Luca e altri 2")
    d.forget()
    assertThat(d.describe(id(1), photo, DescribeSurface.IN_APP)).isEqualTo("Foto, 12 marzo 2024, 10:15, Oslo, preferita, solo sul NAS")
  }

  @Test
  fun personeSpenteOLetturaSpentaNonDiconoNiente() = runTest {
    val rig = Rig(people = FakeDescribePeople(mapOf(id(1) to listOf("Anna")), active = false), text = FakeDescribeText(mapOf(id(1) to "Scontrino"), active = false))
    val d = describer(rig)
    d.warm(listOf(id(1)))
    val text = d.describe(id(1), photo, DescribeSurface.IN_APP)
    assertThat(text).doesNotContain("Anna")
    assertThat(text).doesNotContain("Scontrino")
  }

  @Test
  fun ilTestoLettoSiRipulisce() = runTest {
    val hostile = "Ciao‮olleh\u0000 ​mondo\n\nnuova\triga «virgolette» privato"
    val rig = Rig(text = FakeDescribeText(mapOf(id(1) to hostile)))
    val d = describer(rig)
    d.warm(listOf(id(1)))
    val text = d.describe(id(1), photo, DescribeSurface.IN_APP)
    assertThat(text).contains("con la scritta «Ciao olleh mondo nuova riga virgolette privato»")
    for (bad in listOf('‮', '\u0000', '​', '\n', '\t', '')) assertThat(text).doesNotContain(bad.toString())
  }

  @Test
  fun maiUnIdELaCacheHaUnTetto() = runTest {
    val rig = Rig(people = FakeDescribePeople((1..700).associate { id(it) to listOf("Persona $it") }))
    val d = describer(rig)
    d.warm((1..700).map { id(it) })
    assertThat(d.describe(id(700), photo, DescribeSurface.IN_APP)).contains("Persona 700")
    assertWithMessage("le prime sono uscite dalla cache").that(d.describe(id(1), photo, DescribeSurface.IN_APP)).doesNotContain("Persona 1,")
    if (d is ReferenceDescriber) assertThat(d.cached).isAtMost(DescribeLimits.WARM_CACHE)
    val random = Random(5)
    repeat(2_000) {
      val media = id(random.nextInt(1, 701))
      val facts = MediaFacts(
        MediaKind.entries.random(random), 20200101 + random.nextInt(0, 9) * 10_000 + random.nextInt(1, 12) * 100 + random.nextInt(1, 28),
        if (random.nextBoolean()) LocalTime.of(random.nextInt(24), random.nextInt(60)) else null, if (random.nextBoolean()) OSLO else null,
        random.nextLong(0, 4_000_000), random.nextInt(1 shl 17), 1 + random.nextInt(6), random.nextBoolean(),
        PresenceBadge.entries.plus<PresenceBadge?>(null).random(random),
      )
      for (surface in DescribeSurface.entries) {
        val out = d.describe(media, facts, surface)
        assertThat(out).doesNotContain(media.value)
        if (surface == DescribeSurface.WIDGET || surface == DescribeSurface.NOTIFICATION) {
          assertWithMessage("E-AC11").that(out).doesNotContain("Persona")
          assertWithMessage("E-AC11").that(out).doesNotContain("Oslo")
          assertWithMessage("E-AC11").that(out).doesNotContain("NAS")
        }
      }
    }
  }
}

class ReferenceDescriberContractTest : MediaDescriberContract() {
  override fun describer(rig: Rig): MediaDescriber = ReferenceDescriber(rig.places, rig.people, rig.text, ItalianDescribeStrings.VALUE, rig.a11y)
}

/**
 * La suite delle porte delle descrizioni (Fase 10, P0; D22, E-AC2, E-AC3): gira sui fake e, da P7/P13, sugli adattatori veri. Solo gli id
 * chiesti, mai un nome vuoto, al piu' `max` parole, niente quando la funzione e' spenta.
 */
abstract class DescribePortsContract {
  protected abstract fun people(named: Map<MediaId, List<String>>, active: Boolean): DescribePeople

  protected abstract fun text(texts: Map<MediaId, String>, active: Boolean): DescribeText

  private fun id(n: Int) = MediaId(UUID(40L, n.toLong()).toString())

  @Test
  fun lePersone() = runTest {
    val named = mapOf(id(1) to listOf("Anna"), id(2) to listOf("Marco", "Luca"))
    assertThat(people(named, active = true).names(listOf(id(2), id(3))).keys).containsExactly(id(2))
    assertThat(people(named, active = false).names(listOf(id(1), id(2)))).isEmpty()
    assertThat(people(named, active = true).names(listOf(id(1), id(2))).values.flatten().none { it.isBlank() }).isTrue()
  }

  @Test
  fun ilTesto() = runTest {
    val texts = mapOf(id(1) to "uno due tre quattro cinque", id(2) to "solo")
    val words = text(texts, active = true).firstWords(listOf(id(1), id(3)), max = 3)
    assertThat(words).containsExactly(id(1), "uno due tre")
    assertThat(text(texts, active = false).firstWords(listOf(id(1)), max = 3)).isEmpty()
  }
}

class FakeDescribePortsContractTest : DescribePortsContract() {
  override fun people(named: Map<MediaId, List<String>>, active: Boolean): DescribePeople = FakeDescribePeople(named, active)

  override fun text(texts: Map<MediaId, String>, active: Boolean): DescribeText = FakeDescribeText(texts, active)
}
