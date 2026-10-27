package dev.pampa.mycloudgallery.core.memories

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.CandidateCell
import dev.pampa.mycloudgallery.core.memories.contract.ClipSource
import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.LessChoice
import dev.pampa.mycloudgallery.core.memories.contract.LessLevel
import dev.pampa.mycloudgallery.core.memories.contract.LessTarget
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCard
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTitle
import dev.pampa.mycloudgallery.core.memories.contract.MusicChoice
import dev.pampa.mycloudgallery.core.memories.contract.MusicLicense
import dev.pampa.mycloudgallery.core.memories.contract.MusicPackCatalog
import dev.pampa.mycloudgallery.core.memories.contract.MusicTrackPin
import dev.pampa.mycloudgallery.core.memories.contract.NotifyLedger
import dev.pampa.mycloudgallery.core.memories.contract.NotifyMark
import dev.pampa.mycloudgallery.core.memories.contract.PersonDigest
import dev.pampa.mycloudgallery.core.memories.contract.PersonWindow
import dev.pampa.mycloudgallery.core.memories.contract.ShelfFile
import dev.pampa.mycloudgallery.core.memories.contract.ThumbLight
import dev.pampa.mycloudgallery.core.memories.contract.TripDigestView
import dev.pampa.mycloudgallery.core.memories.contract.ShelfStoreContract
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.model.TripKind
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesRecords
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 9 (P0; `docs/fase9-progetto.md` D9, D33, Q3): l'identita' dei ricordi (deterministica, canonica, accettata dai dati personali), la
 * forma delle chiavi, i `toString()` senza date, luoghi, id o nomi, i tipi che portano tetti e codici.
 */
class ContractTypesTest {

  private val anna = PersonId.fromBytes(ByteArray(16) { (it * 7).toByte() })

