package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
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
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * SOL-10 e OA-11 (pacchetto K3b): con la memoria del telefono piena il registro non si scrive, e lo store non deve restare a meta'. Una modifica (o un lotto) che non si e'
 * potuta salvare **non c'e' per nessuno**: ne' sul disco, ne' in `entries` e `effectiveEntries`, ne' fra le modifiche da datare, ne' nel catalogo. Cosi' chi chiama puo'
 * dire "non salvato" senza mentire, e il tasto per riprovare funziona. Un salvataggio **dopo** una modifica gia' fatta (le chiavi portate nel catalogo) che non riesce non
 * annulla la modifica: e' gia' sul disco e nel catalogo.
 */
class LedgerStoreFullStorageTest {

  private fun device(n: Int) = Base32.encode(ByteArray(16) { n.toByte() })

  private val me = device(1)
  private val album = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
  private val fav1 = flagKey(mediaId(1), PersonalFields.FAVORITE)
  private val fav2 = flagKey(mediaId(2), PersonalFields.FAVORITE)
  private val title = PersonalKey(PersonalKind.ALBUM, album, PersonalFields.ALBUM_DEFINITION)
  private val item1 = PersonalKey(PersonalKind.ALBUM, album, PersonalFields.albumItem(mediaId(1)))

  private class RecordingProjection : PersonalProjection {
    val applied = ArrayList<PersonalEntry>()

    override suspend fun apply(entry: PersonalEntry): ProjectionResult {
      applied += entry
      return if (entry.key.kind == PersonalKind.MEDIA_FLAG) ProjectionResult.APPLIED else ProjectionResult.NOT_PROJECTED
    }

    override suspend fun fingerprintOf(key: PersonalKey): PersonalFingerprint? = null

    override suspend fun localFlags(): List<LocalFlag> = emptyList()
  }

  private var nasNow: Long? = NAS_NOW

  private fun store(ledger: InMemoryPersonalLedger, projection: PersonalProjection = RecordingProjection()) = LedgerPersonalStore(me, { nasNow }, ledger, projection)

  @Test
  fun unaModificaConLaMemoriaPienaNonCambiaNienteDaNessunaParte() = runTest {
    val ledger = InMemoryPersonalLedger()
    val store = store(ledger)
    store.set(title, PersonalValue.Text("Mare"))
    val before = store.entries.value
    val savedBefore = ledger.snapshot
    ledger.full = true

    assertThrows(IOException::class.java) { runBlocking { store.set(title, PersonalValue.Text("Monti")) } }

    assertThat(store.entries.value).isEqualTo(before)
    assertThat(store.effectiveEntries.value).isEqualTo(before)
    assertThat(store.state()).isEqualTo(before)
    assertThat((store.get(title)!!.value as PersonalValue.Text).text).isEqualTo("Mare")
    assertThat(ledger.snapshot).isSameInstanceAs(savedBefore)
  }

  @Test
  fun unLottoConLaMemoriaPienaNonLasciaNienteSeNonLaPrimaVoce() = runTest {
    val ledger = InMemoryPersonalLedger()
    val store = store(ledger)
    store.set(title, PersonalValue.Text("Mare"))
    ledger.full = true

    val batch = listOf(
      PersonalChange(title, PersonalValue.Text("Monti")),
      PersonalChange(item1, PersonalValue.Number(1_000)),
    )
    assertThrows(IOException::class.java) { runBlocking { store.setAll(batch) } }

    assertThat(store.entries.value.keys).containsExactly(title)
    assertThat((store.get(title)!!.value as PersonalValue.Text).text).isEqualTo("Mare")
    assertThat(store.get(item1)).isNull()
  }

  @Test
  fun senzaOraDelNasLaModificaNonResta_daDatare_SeNonSiSalva() = runTest {
    nasNow = null
    val ledger = InMemoryPersonalLedger()
    val store = store(ledger)
    ledger.full = true

    assertThrows(IOException::class.java) { runBlocking { store.set(title, PersonalValue.Text("Mare")) } }

    assertThat(store.undated()).isEmpty()
    assertThat(store.effectiveEntries.value).isEmpty()
    assertThat(store.effective()).isEmpty()

    // Con la memoria libera la stessa modifica si salva e resta da datare.
    ledger.full = false
    store.set(title, PersonalValue.Text("Mare"))
    assertThat(store.undated()).containsExactly(title)
    assertThat(ledger.snapshot.pending.keys).containsExactly(title)
  }

