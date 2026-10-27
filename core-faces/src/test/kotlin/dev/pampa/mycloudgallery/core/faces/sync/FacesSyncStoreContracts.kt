package dev.pampa.mycloudgallery.core.faces.sync

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.data.SnapshotMark
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * LE SUITE DI CONTRATTO DEGLI STATI DI sync/ (Fase 8, P6; `docs/fase8-progetto.md` §5.2): la replica ([FacesReplicaStore]) e il pubblicatore
 * ([FacesPublishStore]) tengono il loro stato dietro due porte, e le regole sono le stesse per la versione in memoria di P3 e per quella sul
 * database dei volti di P6 (cifrata, che sopravvive alla morte del processo): quello che si salva si rilegge uguale, con le voci `cluster`
 * intere; salvare uno stato che cambia in un punto solo non cambia il resto; una generazione che non e' la propria e' vuota; e dopo una
 * "morte del processo" ([reopen]) lo stato salvato c'e'. Per la versione in memoria [reopen] torna lo stesso oggetto.
 */
abstract class FacesReplicaStoreContract {

  protected val generation: FaceGeneration = FaceGeneration.fromBytes(ByteArray(16) { 21 })

  /** Lo store per [generation], vuoto. */
  protected abstract fun create(): FacesReplicaStore

  /** La morte del processo: lo store di prima e' perso, si riapre quello che c'e' su disco (in memoria: lo stesso). */
  protected abstract fun reopen(): FacesReplicaStore

  @Test
  fun unoStatoVuotoSiLegge() = runTest {
    val store = create()
    val state = store.load(generation)
    assertThat(state.devices).isEmpty()
    assertThat(state.revoked).isEmpty()
    assertThat(store.load(FaceGeneration.fromBytes(ByteArray(16) { 22 })).devices).isEmpty()
  }

  @Test
  fun loStatoRiccoVaETornaUgualeAncheDopoLaMorteDelProcesso() = runTest {
    val store = create()
    val state = SyncStateSamples.richReplica()
    store.save(generation, state)
    SyncStateSamples.assertSameReplica(store.load(generation), state)
    SyncStateSamples.assertSameReplica(reopen().load(generation), state)
  }

  @Test
  fun unaGenerazioneCheNonEQuellaDelloStoreEVuota() = runTest {
    val store = create()
    store.save(generation, SyncStateSamples.richReplica())
    assertThat(store.load(FaceGeneration.fromBytes(ByteArray(16) { 23 })).devices).isEmpty()
    SyncStateSamples.assertSameReplica(store.load(generation), SyncStateSamples.richReplica())
  }

  @Test
  fun cambiareUnDispositivoNonCambiaGliAltri() = runTest {
    var store = create()
    val first = SyncStateSamples.richReplica()
    store.save(generation, first)
    // dev-b legge un altro pezzo: testa nuova, un segmento in piu' fatto, l'istantanea di dev-b non c'e'.
    val b = first.devices.getValue("dev-b")
    val progressed = b.copy(mark = HeadMark(4, "testa-2-4"), done = b.done + b.segments[1].ref.hash, counts = b.counts + (b.segments[1].ref.hash to 7))
    val second = first.copy(devices = first.devices + ("dev-b" to progressed))
    store.save(generation, second)
    SyncStateSamples.assertSameReplica(store.load(generation), second)
    store = reopen()
    SyncStateSamples.assertSameReplica(store.load(generation), second)
    // Salvare lo stesso stato due volte e' salvarlo una volta.
    store.save(generation, second)
    SyncStateSamples.assertSameReplica(reopen().load(generation), second)
  }

  @Test
  fun unDispositivoCheEsceEUnRevocatoCheTornaNelloStato() = runTest {
    var store = create()
    store.save(generation, SyncStateSamples.richReplica())
    val rich = SyncStateSamples.richReplica()
    val smaller = FacesReplicaState(rich.devices - "dev-a" - "dev-c" + ("dev-d" to SyncStateSamples.deviceInProgress(4)), setOf("dev-y", "dev-z"))
    store.save(generation, smaller)
    store = reopen()
    SyncStateSamples.assertSameReplica(store.load(generation), smaller)
    store.save(generation, FacesReplicaState.EMPTY)
    store = reopen()
    assertThat(store.load(generation).devices).isEmpty()
    assertThat(store.load(generation).revoked).isEmpty()
  }

