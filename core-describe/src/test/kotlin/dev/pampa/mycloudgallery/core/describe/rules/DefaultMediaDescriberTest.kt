package dev.pampa.mycloudgallery.core.describe.rules

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.describe.contract.A11yActivity
import dev.pampa.mycloudgallery.core.describe.contract.DescribeFact
import dev.pampa.mycloudgallery.core.describe.contract.DescribeLimits
import dev.pampa.mycloudgallery.core.describe.contract.DescribePeople
import dev.pampa.mycloudgallery.core.describe.contract.DescribeSurface
import dev.pampa.mycloudgallery.core.describe.contract.DescribeText
import dev.pampa.mycloudgallery.core.describe.contract.MediaDescriber
import dev.pampa.mycloudgallery.core.describe.contract.MediaDescriberContract
import dev.pampa.mycloudgallery.core.describe.contract.MediaFacts
import dev.pampa.mycloudgallery.core.describe.noop.NoA11yTool
import dev.pampa.mycloudgallery.core.describe.noop.NoDescribePeople
import dev.pampa.mycloudgallery.core.describe.noop.NoDescribeText
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

/** Il descrittore vero passa la stessa suite di contratto del riferimento (P7). */
class DefaultMediaDescriberContractTest : MediaDescriberContract() {
  override fun describer(rig: Rig): MediaDescriber = DefaultMediaDescriber(rig.places, rig.people, rig.text, ItalianDescribeStrings.VALUE, rig.a11y)
}

/**
 * Fase 10 (P7, D22, §2.6): il descrittore vero contro il riferimento (10.000 combinazioni di fatti e superfici), le condizioni di D22 come
 * proprieta', la ripulitura del testo e la misura (meno di 1 ms per descrizione a cache calda). Nomi e testi sono di prova.
 */
class DefaultMediaDescriberTest {

  private val places = FakeDescribePlaces(mapOf(OSLO to "Oslo", LISBONA to "Lisbona", FIRENZE to "Firenze"))
  private val strings = ItalianDescribeStrings.VALUE

  private fun id(n: Int) = MediaId(UUID(70L, n.toLong()).toString())

  private fun default(people: DescribePeople, text: DescribeText, a11y: A11yActivity = FakeA11yActivity()) =
    DefaultMediaDescriber(places, people, text, strings, a11y)

  private fun randomFacts(random: Random) = MediaFacts(
    kind = MediaKind.entries.random(random),
    localDay = (2020 + random.nextInt(0, 9)) * 10_000 + random.nextInt(1, 13) * 100 + random.nextInt(1, 29),
    time = if (random.nextBoolean()) LocalTime.of(random.nextInt(24), random.nextInt(60)) else null,
    placeId = listOf(null, OSLO, LISBONA, FIRENZE, 999_999).random(random),
    durationMs = random.nextLong(0, 40_000_000),
    flags = random.nextInt(1 shl 17),
    groupSize = 1 + random.nextInt(8),
    similarCopies = random.nextBoolean(),
    presence = PresenceBadge.entries.plus<PresenceBadge?>(null).random(random),
  )

  @Test
  fun stessiTestiDelRiferimentoSuDiecimilaCombinazioni() = runTest {
    val random = Random(10)
    val names = (1..40).associate { id(it) to List(random.nextInt(0, 6)) { n -> "Persona ${it}x$n" } }
    val texts = (1..40).associate { id(it) to List(random.nextInt(0, 20)) { w -> "parola$w" }.joinToString(" ") }
    val mine = default(FakeDescribePeople(names), FakeDescribeText(texts))
    val reference = ReferenceDescriber(places, FakeDescribePeople(names), FakeDescribeText(texts), strings, FakeA11yActivity())
    val warmed = (1..20).map { id(it) }
    mine.warm(warmed)
    reference.warm(warmed)
    repeat(10_000) { n ->
      val media = id(random.nextInt(1, 41))
      val facts = randomFacts(random)
      val surface = DescribeSurface.entries[n % DescribeSurface.entries.size]
      assertThat(mine.describe(media, facts, surface)).isEqualTo(reference.describe(media, facts, surface))
    }
  }