  @Test
  fun unPreferitoNonSalvatoNonArrivaNelCatalogoNeResta_daPortare() = runTest {
    val ledger = InMemoryPersonalLedger()
    val projection = RecordingProjection()
    val store = store(ledger, projection)
    ledger.full = true

    assertThrows(IOException::class.java) { runBlocking { store.set(fav1, PersonalValue.Flag(true)) } }

    // L'effetto sul catalogo non c'e': il cuore non si accende per una modifica che non e' stata salvata.
    assertThat(projection.applied).isEmpty()
    assertThat(store.entries.value).isEmpty()

    ledger.full = false
    store.set(fav1, PersonalValue.Flag(true))
    assertThat(projection.applied.map { it.key }).containsExactly(fav1)
    assertThat(ledger.snapshot.unprojected).isEmpty()
  }

  @Test
  fun chiRiprovaDopoAverLiberatoLaMemoriaSalvaTutto() = runTest {
    val ledger = InMemoryPersonalLedger()
    val store = store(ledger)
    ledger.full = true
    val batch = listOf(PersonalChange(title, PersonalValue.Text("Mare")), PersonalChange(item1, PersonalValue.Number(1_000)))
    assertThrows(IOException::class.java) { runBlocking { store.setAll(batch) } }
    assertThat(ledger.snapshot.entries).isEmpty()

    ledger.full = false
    store.setAll(batch)

    assertThat(ledger.snapshot.entries.keys).containsExactly(title, item1)
    assertThat(store.entries.value.keys).containsExactly(title, item1)
  }

  @Test
  fun chiLeggeLoStatoNonVedeMaIlLottoCheNonSiSalva() = runTest {
    val ledger = InMemoryPersonalLedger()
    val store = store(ledger)
    store.open()
    val seen = ArrayList<Set<PersonalKey>>()
    // Un osservatore dello stato (come la proiezione sulle tabelle degli album): non deve mai vedere il lotto che poi non si salva.
    val job = CoroutineScope(Dispatchers.Unconfined).launch { store.effectiveEntries.collect { if (it.isNotEmpty()) seen += it.keys } }
    ledger.full = true
    assertThrows(IOException::class.java) { runBlocking { store.set(title, PersonalValue.Text("Mare")) } }
    job.cancel()

    assertThat(seen.flatten()).isEmpty()
  }

  @Test
  fun seIlSecondoSalvataggioDopoLaProiezioneNonRiesceLaModificaResta() = runTest {
    val ledger = InMemoryPersonalLedger()
    val projection = RecordingProjection()
    val store = store(ledger, projection)
    store.set(title, PersonalValue.Text("Mare"))
    // Il primo salvataggio (la modifica) riesce; il secondo (le chiavi portate nel catalogo) trova la memoria piena.
    ledger.failAfterSaves = ledger.saves + 1

    store.set(fav1, PersonalValue.Flag(true))

    assertThat(store.entries.value.keys).containsExactly(title, fav1)
    // La proiezione ha visto il titolo (la prima modifica) e il preferito: il secondo salvataggio che fallisce non la annulla.
    assertThat(projection.applied.map { it.key }).containsExactly(title, fav1)
    // Sul disco la modifica c'e'; la chiave e' ancora "da portare" (al riavvio `reproject` la rifa').
    assertThat(ledger.snapshot.entries.keys).containsExactly(title, fav1)
    assertThat(ledger.snapshot.unprojected).containsExactly(fav1)

    // Alla prossima scrittura con la memoria libera il registro si allinea.
    ledger.failAfterSaves = Int.MAX_VALUE
    store.set(fav2, PersonalValue.Flag(true))
    assertThat(ledger.snapshot.entries.keys).containsExactly(title, fav1, fav2)
    assertThat(ledger.snapshot.unprojected).isEmpty()
  }
}
