package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.personal.codec.PersonalSegments
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesRecords
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import dev.pampa.mycloudgallery.core.personal.contract.MemoryLessLevel
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.legacy.LegacyPersonalSegments
import dev.pampa.mycloudgallery.core.personal.settings.PersonalMemories
import dev.pampa.mycloudgallery.core.personal.store.LedgerPersonalStore
import dev.pampa.mycloudgallery.core.personal.store.NoProjection
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryPersonalLedger
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryPersonalStore
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 9 (P0; `docs/fase9-progetto.md` D10, D11, Q9; formato-nas.md §5.5-bis A1, A2): i campi dei ricordi stanno **dentro i tipi esistenti**
 * `MEMORY` e `SETTING`. Un'app della Fase 8 li legge con il suo codec **senza scartare il segmento** e li conserva identici: il codec della
 * classe `personal` non e' cambiato dalla Fase 5 (`5c1138ef`), quindi quello di allora (`legacy/`, copia parola per parola) e' anche quello
 * della Fase 8. In piu': la lettura delle voci (`MemoriesRecords.read`) e `PersonalMemories` con il giorno locale.
 */
class Phase9MemoriesFieldsTest {

  private val device = TrustIds.random()
  private val t = 1_790_000_000_000L
  private val anna = PersonId.fromBytes(ByteArray(16) { (it + 3).toByte() })

  private fun memoryId(n: Int): String = Base32.encode(MessageDigest.getInstance("SHA-256").digest("m$n".toByteArray()).copyOf(16))

  private fun e(key: PersonalKey, at: Long, value: PersonalValue) = PersonalEntry(key, at, device, value)

