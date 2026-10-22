package dev.pampa.mycloudgallery.core.albums

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.AlbumTitles
import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationCheck
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationIssuer
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationRules
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationSheet
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemAction
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemState
import dev.pampa.mycloudgallery.core.albums.contract.SharePlan
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanItem
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanKind
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.ShareStateMachine
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumCopies
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.TitleProblem
import dev.pampa.mycloudgallery.core.albums.contract.TripId
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.model.TripKind
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.time.LocalDate
import java.time.MonthDay
import org.junit.Assert.assertThrows
import org.junit.Test

/** I tipi puri dei contratti (P0): titoli, periodi, conferma, macchina a stati, copie di Q5, impronte. */
class ContractTypesTest {

  private val album = AlbumId.derived(AccountId("test"), ByteArray(16))

  private fun plan(hidden: HiddenChoice = HiddenChoice.LEAVE_OUT, created: Long = 1_000, size: Long = 10) = SharePlan(
    ShareRequest.NewAlbum("Oslo", ShareLevel.ADD, listOf(FakeAlbumLibrary.id(1))), SharePlanKind.SHARE, album, "Oslo", "Album condivisi/Oslo",
    ShareLevel.ADD, "00".repeat(16), listOf(SharePlanItem(FakeAlbumLibrary.id(1), ShareItemAction.MOVE_IN, size, false, false)), emptyMap(), hidden, created,
  )

  @Test
  fun iTitoli() {
    assertThat(AlbumTitles.problem("Oslo 2026")).isNull()
    assertThat(AlbumTitles.problem("Viaggio: 1/2 \"bello\"?")).isNull() // i caratteri vietati nei nomi di file vanno bene nel titolo (E-SH22)
    assertThat(AlbumTitles.problem(" \t ")).isEqualTo(TitleProblem.EMPTY)
    assertThat(AlbumTitles.problem("é".repeat(64))).isNull()
    assertThat(AlbumTitles.problem("😀".repeat(65))).isEqualTo(TitleProblem.TOO_LONG)
    assertThat(AlbumTitles.problem("a\u0007b")).isEqualTo(TitleProblem.FORBIDDEN_CHARS)
    assertThat(AlbumTitles.problem("a⁦b")).isEqualTo(TitleProblem.FORBIDDEN_CHARS)
  }