  @Test
  fun personeSpenteEsclusePersonaNascostaNonDiconoNiente() = runTest {
    val named = mapOf(id(1) to listOf("Anna"))
    // Persone spenta qui: la porta non da' nomi.
    val off = default(FakeDescribePeople(named, active = false), FakeDescribeText())
    off.warm(listOf(id(1)))
    assertThat(off.describe(id(1), anyFacts, DescribeSurface.IN_APP)).doesNotContain("Anna")
    // Nessun nome dicibile (tutte nascoste o senza nome: la porta non le da'): niente "con".
    val none = default(NoDescribePeople, NoDescribeText)
    none.warm(listOf(id(1)))
    assertThat(none.describe(id(1), anyFacts, DescribeSurface.IN_APP)).doesNotContain("con ")
    // Errore di una porta = niente nomi, ma il resto della descrizione c'e'.
    val failing = default(DescribePeople { error("errore di prova") }, DescribeText { _, _ -> error("errore di prova") })
    failing.warm(listOf(id(1)))
    assertThat(failing.describe(id(1), anyFacts, DescribeSurface.IN_APP)).startsWith("Foto, 12 marzo 2024")
    // Lettura spenta o esclusa = niente testo.
    val textOff = default(FakeDescribePeople(), FakeDescribeText(mapOf(id(1) to "Scontrino"), active = false))
    textOff.warm(listOf(id(1)))
    assertThat(textOff.describe(id(1), anyFacts, DescribeSurface.IN_APP)).doesNotContain("Scontrino")
  }

  @Test
  fun unNomeToltoDaPersoneDopoIlWarmNonRestaInCache() = runTest {
    val people = FakeDescribePeople(mapOf(id(1) to listOf("Anna")))
    val d = default(people, FakeDescribeText())
    d.warm(listOf(id(1)))
    assertThat(d.describe(id(1), anyFacts, DescribeSurface.IN_APP)).contains("con Anna")
    people.names.clear() // la persona e' stata nascosta o dimenticata
    d.warm(listOf(id(1)))
    assertThat(d.describe(id(1), anyFacts, DescribeSurface.IN_APP)).doesNotContain("Anna")
    assertThat(d.cached).isEqualTo(0)
  }

  @Test
  fun alMassimoTreNomiPoiAltri() = runTest {
    val d = default(FakeDescribePeople(mapOf(id(1) to listOf("Anna", "Marco", "Luca", "Sara", "Ugo", "Elsa"))), FakeDescribeText())
    d.warm(listOf(id(1)))
    assertThat(d.describe(id(1), anyFacts, DescribeSurface.IN_APP)).contains("con Anna, Marco, Luca e altri 3")
  }

  @Test
  fun widgetENotificaNonDiconoMaiPersoneTestoLuogoOPresenza() = runTest {
    val d = default(FakeDescribePeople((1..30).associate { id(it) to listOf("Anna") }), FakeDescribeText((1..30).associate { id(it) to "Scontrino segreto" }))
    d.warm((1..30).map { id(it) })
    val random = Random(3)
    repeat(2_000) {
      val facts = randomFacts(random)
      val widget = d.describe(id(random.nextInt(1, 31)), facts, DescribeSurface.WIDGET)
      assertThat(widget).doesNotContain("Anna")
      assertThat(widget).doesNotContain("Scontrino")
      assertThat(widget).doesNotContain("Oslo")
      assertThat(widget.split(", ")).hasSize(2) // tipo e data, niente altro
      assertThat(d.describe(id(1), facts, DescribeSurface.NOTIFICATION)).isEmpty()
    }
  }

