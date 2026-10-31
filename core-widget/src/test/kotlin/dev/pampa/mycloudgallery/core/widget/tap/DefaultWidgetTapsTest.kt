package dev.pampa.mycloudgallery.core.widget.tap

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.testing.FakeVisibility
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.widget.contract.WidgetCell
import dev.pampa.mycloudgallery.core.widget.contract.WidgetCellRef
import dev.pampa.mycloudgallery.core.widget.contract.WidgetLedger
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlan
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTapContract
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTapRequest
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTapUri
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTaps
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTarget
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTitle
import dev.pampa.mycloudgallery.core.widget.testing.InMemoryWidgetLedger
import dev.pampa.mycloudgallery.core.widget.testing.ReferenceWidgetTaps
import java.io.IOException
import java.time.YearMonth
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Fase 10 P2: `DefaultWidgetTaps` passa la suite di contratto del tocco di P0. */
class DefaultWidgetTapsContractTest : WidgetTapContract() {
  override fun taps(): WidgetTaps = DefaultWidgetTaps()
}

/** Fase 10 P2: `WidgetTap.parse` rifiuta tutto cio' che non e' `mcg-widget:<int>/<int>` nei limiti (D9, C17, C433). */
class DefaultWidgetTapsTest {

  private val taps = DefaultWidgetTaps()

  private fun id(n: Int) = MediaId(UUID(60L, n.toLong()).toString())

  private val memory: MemoryId = MemoryIds.of(MemoryKey.Month(YearMonth.of(2025, 3)))

  @Test
  fun ilPuntoDIngressoStaticoEQuelloVero() {
    assertThat(WidgetTap.parse("mcg-widget:12/3")).isEqualTo(WidgetTapRequest(12, 3))
    assertThat(WidgetTap.parse("mcg-widget:12/3x")).isNull()
    assertThat(WidgetTap.parse(null)).isNull()
    assertThat(WidgetTapUri.ACTION).isEqualTo("dev.pampa.mycloudgallery.action.OPEN_WIDGET")
  }

  @Test
  fun iLimitiEsatti() {
    assertThat(taps.parse("mcg-widget:1/0")).isEqualTo(WidgetTapRequest(1, 0))
    assertThat(taps.parse("mcg-widget:2147483647/6")).isEqualTo(WidgetTapRequest(Int.MAX_VALUE, 6))
    assertThat(taps.parse("mcg-widget:2147483647/7")).isNull()
    assertThat(taps.parse("mcg-widget:2147483648/0")).isNull()
    assertThat(taps.parse("mcg-widget:4294967297/0")).isNull() // 2^32 + 1: non deve tornare 1
    assertThat(taps.parse("mcg-widget:0/0")).isNull()
    assertThat(taps.parse("mcg-widget:0000000001/0")).isNull()
    assertThat(taps.parse("mcg-widget:1/00")).isNull()
    assertThat(taps.parse("mcg-widget:1/0000000000")).isNull()
    assertThat(taps.parse("mcg-widget:1/10")).isNull()
    assertThat(taps.parse("mcg-widget:99999999999999999999/1")).isNull()
  }

  @Test
  fun ogniAltraFormaERifiutata() {
    val bad = listOf(
      "mcg-widget:1/1/", "mcg-widget:1/1\n", "mcg-widget:1/1\r", "mcg-widget:1/1\t", "mcg-widget:\t1/1", "mcg-widget: 1/1", "mcg-widget:1 /1",
      "mcg-widget:1/ 1", "mcg-widget:١/1", "mcg-widget:1/١", "mcg-widget:1/1\u200B", "mcg-widget:1.0/1", "mcg-widget:1,0/1", "mcg-widget:0x1/1",
      "mcg-widget:+1/1", "mcg-widget:-1/1", "mcg-widget:1/-1", "mcg-widget:1/+1", "mcg-widget:1//1", "mcg-widget:1\\1", "mcg-widget:1:1",
      "mcg-widget:1/1&x=2", "mcg-widget:1/1;x", "mcg-widget:1/1%00", "mcg-widget://1/1", "mcg-widget:/1/1", "mcg-widget1/1", "mcg-widget/1/1",
      "mcg-widget:", "mcg-widget", "", " ", "mcg-widget:1/1 mcg-widget:2/2", "http://mcg-widget:1/1", "intent:#Intent;end", "content://mcg-widget:1/1",
      "Mcg-widget:1/1", "mcg-Widget:1/1", "mcg_widget:1/1", "mcg-widget;1/1", "mcg-widget:" + "1".repeat(40) + "/1", "x".repeat(10_000),
    )
    for (uri in bad) assertWithMessage("accettato: ${uri.take(40)}").that(taps.parse(uri)).isNull()
  }