  @Test
  fun iPeriodi() {
    val range = LocalDayRange(LocalDate.of(2025, 12, 30), LocalDate.of(2026, 1, 2))
    assertThat(range.days).isEqualTo(4)
    assertThat(LocalDate.of(2026, 1, 1) in range).isTrue()
    assertThat(range.widen(1).from).isEqualTo(LocalDate.of(2025, 12, 29))
    assertThrows(IllegalArgumentException::class.java) { LocalDayRange(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 1)) }
    val winter = SmartPeriod.EveryYear(MonthDay.of(12, 20), MonthDay.of(1, 6))
    assertThat(LocalDate.of(2019, 12, 31) in winter).isTrue()
    assertThat(LocalDate.of(2030, 1, 6) in winter).isTrue()
    assertThat(LocalDate.of(2030, 1, 7) in winter).isFalse()
    assertThat(LocalDate.of(2030, 6, 1) in SmartPeriod.EveryYear(MonthDay.of(6, 1), MonthDay.of(8, 31))).isTrue()
    assertThrows(IllegalArgumentException::class.java) { SmartPeriod.Ranges(listOf(range, range)) }
  }

  /** C26: la conferma vale per un piano, 10 minuti, non da un altro avvio; il foglio ignora il doppio tocco sulla comparsa. */
  @Test
  fun laConfermaDellaCondivisione() {
    val p = plan()
    val ok = ShareConfirmationIssuer.issue(p, 2_000)
    assertThat(ShareConfirmationRules.check(p, ok, 3_000)).isEqualTo(ShareConfirmationCheck.Valid)
    assertThat(ShareConfirmationRules.check(plan(HiddenChoice.SHARE_TOO), ok, 3_000)).isEqualTo(ShareConfirmationCheck.WrongPlan)
    assertThat(ShareConfirmationRules.check(plan(size = 11), ok, 3_000)).isEqualTo(ShareConfirmationCheck.WrongPlan)
    assertThat(ShareConfirmationRules.check(p, ok, 1_500)).isEqualTo(ShareConfirmationCheck.FromTheFuture)
    assertThat(ShareConfirmationRules.check(p, ok, 2_000 + ShareConfirmationRules.MAX_AGE_MS + 1)).isEqualTo(ShareConfirmationCheck.Expired)
    // Lo stesso piano rifatto piu' tardi ha la stessa impronta (l'istante non conta).
    assertThat(plan(created = 99_000).hash).isEqualTo(p.hash)
    val sheet = ShareConfirmationSheet(p, shownAtMonotonicMs = 10_000)
    assertThat(sheet.tap(10_100)).isNull()
    assertThat(sheet.tap(10_700)!!.planHash).isEqualTo(p.hash)
    assertThat(ok.toString()).doesNotContain(p.hash.hex)
  }

  @Test
  fun iCamminiDegliElementi() {
    val steps = ShareStateMachine.steps(ShareItemAction.MOVE_IN)
    assertThat(steps.last()).isEqualTo(ShareItemState.DONE)
    for ((a, b) in steps.zipWithNext()) assertThat(ShareStateMachine.allowed(ShareItemAction.MOVE_IN, a, b)).isTrue()
    // Nessun salto: la voce solo dopo che il file e' nell'area comune (invariante 6).
    assertThat(ShareStateMachine.allowed(ShareItemAction.MOVE_IN, ShareItemState.TRANSFERRING, ShareItemState.ENTRY_STAGED)).isFalse()
    assertThat(ShareStateMachine.allowed(ShareItemAction.REFERENCE, ShareItemState.PLANNED, ShareItemState.TRANSFERRING)).isFalse()
    assertThat(ShareStateMachine.allowed(ShareItemAction.MOVE_IN, ShareItemState.IN_COMMON, ShareItemState.CANCELLED)).isFalse()
    assertThat(ShareStateMachine.allowed(ShareItemAction.MOVE_OUT, ShareItemState.IN_PRIVATE, ShareItemState.DONE)).isTrue()
    assertThat(ShareStateMachine.allowed(ShareItemAction.MOVE_IN, ShareItemState.DONE, ShareItemState.FAILED)).isFalse()
  }

  /** Q5: la scelta per gli altri c'e' solo alla chiusura; un codice sconosciuto vale "sparisce"; l'id della copia e' per account. */
  @Test
  fun laSceltaDiQ5() {
    val folder = SharePath.of("Album condivisi", "Oslo")
    assertThrows(IllegalArgumentException::class.java) {
      SharedAlbumChange.Definition("Oslo", folder, SharedState.CLOSED, AccountId("test"), "00".repeat(16), null)
    }
    assertThrows(IllegalArgumentException::class.java) {
      SharedAlbumChange.Definition("Oslo", folder, SharedState.ACTIVE, AccountId("test"), "00".repeat(16), OthersAfterClose.KEEP_COPY)
    }
    assertThat(OthersAfterClose.fromCode("keep_copy")).isEqualTo(OthersAfterClose.KEEP_COPY)
    assertThat(OthersAfterClose.fromCode("archive")).isEqualTo(OthersAfterClose.REMOVE)
    val a = SharedAlbumCopies.copyIdFor(album, AccountId("test2"))
    assertThat(SharedAlbumCopies.copyIdFor(album, AccountId("test2"))).isEqualTo(a)
    assertThat(SharedAlbumCopies.copyIdFor(album, AccountId("test"))).isNotEqualTo(a)
    assertThat(a).isNotEqualTo(album)
  }

  @Test
  fun lImprontaDiUnaProposta() {
    val days = LocalDayRange(LocalDate.of(2026, 3, 14), LocalDate.of(2026, 3, 17))
    assertThat(TripId.of(TripKind.TRIP, days, listOf(2, 1))).isEqualTo(TripId.of(TripKind.TRIP, days, listOf(1, 2)))
    assertThat(TripId.of(TripKind.EVENT, days, listOf(1, 2))).isNotEqualTo(TripId.of(TripKind.TRIP, days, listOf(1, 2)))
    assertThat(TripId.of(TripKind.TRIP, days, emptyList()).value).hasLength(64)
  }
}