  @Test
  fun unaTestaNuovaCambiaISegmentiClusterELIstantanea() = runTest {
    var store = create()
    val first = SyncStateSamples.richReplica()
    store.save(generation, first)
    // La testa nuova di dev-a elenca altri segmenti `cluster` e un'altra istantanea (seq piu' alta): i vecchi blocchi se ne vanno.
    val next = SyncStateSamples.deviceWithSnapshot(seed = 5, persons = 4, perPerson = 7, version = 8)
    val second = first.copy(devices = first.devices + ("dev-a" to next))
    store.save(generation, second)
    store = reopen()
    SyncStateSamples.assertSameReplica(store.load(generation), second)
    // Una testa senza istantanea toglie quella del dispositivo.
    val without = next.copy(snapshotMark = null, snapshot = null, snapshotHead = null, cluster = emptyMap(), checked = null)
    val third = second.copy(devices = second.devices + ("dev-a" to without))
    store.save(generation, third)
    SyncStateSamples.assertSameReplica(reopen().load(generation), third)
  }

  @Test
  fun unaIstantaneaGrandeSiSpezzaESiRilegge() = runTest {
    val store = create()
    // Duemila persone con il centroide e seimila assegnazioni: decine di righe, e nessuna oltre i 2 MB.
    val big = SyncStateSamples.deviceWithSnapshot(seed = 6, persons = 2_000, perPerson = 3)
    val state = FacesReplicaState(mapOf("dev-big" to big), emptySet())
    store.save(generation, state)
    SyncStateSamples.assertSameReplica(reopen().load(generation), state)
  }
}

abstract class FacesPublishStoreContract {

  protected val generation: FaceGeneration = FaceGeneration.fromBytes(ByteArray(16) { 31 })

  protected abstract fun create(): FacesPublishStore

  protected abstract fun reopen(): FacesPublishStore

  private fun key(n: Int): ContentKey = FacesSyncWorld.key(n)

  private fun publication(version: Long, vararg segments: String, snapshot: SnapshotMark? = null) =
    FacesPublication(version, HeadMark(version, "testa-$version"), 1_000L * version, segments.toSet(), snapshot, entries = 10 * version.toInt())

  private fun staged(seed: Int, persons: Int = 3, perPerson: Int = 6, seq: Long = 9): StagedSnapshot =
    StagedSnapshot(SnapshotMark(seq, 4), SyncStateSamples.snapshotEntries(persons, perPerson, seed, seq))

  private fun assertSame(actual: FacesPublishState, expected: FacesPublishState) {
    assertThat(actual.keys).isEqualTo(expected.keys)
    assertThat(actual.publication).isEqualTo(expected.publication)
    assertThat(actual.pending).isEqualTo(expected.pending)
    assertThat(actual.diary.toList()).isEqualTo(expected.diary.toList())
    assertThat(actual.adopted).isEqualTo(expected.adopted)
    assertThat(actual.uncertain).isEqualTo(expected.uncertain)
    assertThat(actual.snapshot == null).isEqualTo(expected.snapshot == null)
    expected.snapshot?.let { want ->
      assertThat(actual.snapshot!!.mark).isEqualTo(want.mark)
      assertThat(actual.snapshot!!.entries.toSet()).isEqualTo(want.entries.toSet())
    }
  }

  private fun rich() = FacesPublishState(
    keys = (1..40).mapTo(LinkedHashSet()) { key(it) },
    snapshot = staged(1),
    publication = publication(5, "a1", "a2", "a3", snapshot = SnapshotMark(7, 3)),
    pending = 3,
    diary = (1..30).mapTo(LinkedHashSet()) { SyncStateSamples.hash(it) },
    adopted = true,
    uncertain = true,
  )

