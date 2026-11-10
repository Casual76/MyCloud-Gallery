package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.personal.contract.PersonalChange
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.store.LedgerPersonalStore
import dev.pampa.mycloudgallery.core.personal.store.LocalFlag
import dev.pampa.mycloudgallery.core.personal.store.PersonalProjection
import dev.pampa.mycloudgallery.core.personal.store.ProjectionResult
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryPersonalLedger
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryPersonalStore
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Review generale, OA-1: le modifiche a lotti dello store (`PersonalStore.setAll`). Un lotto si applica in memoria e si salva **una volta
 * sola** (il registro riscrive l'istantanea intera a ogni salvataggio): c'e' tutto o non c'e', e lo stato che ne esce (quello che la
 * sincronizzazione pubblica, le modifiche da datare, l'effetto sul catalogo) e' quello delle stesse modifiche fatte una alla volta.
 */
class LedgerStoreBatchTest {

  private fun device(n: Int) = Base32.encode(ByteArray(16) { n.toByte() })

  private val me = device(1)
  private val other = device(2)
  private val album = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"

  private fun item(n: Int) = PersonalKey(PersonalKind.ALBUM, album, PersonalFields.albumItem(mediaId(n)))

  private fun fp(n: Int) = PersonalFingerprint(5_000L + n, QuickHash("%064x".format(n + 1)))

  private fun place(n: Int) = PersonalChange(item(n), PersonalValue.Number((n + 1) * 1_000L), fp(n))

  /** Una proiezione che registra cio' che le arriva; i flag di un media "sconosciuto" restano in attesa. */
  private class RecordingProjection(private val unknown: Set<String> = emptySet()) : PersonalProjection {
    val applied = ArrayList<PersonalEntry>()

    override suspend fun apply(entry: PersonalEntry): ProjectionResult {
      applied += entry
      return when {
        entry.key.kind != PersonalKind.MEDIA_FLAG -> ProjectionResult.NOT_PROJECTED
        entry.key.subject in unknown -> ProjectionResult.UNRESOLVED
        else -> ProjectionResult.APPLIED
      }
    }

    override suspend fun fingerprintOf(key: PersonalKey): PersonalFingerprint? = null

    override suspend fun localFlags(): List<LocalFlag> = emptyList()

    /** L'ultimo valore arrivato per chiave: cio' che il catalogo ha alla fine. */
    fun last(): Map<PersonalKey, PersonalValue> = applied.associate { it.key to it.value }
  }

  private var nasNow: Long? = NAS_NOW

  private fun store(ledger: InMemoryPersonalLedger = InMemoryPersonalLedger(), projection: PersonalProjection = RecordingProjection()) =
    LedgerPersonalStore(me, { nasNow }, ledger, projection)

  // --- Un salvataggio solo, tutto o niente -------------------------------------------------------------------------------------------

  @Test
  fun unLottoSiApplicaTuttoESiSalvaUnaVoltaSola() = runTest {
    val ledger = InMemoryPersonalLedger()
    val store = store(ledger)
    store.open()

    store.setAll((0 until 1_000).map(::place))

    assertThat(ledger.saves).isEqualTo(1)
    assertThat(ledger.snapshot.entries).hasSize(1_000)
    assertThat(store.entries.value.keys).containsExactlyElementsIn((0 until 1_000).map(::item))
    val e = store.get(item(7))!!
    assertThat(e).isEqualTo(PersonalEntry(item(7), NAS_NOW, me, PersonalValue.Number(8_000), fp(7)))
    assertThat(store.undated()).isEmpty()
  }

  @Test
  fun unLottoVuotoNonFaNiente() = runTest {
    val ledger = InMemoryPersonalLedger()
    val store = store(ledger)
    store.setAll(emptyList())
    assertThat(ledger.saves).isEqualTo(0)
    assertThat(store.entries.value).isEmpty()
  }

  @Test
  fun seIlDiscoCadeSulRegistroNonCeNessunaVoceDelLottoNeSulDiscoNeInMemoria() = runTest {
    val ledger = InMemoryPersonalLedger()
    val first = store(ledger)
    first.set(item(999), PersonalValue.Number(1))
    ledger.failNextSave = true
    assertThrows(IOException::class.java) { runBlocking { first.setAll((0 until 50).map(::place)) } }

    // Sul disco: lo stato di prima, senza nemmeno una voce del lotto. Un processo nuovo non ne vede nessuna (mai un lotto a meta').
    assertThat(ledger.snapshot.entries.keys).containsExactly(item(999))
    assertThat(store(ledger).state().keys).containsExactly(item(999))

    // In memoria nemmeno: il lotto non c'e' per nessuno (tutto o niente), e il salvataggio dopo non lo porta di nascosto sul disco.
    assertThat(first.entries.value.keys).containsExactly(item(999))
    first.set(item(998), PersonalValue.Number(2))
    assertThat(ledger.snapshot.entries.keys).containsExactly(item(999), item(998))
  }

  @Test
  fun rifareLoStessoLottoDopoUnGuastoDelDiscoLoSalva() = runTest {
    val ledger = InMemoryPersonalLedger()
    val store = store(ledger)
    val batch = (0 until 20).map(::place)
    ledger.failNextSave = true
    assertThrows(IOException::class.java) { runBlocking { store.setAll(batch) } }
    assertThat(ledger.snapshot.entries).isEmpty()

    // Chi chiama riprova: il lotto, che non c'era da nessuna parte, ora c'e' ovunque.
    store.setAll(batch)
    assertThat(ledger.snapshot.entries).hasSize(20)
  }

  @Test
  fun unLottoCheNonCambiaNienteNonRiscriveIlRegistro() = runTest {
    val ledger = InMemoryPersonalLedger()
    val store = store(ledger)
    val batch = (0 until 20).map(::place)
    store.setAll(batch)
    val saves = ledger.saves
    val before = store.entries.value

    nasNow = NAS_NOW + 60_000
    store.setAll(batch)

    assertThat(ledger.saves).isEqualTo(saves)
    assertThat(store.entries.value).isEqualTo(before)
  }

  @Test
  fun unTipoRiservatoRifiutaTuttoIlLotto() = runTest {
    val ledger = InMemoryPersonalLedger()
    val store = store(ledger)
    val reserved = PersonalChange(PersonalKey(PersonalKind.PERSON, "p1", "name"), PersonalValue.Text("x"))
    assertThrows(IllegalArgumentException::class.java) { runBlocking { store.setAll(listOf(place(1), reserved, place(2))) } }
    assertThat(store.entries.value).isEmpty()
    assertThat(ledger.saves).isEqualTo(0)
  }

  // --- Ora e modifiche da datare -----------------------------------------------------------------------------------------------------

  @Test
  fun senzaOraDelNasIlLottoRestaDaDatareMaValeSubito() = runTest {
    val ledger = InMemoryPersonalLedger()
    val store = store(ledger)
    nasNow = null

    store.setAll((0 until 30).map(::place))

    assertThat(ledger.saves).isEqualTo(1)
    assertThat(store.entries.value).isEmpty()
    assertThat(store.undated()).containsExactlyElementsIn((0 until 30).map(::item))
    assertThat(store.effectiveEntries.value.keys).containsExactlyElementsIn((0 until 30).map(::item))
    assertThat(ledger.snapshot.pending).hasSize(30)

    store.stamp(NAS_NOW + 5_000)
    assertThat(store.undated()).isEmpty()
    assertThat(store.get(item(3))).isEqualTo(PersonalEntry(item(3), NAS_NOW + 5_000, me, PersonalValue.Number(4_000), fp(3)))
  }

  @Test
  fun laStessaChiaveDueVolteNelLotto_valeLUltima_eNonPrecedeLaPrima() = runTest {
    val store = store()
    store.setAll(listOf(place(1), PersonalChange(item(1), PersonalValue.Removed), place(2)))
    val e = store.get(item(1))!!
    assertThat(e.value).isEqualTo(PersonalValue.Removed)
    // L'impronta della voce resta quella che la chiave aveva; l'ora e' dopo quella della prima modifica (ordine causale).
    assertThat(e.fingerprint).isEqualTo(fp(1))
    assertThat(e.atNas).isEqualTo(NAS_NOW + 1)
    assertThat(store.get(item(2))!!.atNas).isEqualTo(NAS_NOW)
  }

  @Test
  fun unaModificaDelLottoNonPrecedeMaiCioCheIlDispositivoGiaSa() = runTest {
    val store = store()
    // Una voce di un altro dispositivo con l'orologio del NAS piu' avanti di quello che questo dispositivo vede adesso.
    store.applyRemote(listOf(PersonalEntry(item(1), NAS_NOW + 30_000, other, PersonalValue.Number(5))))
    store.setAll(listOf(PersonalChange(item(1), PersonalValue.Removed), place(2)))
    assertThat(store.get(item(1))!!.atNas).isEqualTo(NAS_NOW + 30_001)
    assertThat(store.get(item(1))!!.author).isEqualTo(me)
  }

  // --- Preferiti e nascosti: l'effetto sul catalogo -----------------------------------------------------------------------------------

  @Test
  fun iFlagDiUnLottoArrivanoAlCatalogoEQuelliSenzaMediaRestanoInAttesa_conDueSalvataggiAlPiu() = runTest {
    val ledger = InMemoryPersonalLedger()
    val projection = RecordingProjection(unknown = setOf(mediaId(3)))
    val store = store(ledger, projection)
    val flags = (1..5).map { PersonalChange(flagKey(mediaId(it), PersonalFields.HIDDEN), PersonalValue.Flag(true)) }

    store.setAll(flags + place(9))

    assertThat(projection.last().keys).containsExactlyElementsIn(flags.map { it.key } + item(9))
    assertThat(ledger.snapshot.unprojected).containsExactly(flagKey(mediaId(3), PersonalFields.HIDDEN))
    // Uno con le chiavi "da portare nel catalogo" (prima di toccarlo), uno dopo: mai uno per voce.
    assertThat(ledger.saves).isEqualTo(2)
    assertThat(ledger.snapshot.entries).hasSize(6)
    assertThat(store.reproject()).isEqualTo(1)
  }

  // --- Lo stesso risultato delle modifiche una alla volta -----------------------------------------------------------------------------

  private fun randomChanges(random: Random, count: Int): List<PersonalChange> = List(count) {
    val n = random.nextInt(12)
    when (random.nextInt(6)) {
      0 -> PersonalChange(flagKey(mediaId(n), PersonalFields.FAVORITE), PersonalValue.Flag(random.nextBoolean()))
      1 -> PersonalChange(flagKey(mediaId(n), PersonalFields.HIDDEN), PersonalValue.Flag(random.nextBoolean()))
      2 -> PersonalChange(item(n), PersonalValue.Removed, if (random.nextBoolean()) fp(n) else null)
      3 -> PersonalChange(PersonalKey(PersonalKind.ALBUM, album, PersonalFields.albumAccept(mediaId(n))), PersonalValue.Flag(true), fp(n))
      else -> PersonalChange(item(n), PersonalValue.Number(random.nextLong(1, 20) * 1_000), if (random.nextBoolean()) fp(n) else null)
    }
  }

  @Test
  fun unLottoValeQuantoLeStesseModificheUnaAllaVolta() = runTest {
    for (seed in 0 until 200) {
      val random = Random(seed)
      val oneLedger = InMemoryPersonalLedger()
      val batchLedger = InMemoryPersonalLedger()
      val oneProjection = RecordingProjection(unknown = setOf(mediaId(4)))
      val batchProjection = RecordingProjection(unknown = setOf(mediaId(4)))
      val one = store(oneLedger, oneProjection)
      val batched = store(batchLedger, batchProjection)

      // Tre tempi: con l'ora del NAS, senza (fuori casa), di nuovo con l'ora e la datazione di cio' che era rimasto.
      for (round in 0 until 4) {
        nasNow = if (round % 2 == 1) null else NAS_NOW + round * 10_000L
        val changes = randomChanges(random, random.nextInt(1, 40))
        for (c in changes) one.set(c.key, c.value, c.fingerprint)
        batched.setAll(changes)
        if (round == 2) {
          one.stamp(NAS_NOW + 25_000)
          batched.stamp(NAS_NOW + 25_000)
        }

        val label = "seme $seed, giro $round"
        // Cio' che la sincronizzazione pubblica (valore, autore, impronta), cio' che resta da datare, cio' che l'utente vede.
        assertWithMessage(label).that(batched.state().mapValues { Triple(it.value.value, it.value.author, it.value.fingerprint) })
          .isEqualTo(one.state().mapValues { Triple(it.value.value, it.value.author, it.value.fingerprint) })
        assertWithMessage(label).that(batched.undated().toSet()).isEqualTo(one.undated().toSet())
        assertWithMessage(label).that(batched.effective().mapValues { it.value.value }).isEqualTo(one.effective().mapValues { it.value.value })
        // Il catalogo: lo stesso valore finale per ogni chiave, le stesse voci ancora in attesa del loro media.
        assertWithMessage(label).that(batchProjection.last()).isEqualTo(oneProjection.last())
        assertWithMessage(label).that(batchLedger.snapshot.unprojected).isEqualTo(oneLedger.snapshot.unprojected)
        // E quello che c'e' sul disco e' quello che c'e' in memoria.
        assertWithMessage(label).that(batchLedger.snapshot.entries).isEqualTo(batched.state())
        assertWithMessage(label).that(batchLedger.snapshot.pending.keys).isEqualTo(batched.undated().toSet())
      }
      // L'ora di ogni voce e' quella che avrebbe avuto scritta da sola (un'ora del NAS sola per il lotto, piu' l'ordine causale).
      val oneState = one.state()
      for ((key, entry) in batched.state()) {
        assertWithMessage("seme $seed: ora di ${key.field}").that(entry.atNas).isEqualTo(oneState.getValue(key).atNas)
      }
    }
  }

  // --- Gli store che non hanno un lotto loro -----------------------------------------------------------------------------------------

  @Test
  fun unoStoreSenzaLottoProprioApplicaLeModificheUnaAllaVolta() = runTest {
    val store = InMemoryPersonalStore(me) { NAS_NOW }
    store.setAll(listOf(place(1), place(2), PersonalChange(item(1), PersonalValue.Removed)))
    assertThat(store.entries.value.keys).containsExactly(item(1), item(2))
    assertThat(store.get(item(2))!!.fingerprint).isEqualTo(fp(2))
  }
}