  @Test
  fun warmSoloConUnoStrumentoAttivoEForgetVuotaLaCache() = runTest {
    val people = FakeDescribePeople(mapOf(id(1) to listOf("Anna")))
    val a11y = FakeA11yActivity(enabled = false)
    val d = default(people, FakeDescribeText(), a11y)
    d.warm(listOf(id(1)))
    assertThat(people.calls).isEqualTo(0)
    assertThat(d.cached).isEqualTo(0)
    a11y.enabled = true
    d.warm(listOf(id(1)))
    assertThat(people.calls).isEqualTo(1)
    assertThat(d.cached).isEqualTo(1)
    // Lo strumento si spegne: la descrizione smette di usare la cache.
    a11y.enabled = false
    assertThat(d.describe(id(1), anyFacts, DescribeSurface.IN_APP)).doesNotContain("Anna")
    a11y.enabled = true
    d.forget()
    assertThat(d.cached).isEqualTo(0)
    assertThat(default(people, FakeDescribeText(), NoA11yTool).also { it.warm(listOf(id(1))) }.cached).isEqualTo(0)
  }

  @Test
  fun laCacheHaUnTettoDi500() = runTest {
    val people = FakeDescribePeople((1..900).associate { id(it) to listOf("Persona $it") })
    val d = default(people, FakeDescribeText())
    d.warm((1..900).map { id(it) })
    assertThat(d.cached).isEqualTo(DescribeLimits.WARM_CACHE)
    assertThat(d.describe(id(900), anyFacts, DescribeSurface.IN_APP)).contains("Persona 900")
    assertThat(d.describe(id(1), anyFacts, DescribeSurface.IN_APP)).doesNotContain("Persona")
  }

  @Test
  fun iNomiDiUnaPortaOstileSiRipulisconoENonEsceMaiUnId() = runTest {
    val hostile = mapOf(id(1) to listOf("Anna\u202Eevil\u0000\nCapo", "   ", "A".repeat(200)))
    val d = default(FakeDescribePeople(hostile), FakeDescribeText())
    d.warm(listOf(id(1)))
    val out = d.describe(id(1), anyFacts, DescribeSurface.IN_APP)
    for (bad in listOf('\u202E', '\u0000', '\n', '\t')) assertThat(out).doesNotContain(bad.toString())
    assertThat(out).contains("Anna evil Capo")
    assertThat(out).doesNotContain("A".repeat(41))
    assertThat(out).doesNotContain(id(1).value)
  }

  @Test
  fun menoDiUnMillisecondoPerDescrizioneACacheCalda() = runTest {
    val media = (1..500).map { id(it) }
    val d = default(
      FakeDescribePeople(media.associateWith { listOf("Anna", "Marco", "Luca", "Sara") }),
      FakeDescribeText(media.associateWith { "Biglietto ferroviario Firenze Roma andata e ritorno del 12 marzo con posto 14 carrozza 3" }),
    )
    d.warm(media)
    val facts = MediaFacts(MediaKind.VIDEO, 20240312, LocalTime.of(10, 15), OSLO, 72_000, CellFlags.FAVORITE or CellFlags.EDITED, 3, true, PresenceBadge.NAS_ONLY)
    repeat(2_000) { d.describe(media[it % media.size], facts, DescribeSurface.IN_APP) } // riscaldamento della JVM
    val runs = 10_000
    val start = System.nanoTime()
    var length = 0
    repeat(runs) { length += d.describe(media[it % media.size], facts, DescribeSurface.IN_APP).length }
    val perCallNs = (System.nanoTime() - start) / runs
    assertThat(length).isGreaterThan(0)
    assertWithMessage("per descrizione: $perCallNs ns").that(perCallNs).isLessThan(1_000_000L)
  }

  // ---- le regole ----