  @Test
  fun unoStatoVuotoSiLegge() = runTest {
    val state = create().load(generation)
    assertThat(state.keys).isEmpty()
    assertThat(state.snapshot).isNull()
    assertThat(state.publication).isNull()
    assertThat(state.pending).isEqualTo(0)
    assertThat(state.diary).isEmpty()
    assertThat(state.adopted).isFalse()
    assertThat(state.uncertain).isFalse()
    assertThat(create().load(FaceGeneration.fromBytes(ByteArray(16) { 32 }))).isEqualTo(FacesPublishState.EMPTY)
  }

  @Test
  fun loStatoRiccoVaETornaUgualeAncheDopoLaMorteDelProcesso() = runTest {
    val store = create()
    store.save(generation, rich())
    assertSame(store.load(generation), rich())
    assertSame(reopen().load(generation), rich())
  }

  @Test
  fun leChiaviSoleSiSalvanoAncheSenzaAltro() = runTest {
    // Il primo salvataggio puo' essere uguale a "niente" negli scalari: le chiavi in coda non devono sparire per questo.
    val store = create()
    val state = FacesPublishState(keys = setOf(key(1), key(2)))
    store.save(generation, state)
    assertThat(reopen().load(generation).keys).containsExactly(key(1), key(2))
  }

  @Test
  fun gliAggiornamentiSiVedono() = runTest {
    var store = create()
    val first = rich()
    store.save(generation, first)
    // Una chiave in piu', una modifica in coda, il diario avanza.
    val second = first.copy(keys = first.keys + key(99), pending = 4, diary = LinkedHashSet(first.diary + SyncStateSamples.hash(31)))
    store.save(generation, second)
    store = reopen()
    assertSame(store.load(generation), second)
    // Il diario perde i piu' vecchi (il tetto) e conserva l'ordine; la testa nuova cambia i segmenti elencati.
    val trimmed = LinkedHashSet(second.diary.drop(25))
    val third = second.copy(
      diary = trimmed,
      publication = publication(6, "b1", "b2"),
      pending = 0,
      uncertain = false,
    )
    store.save(generation, third)
    store = reopen()
    assertSame(store.load(generation), third)
    // Nuovi arrivi nel diario vengono dopo i vecchi, anche dopo una riapertura.
    val fourth = third.copy(diary = LinkedHashSet(third.diary + SyncStateSamples.hash(40) + SyncStateSamples.hash(41)))
    store.save(generation, fourth)
    assertSame(reopen().load(generation), fourth)
    assertThat(reopen().load(generation).diary.toList().takeLast(2)).containsExactly(SyncStateSamples.hash(40), SyncStateSamples.hash(41)).inOrder()
  }

  @Test
  fun lIstantaneaInCodaSiSostituisceESiToglie() = runTest {
    var store = create()
    val first = rich()
    store.save(generation, first)
    val replaced = first.copy(snapshot = staged(2, persons = 4, perPerson = 5, seq = 12))
    store.save(generation, replaced)
    store = reopen()
    assertSame(store.load(generation), replaced)
    val cleared = replaced.copy(snapshot = null, publication = null)
    store.save(generation, cleared)
    store = reopen()
    assertSame(store.load(generation), cleared)
    store.save(generation, FacesPublishState.EMPTY)
    assertSame(reopen().load(generation), FacesPublishState.EMPTY)
  }

  @Test
  fun muoreIlProcessoEMolteChiaviRestano() = runTest {
    val store = create()
    val many = FacesPublishState(keys = (1..3_000).mapTo(LinkedHashSet()) { key(it) }, pending = 3_000, adopted = true)
    store.save(generation, many)
    store.save(generation, many.copy(keys = many.keys + key(5_000), pending = 3_001))
    val back = reopen().load(generation)
    assertThat(back.keys).hasSize(3_001)
    assertThat(back.pending).isEqualTo(3_001)
  }

  @Test
  fun unaIstantaneaGrandeSiSpezzaESiRilegge() = runTest {
    val store = create()
    val state = FacesPublishState(snapshot = staged(3, persons = 2_000, perPerson = 3), pending = 1)
    store.save(generation, state)
    assertSame(reopen().load(generation), state)
  }
}