  /** Uno stato con TUTTI i campi nuovi (e uno vecchio, perche' conviva). */
  private fun phase9State(): List<PersonalEntry> = listOf(
    e(MemoriesRecords.seenKey(memoryId(1)), t + 1, MemoriesRecords.seenValue(20261027)),
    e(MemoriesRecords.dismissedKey(memoryId(2)), t + 2, PersonalValue.Flag(true)),
    e(MemoriesRecords.dismissedKey(memoryId(3)), t + 3, PersonalValue.Removed),
    e(MemoriesRecords.lessKey(anna), t + 4, MemoriesRecords.lessValue(MemoryLessLevel.LESS)),
    e(MemoriesRecords.neverKey(MemoryDays(20240301, 20240315)), t + 5, PersonalValue.Flag(true)),
    e(MemoriesRecords.peopleKey, t + 6, PersonalValue.Flag(false)),
    e(PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, PersonalFields.SETTING_MOTION_ANIMATION), t + 7, PersonalValue.Flag(true)),
  )

  @Test
  fun ilCodecDellaFase8LeggeISegmentiConICampiDeiRicordiSenzaScartarli() {
    val state = phase9State()
    val plan = PersonalSegments.plan(state)
    assertThat(plan.skipped).isEqualTo(0)
    assertThat(plan.entries).isEqualTo(state.size)
    val old = plan.segments.flatMap { LegacyPersonalSegments.decode(it.bytes, it.ref).acceptedOrNull() ?: error("segmento scartato dall'app vecchia") }
    val today = plan.segments.flatMap { PersonalSegments.decode(it.bytes, it.ref).acceptedOrNull().orEmpty() }
    assertThat(old).containsExactlyElementsIn(state)
    assertThat(today).containsExactlyElementsIn(state)
    // E l'app vecchia che ripubblica scrive gli stessi byte: le voci nuove non si perdono passando da lei.
    assertThat(LegacyPersonalSegments.plan(state).segments.map { it.ref.hash }).containsExactlyElementsIn(plan.segments.map { it.ref.hash })
  }

  @Test
  fun unoStoreCheRiceveLoStatoDallAppVecchiaLoTieneIntero() = runTest {
    val state = phase9State()
    val store = LedgerPersonalStore(TrustIds.random(), { t + 1_000 }, InMemoryPersonalLedger(), NoProjection)
    val decoded = PersonalSegments.plan(state).segments.flatMap { LegacyPersonalSegments.decode(it.bytes, it.ref).acceptedOrNull().orEmpty() }
    assertThat(store.applyRemote(decoded).applied).isEqualTo(state.size)
    assertThat(store.state().values).containsExactlyElementsIn(state)
  }

  @Test
  fun laLetturaDelleVociDaLeScelteGiuste() {
    val choices = MemoriesRecords.read(phase9State().associateBy { it.key })
    assertThat(choices.seen).containsExactly(memoryId(1), 20261027)
    assertThat(choices.dismissed).containsExactly(memoryId(2))
    assertThat(choices.people).containsExactly(anna, MemoryLessLevel.LESS)
    assertThat(choices.neverDays).containsExactly(MemoryDays(20240301, 20240315))
    assertThat(choices.peopleMemories).isFalse()
    assertThat(choices.toString()).doesNotContain(memoryId(1))
  }

  /** Le chiavi: forma del formato (A1, A2), mai un nome; i tetti dei campi (128 caratteri) larghi. */
  @Test
  fun leChiaviHannoLaFormaDelFormato() {
    assertThat(MemoriesRecords.seenKey(memoryId(1)).field).isEqualTo("seen")
    assertThat(MemoriesRecords.dismissedKey(memoryId(1)).field).isEqualTo("dismissed")
    assertThat(MemoriesRecords.lessKey(anna).field).isEqualTo("memories_less:person:${anna.base32()}")
    assertThat(MemoriesRecords.neverKey(MemoryDays(20240301, 20240315)).field).isEqualTo("memories_never:days:20240301-20240315")
    assertThat(MemoriesRecords.peopleKey.field).isEqualTo("memories_people")
    assertThat(PersonalFields.memoriesNeverDaysOf("memories_never:days:20240301-20240315")).isEqualTo(20240301 to 20240315)
    assertThat(PersonalFields.memoriesNeverDaysOf("memories_never:days:2024031-20240315")).isNull()
    assertThat(PersonalFields.memoriesLessPersonOf("memories_less:person:${anna.base32()}")).isEqualTo(anna.base32())
    assertThat(MemoriesRecords.isMemoryId(memoryId(9))).isTrue()
    assertThat(MemoriesRecords.isMemoryId("ricordo-2025-10")).isFalse()
  }

  /** I periodi: date vere, non rovesciati, al piu' 366 giorni (D11); il `toString` senza date. */
  @Test
  fun iPeriodiSonoDateVereEBrevi() {
    assertThat(MemoryDays(20240101, 20241231).count).isEqualTo(366)
    assertThat(MemoryDays.parse(20230101, 20240102)).isNull()
    assertThat(MemoryDays.parse(20230101, 20240101)!!.count).isEqualTo(366)
    assertThat(MemoryDays.parse(20240310, 20240301)).isNull()
    assertThat(MemoryDays.parse(20240230, 20240301)).isNull()
    assertThat(MemoryDays.parse(18991231, 18991231)).isNull()
    val days = MemoryDays(20241230, 20250102)
    assertThat(20241231 in days).isTrue()
    assertThat(20250103 in days).isFalse()
    assertThat(days.toString()).isEqualTo("MemoryDays(4 giorni)")
  }

  /** `PersonalMemories`: il giorno locale (D10), l'avanzamento solo in avanti, "Non mostrare" e "Annulla", lo stato effettivo. */
  @Test
  fun personalMemoriesScriveIlGiornoEScarta() = runTest {
    val store = InMemoryPersonalStore(device) { t }
    val memories = PersonalMemories(store)
    assertThat(memories.seenDay(memoryId(1))).isNull()
    memories.markSeen(memoryId(1), 20261027)
    assertThat(memories.seenDay(memoryId(1))).isEqualTo(20261027)
    memories.markSeen(memoryId(1), 20261001)
    assertThat(memories.seenDay(memoryId(1))).isEqualTo(20261027)
    assertThat(memories.isDismissed(memoryId(2))).isFalse()
    memories.dismiss(memoryId(2))
    assertThat(memories.isDismissed(memoryId(2))).isTrue()
    memories.dismiss(memoryId(2), dismissed = false)
    assertThat(memories.isDismissed(memoryId(2))).isFalse()
    assertThat(store.entries.value.getValue(MemoriesRecords.dismissedKey(memoryId(2))).value).isEqualTo(PersonalValue.Removed)
  }
}