  @Test
  fun laTabellaDelleSuperficiEQuellaDelleRegole() {
    for (surface in DescribeSurface.entries) assertThat(DescriptionRules.facts(surface)).isEqualTo(dev.pampa.mycloudgallery.core.describe.contract.DescribeSurfaces.facts(surface))
    assertThat(DescriptionRules.allowsPersonalFacts(DescribeSurface.IN_APP)).isTrue()
    assertThat(DescriptionRules.allowsPersonalFacts(DescribeSurface.HIDDEN_AREA)).isTrue()
    assertThat(DescriptionRules.allowsPersonalFacts(DescribeSurface.WIDGET)).isFalse()
    assertThat(DescriptionRules.allowsPersonalFacts(DescribeSurface.NOTIFICATION)).isFalse()
    assertThat(DescriptionRules.facts(DescribeSurface.WIDGET)).containsExactly(DescribeFact.KIND, DescribeFact.DATE)
  }

  @Test
  fun ilTestoSiRipulisceESiTagliaADodiciParole() {
    assertThat(DescriptionRules.cleanText("Ciao\u202Eolleh\u0000 \u200Bmondo\n\nnuova\triga «virgolette» fine")).isEqualTo("Ciao olleh mondo nuova riga virgolette fine")
    assertThat(DescriptionRules.cleanText("a\u2028b\u2029c\u00A0d")).isEqualTo("a b c d")
    assertThat(DescriptionRules.cleanText("   \n\t ")).isNull()
    assertThat(DescriptionRules.cleanText("")).isNull()
    assertThat(DescriptionRules.cleanText("e\u0301")).isEqualTo("\u00E9") // NFC
    val twelve = (1..12).joinToString(" ") { "p$it" }
    assertThat(DescriptionRules.cleanText(twelve)).isEqualTo(twelve)
    assertThat(DescriptionRules.cleanText("$twelve p13")).isEqualTo("$twelve…")
    assertThat(DescriptionRules.cleanText("uno due tre", maxWords = 2)).isEqualTo("uno due…")
    // Una coppia di surrogati valida (un emoji) resta; una meta' sola no.
    assertThat(DescriptionRules.cleanText("ciao \uD83D\uDE00 x\uD83D")).isEqualTo("ciao \uD83D\uDE00 x")
  }

  @Test
  fun ilTestoRipulitoNonHaMaiControlliDirezioniOACapo() {
    val random = Random(11)
    val pool = "abc XYZ\n\r\t\u0000\u0007\u202E\u202D\u2066\u2069\u200B\u200F\u061C\u2028\u2029\u00A0«»\uFEFF\uE000\uD800 "
    repeat(5_000) {
      val raw = String(CharArray(random.nextInt(0, 60)) { pool[random.nextInt(pool.length)] })
      val clean = DescriptionRules.cleanText(raw) ?: return@repeat
      for (ch in clean) {
        val type = Character.getType(ch)
        assertWithMessage("U+${ch.code.toString(16)} in $clean").that(
          type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() || type == Character.LINE_SEPARATOR.toInt() ||
            type == Character.PARAGRAPH_SEPARATOR.toInt() || type == Character.PRIVATE_USE.toInt() || type == Character.SURROGATE.toInt() ||
            ch == '«' || ch == '»',
        ).isFalse()
      }
      assertThat(clean).doesNotContain("  ")
      assertThat(clean).isEqualTo(clean.trim())
    }
  }

  @Test
  fun unNomeLungoSiTagliaSenzaSpezzareUnaCoppiaDiSurrogati() {
    val name = "A".repeat(39) + "\uD83D\uDE00" + "B"
    val clean = DescriptionRules.cleanName(name)!!
    assertThat(clean.length).isAtMost(DescriptionRules.MAX_NAME_CHARS)
    assertThat(clean.endsWith("\uD83D")).isFalse()
    assertThat(DescriptionRules.cleanName("  ")).isNull()
  }

  private val anyFacts = MediaFacts(MediaKind.PHOTO, 20240312, null, null, null, 0, 1, false, null)

  private companion object {
    const val OSLO: Int = 3_143_244
    const val LISBONA: Int = 2_267_057
    const val FIRENZE: Int = 3_176_959
  }
}