  @Test
  fun siComportaComeIlRiferimentoSuDuecentomilaStringheVicineAllaForma() {
    val reference = ReferenceWidgetTaps()
    val random = Random(99)
    val pieces = listOf("mcg-widget:", "mcg-widget", ":", "/", "0", "1", "7", "9", "12", "2147483647", "2147483648", "-", "+", " ", "١", "x", "?", "#", "\u0000", "00", "6")
    var accepted = 0
    repeat(200_000) {
      val text = if (it % 2 == 0) {
        buildString { repeat(random.nextInt(1, 7)) { append(pieces[random.nextInt(pieces.size)]) } }
      } else {
        // Una forma valida con un solo difetto (o nessuno): un carattere cambiato, tolto o aggiunto.
        val base = StringBuilder(WidgetTapUri.format(WidgetTapRequest(random.nextInt(1, 100_000), random.nextInt(0, WidgetTapUri.CELLS))))
        val at = random.nextInt(base.length + 1)
        val glyphs = "0123456789/:- x+\u0000١"
        val glyph = glyphs[random.nextInt(glyphs.length)]
        when (random.nextInt(4)) {
          0 -> if (at < base.length) base.setCharAt(at, glyph)
          1 -> if (at < base.length) base.deleteCharAt(at)
          2 -> base.insert(at, glyph)
        }
        base.toString()
      }
      val ours = taps.parse(text)
      assertWithMessage("stringa: ${text.replace("\u0000", "<NUL>")}").that(ours).isEqualTo(reference.parse(text))
      if (ours != null) {
        accepted++
        assertThat(WidgetTapUri.format(ours)).isEqualTo(text)
      }
    }
    assertThat(accepted).isGreaterThan(20_000)
    // Anche le forme valide fatte bene a caso vanno e vengono.
    repeat(20_000) {
      val request = WidgetTapRequest(random.nextInt(1, Int.MAX_VALUE), random.nextInt(0, WidgetTapUri.CELLS))
      assertThat(taps.parse(WidgetTapUri.format(request))).isEqualTo(request)
    }
  }

  // --- resolve -------------------------------------------------------------------------------------------------------------

  private suspend fun ledgerWithMemory(): InMemoryWidgetLedger = InMemoryWidgetLedger().also {
    it.record(
      WidgetPlan(
        WidgetSlot(4), WidgetSize.WIDE, WidgetMode.Memory(memory, WidgetTitle(null, emptyList(), null, YearMonth.of(2025, 3))),
        listOf(WidgetCell(0, id(1), MediaKind.PHOTO, 20250301), WidgetCell(1, id(2), MediaKind.VIDEO, 20250302)), 1,
      ),
    )
  }

  @Test
  fun unGuastoDelRegistroOGliNascostiNonApreMaiUnMedia() = runTest {
    val ledger = ledgerWithMemory()
    val visibility = FakeVisibility(present = setOf(id(1), id(2)))
    val own = setOf(WidgetSlot(4))
    val brokenLedger = object : WidgetLedger by ledger {
      override suspend fun cell(slot: WidgetSlot, index: Int): WidgetCellRef? = throw IOException("disco")
    }
    assertThat(taps.resolve(WidgetTapRequest(4, 1), own, brokenLedger, visibility, visibility)).isEqualTo(WidgetTarget.Library(notice = false))
    val brokenVisible = object : VisibleMedia by visibility {
      override suspend fun visibleOrThrow(ids: Collection<MediaId>): Set<MediaId> = throw IOException("database")
    }
    assertThat(taps.resolve(WidgetTapRequest(4, 1), own, ledger, brokenVisible, visibility)).isEqualTo(WidgetTarget.Library(notice = false))
    val brokenMask = object : HiddenMask {
      override suspend fun hiddenAmong(ids: Collection<MediaId>): Set<MediaId> = throw IOException("database")
    }
    assertThat(taps.resolve(WidgetTapRequest(4, 1), own, ledger, visibility, brokenMask)).isEqualTo(WidgetTarget.Library(notice = false))
    // Senza guasti il media si apre.
    assertThat(taps.resolve(WidgetTapRequest(4, 1), own, ledger, visibility, visibility)).isEqualTo(WidgetTarget.Viewer(id(2)))
  }

  @Test
  fun unNascostoDelGruppoNonSiApreDalWidget() = runTest {
    val ledger = ledgerWithMemory()
    // Il media 2 e' in un gruppo in cui il 9 e' nascosto: nascosto anche lui (il nascosto e' contagioso).
    val visibility = FakeVisibility(present = setOf(id(1), id(2), id(9)), hidden = setOf(id(9)), groups = listOf(setOf(id(2), id(9))))
    assertThat(taps.resolve(WidgetTapRequest(4, 1), setOf(WidgetSlot(4)), ledger, visibility, visibility)).isEqualTo(WidgetTarget.Library(notice = true))
    assertThat(taps.resolve(WidgetTapRequest(4, 0), setOf(WidgetSlot(4)), ledger, visibility, visibility)).isEqualTo(WidgetTarget.MemoryPage(memory))
  }

  @Test
  fun ilTocchiDiUnEstraneoAprononoAlPiuLaLibreria() = runTest {
    val ledger = ledgerWithMemory()
    val visibility = FakeVisibility(present = setOf(id(1), id(2)))
    val own = setOf(WidgetSlot(4))
    // Un widget che non e' nostro, anche se il registro conosce lo stesso numero (il widget e' stato tolto dalla Home).
    assertThat(taps.resolve(WidgetTapRequest(4, 0), emptySet(), ledger, visibility, visibility)).isEqualTo(WidgetTarget.Library(false))
    // Una cella che il widget non ha (era un messaggio, o ha meno foto).
    assertThat(taps.resolve(WidgetTapRequest(4, 5), own, ledger, visibility, visibility)).isEqualTo(WidgetTarget.Library(false))
    assertThat(taps.resolve(WidgetTapRequest(5, 0), own, ledger, visibility, visibility)).isEqualTo(WidgetTarget.Library(false))
    // Nessun risultato porta un id che non sia nel registro.
    val random = Random(5)
    repeat(2_000) {
      val request = WidgetTapRequest(random.nextInt(1, 10), random.nextInt(0, 7))
      val target = taps.resolve(request, own, ledger, visibility, visibility)
      when (target) {
        is WidgetTarget.Viewer -> assertThat(target.media).isIn(listOf(id(1), id(2)))
        is WidgetTarget.MemoryPage -> assertThat(target.id).isEqualTo(memory)
        is WidgetTarget.Library -> Unit
      }
    }
  }
}