  private fun keys(): List<MemoryKey> = listOf(
    MemoryKey.OnThisDay(LocalDate.of(2024, 2, 29)),
    MemoryKey.Trip(DayRange(LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 6))),
    MemoryKey.Event(LocalDate.of(2025, 4, 12), 63),
    MemoryKey.Person(anna, PersonWindow.Year(2024)),
    MemoryKey.Person(anna, PersonWindow.Recent),
    MemoryKey.Month(YearMonth.of(2026, 9)),
  )

  /** D9: lo stesso ricordo ha lo stesso id su ogni dispositivo; id canonici, accettati dalle voci `memory` dei dati personali. */
  @Test
  fun gliIdSonoDeterministiciECanonici() {
    val ids = keys().map { MemoryIds.of(it) }
    assertThat(ids).containsNoDuplicates()
    assertThat(keys().map { MemoryIds.of(it) }).isEqualTo(ids)
    for (id in ids) {
      assertThat(id.value).hasLength(26)
      assertThat(MemoryIds.isCanonical(id.value)).isTrue()
      assertThat(MemoriesRecords.isMemoryId(id.value)).isTrue()
      assertThat(id.toString()).isEqualTo("MemoryId")
    }
    // Mille chiavi a caso: sempre accettate dall'altra parte (la base32 di Crockford di core-trust).
    var day = LocalDate.of(2000, 1, 1)
    repeat(1_000) {
      assertThat(MemoriesRecords.isMemoryId(MemoryIds.of(MemoryKey.OnThisDay(day)).value)).isTrue()
      day = day.plusDays(3)
    }
    assertThrows(IllegalArgumentException::class.java) { MemoryId("0123456789abcdefghjkmnpqrs") }
    assertThrows(IllegalArgumentException::class.java) { MemoryId("0123456789ABCDEFGHJKMNPQR0") }
  }

  /** D9: la forma delle chiavi (entra nell'hash: non cambia mai). */
  @Test
  fun laFormaDelleChiavi() {
    assertThat(keys().map { it.text }).containsExactly(
      "otd:2024-02-29",
      "trip:2025-03-01:2025-03-06",
      "event:2025-04-12:63",
      "person:${anna.base32()}:2024",
      "person:${anna.base32()}:recenti",
      "month:2026-09",
    ).inOrder()
    assertThat(keys().map { it.kind }).containsExactly(
      MemoryKind.ON_THIS_DAY, MemoryKind.TRIP, MemoryKind.EVENT, MemoryKind.PERSON, MemoryKind.PERSON, MemoryKind.MONTH,
    ).inOrder()
    assertThat(MemoryKind.entries.map { it.code }).containsExactly("otd", "trip", "event", "person", "month").inOrder()
    // L'id e' l'hash di tipo e chiave: lo stesso testo con un altro tipo e' un altro ricordo.
    assertThat(MemoryIds.of(MemoryKey.OnThisDay(LocalDate.of(2024, 2, 29)))).isNotEqualTo(MemoryIds.of(MemoryKey.Month(YearMonth.of(2024, 2))))
  }

  /** D33: nessun `toString()` mostra date, luoghi, id, nomi o titoli. */
  @Test
  fun iToStringNonMostranoNiente() {
    val media = MediaId.random()
    val memory = ShelfStoreContract.sampleMemory(MemoryKey.Person(anna, PersonWindow.Year(2024)))
    val title = MemoryTitle(3, DayRange(LocalDate.of(2023, 7, 1), LocalDate.of(2023, 7, 9)), listOf(3_169_070), "Oslo, luglio 2023", anna, null)
    val shown = keys().map { it.toString() } + listOf(
      memory, title, DayRange.of(LocalDate.of(2023, 7, 1)),
      MemoryCard(memory.id, memory.kind, title, media, 12, 20261027, isNew = true),
      TripDigestView(TripKind.TRIP, title.days, "Oslo, luglio 2023", listOf(3_169_070), listOf(media), null, null),
      PersonDigest(anna, listOf(media)),
      CandidateCell(media, 1L, 20230701, MediaKind.PHOTO, MediaSource.CAMERA, 0, 0, 1_333, null, false),
      LessChoice(LessTarget.Person(anna), LessLevel.LESS, "Anna"),
      LessChoice(LessTarget.Days(title.days), LessLevel.NEVER, null),
      ShelfFile(listOf(memory)), MusicChoice.OwnSong("content://documenti/canzone-di-anna"), ClipSource("clip", 8_000),
      PersonWindow.Year(2024), NotifyLedger(listOf(NotifyMark(20261027, special = false))),
    ).map { it.toString() }
    for (text in shown) {
      for (secret in listOf("2023", "2024", "2026", "07-01", "Oslo", "3169070", "Anna", media.value, memory.id.value, anna.value, anna.base32(), "canzone")) {
        assertThat(text).doesNotContain(secret)
      }
    }
  }

  /** D6: la luminanza media dall'intestazione del ThumbHash (`l_dc`, 6 bit). */
  @Test
  fun laLuceDalThumbHash() {
    val dark = ThumbHash.of(byteArrayOf(0, 0, 0, 0, 0))
    val mid = ThumbHash.of(byteArrayOf(32, 0, 0, 0, 0))
    val bright = ThumbHash.of(byteArrayOf(63, 0, 0, 0, 0))
    assertThat(ThumbLight.averageLuma(dark)).isEqualTo(0.0)
    assertThat(ThumbLight.averageLuma(mid)!!).isWithin(1e-9).of(32 / 63.0)
    assertThat(ThumbLight.averageLuma(bright)).isEqualTo(1.0)
  }

  /** I luoghi del titolo: i tre piu' frequenti, a pari frequenza l'id minore, mai lo 0. */
  @Test
  fun iLuoghiPrincipali() {
    assertThat(MemoryTitle.topPlaces(listOf(5, 5, 9, 9, 9, 0, 0, 0, 0, 2, 7, 7))).containsExactly(9, 5, 7).inOrder()
    assertThat(MemoryTitle.topPlaces(emptyList())).isEmpty()
  }

  /** Q3: il catalogo della musica libera e' vuoto finche' l'utente non approva fonti e brani; un brano ha impronta e licenza libera. */
  @Test
  fun laMusicaLiberaAspettaLApprovazione() {
    assertThat(MusicPackCatalog.TRACKS).isEmpty()
    assertThat(MusicPackCatalog.byId("qualunque")).isNull()
    val pin = MusicTrackPin("brano_1", "Titolo", "Autore", MusicLicense.CC0, 3_000_000, "a".repeat(64))
    assertThat(pin.license.code).isEqualTo("cc0")
    assertThrows(IllegalArgumentException::class.java) { MusicTrackPin("Brano 1", "T", "A", MusicLicense.CC0, 1, "a".repeat(64)) }
    assertThrows(IllegalArgumentException::class.java) { MusicTrackPin("brano", "T", "A", MusicLicense.CC0, 1, "xyz") }
    assertThrows(IllegalArgumentException::class.java) { MusicTrackPin("brano", "T", "A", MusicLicense.CC0, 50_000_000, "a".repeat(64)) }
  }

  /** Il registro delle notifiche tiene 14 giorni e un giorno una volta sola. */
  @Test
  fun ilRegistroDelleNotifiche() {
    val today = LocalDate.of(2026, 11, 3)
    var ledger = NotifyLedger()
    for (d in 0L until 20L) ledger = ledger.with(NotifyMark(NotifyLedger.dayOf(today.minusDays(19 - d)), special = false), today)
    assertThat(ledger.marks).hasSize(14)
    ledger = ledger.with(NotifyMark(NotifyLedger.dayOf(today), special = true), today)
    assertThat(ledger.marks.count { it.day == NotifyLedger.dayOf(today) }).isEqualTo(1)
    assertThat(ledger.countIn(today.minusDays(6), today.minusDays(1))).isEqualTo(6)
  }
}
