package dev.pampa.mycloudgallery.core.faces.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.store.FaceFixtures.indexOf
import dev.pampa.mycloudgallery.core.faces.sync.DeviceReplica
import dev.pampa.mycloudgallery.core.faces.sync.FacesPublishState
import dev.pampa.mycloudgallery.core.faces.sync.FacesReplicaState
import dev.pampa.mycloudgallery.core.faces.sync.StagedSnapshot
import dev.pampa.mycloudgallery.core.faces.sync.SyncStateSamples
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.trust.data.ClusterEntry
import dev.pampa.mycloudgallery.core.trust.data.FacesDocs
import dev.pampa.mycloudgallery.core.trust.data.SnapshotMark
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Fase 8 (P6, D14, C39): cio' che gli stati della replica e del pubblicatore sul database dei volti garantiscono oltre alle suite di contratto
 * (`FacesReplicaStoreContract`, `FacesPublishStoreContract`) e alle prove di P3. **I centroidi e le assegnazioni non sono in chiaro nel file**
 * (con il controllo positivo sul segmento non sigillato), nessuna riga oltre il milione di byte, una scrittura che fallisce lascia lo stato di
 * prima, un blocco alterato fa rifare cio' che conteneva senza rompere il resto, e dopo la cancellazione gli stati lanciano.
 */
@RunWith(AndroidJUnit4::class)
class RoomFacesSyncStoresTest {

  @get:Rule
  val folder = TemporaryFolder()

  private val world by lazy { StoreWorld(File(folder.root, "faces")) }
  private val generation = FaceGeneration.fromBytes(ByteArray(16) { 41 })

  private fun sql(store: FacesStore) = store.database.openHelper.writableDatabase

  private fun centroidWindows(entries: List<ClusterEntry>): List<ByteArray> =
    entries.filterIsInstance<ClusterEntry.Person>().flatMap { p ->
      listOf(FaceFixtures.bigEndian(p.centroid, 0, 8), FaceFixtures.bigEndian(p.centroid, 60, 8))
    }

  @Test
  fun iCentroidiEIRiferimentiDelleVociClusterNonSonoInChiaroNelFile() = runBlocking<Unit> {
    val store = world.ready(generation)
    val replica = SyncStateSamples.richReplica()
    store.replicaStore.save(generation, replica)
    val staged = StagedSnapshot(SnapshotMark(9, 4), SyncStateSamples.snapshotEntries(5, 8, seed = 7))
    store.publishStore.save(generation, FacesPublishState(snapshot = staged, pending = 2))
    val entries = replica.devices.values.flatMap { it.cluster.values.flatten() } + staged.entries
    val windows = centroidWindows(entries)
    val keys = entries.filterIsInstance<ClusterEntry.Assignment>().map { it.face.contentKey.bytes() }
    assertThat(windows).isNotEmpty()
    assertThat(keys).isNotEmpty()

    // Il controllo positivo: nel segmento non sigillato i centroidi e le chiavi di contenuto ci sono (il cercatore li vede).
    val raw = FacesDocs.clusterSegment(staged.entries.take(64)).bytes
    assertWithMessage("il cercatore non vede un centroide nel segmento non sigillato").that(centroidWindows(staged.entries.take(64)).any { indexOf(raw, it) >= 0 }).isTrue()
    assertThat(keys.any { indexOf(FacesDocs.clusterSegment(staged.entries.filterIsInstance<ClusterEntry.Assignment>().take(64)).bytes, it) >= 0 }).isTrue()

    for (phase in listOf("aperto", "chiuso")) {
      val files = world.bytesOf(generation)
      assertThat(files).isNotEmpty()
      for (file in files) {
        for (window in windows) assertWithMessage("un centroide e' in chiaro ($phase)").that(indexOf(file, window)).isEqualTo(-1)
        for (key in keys) assertWithMessage("una chiave di contenuto e' in chiaro nelle assegnazioni ($phase)").that(indexOf(file, key)).isEqualTo(-1)
      }
      if (phase == "aperto") store.close()
    }
  }

  @Test
  fun nessunaRigaDeiBlocchiSuperaIlMilioneDiByte() = runBlocking<Unit> {
    val store = world.ready(generation)
    store.replicaStore.save(generation, FacesReplicaState(mapOf("dev-big" to SyncStateSamples.deviceWithSnapshot(seed = 8, persons = 1_500, perPerson = 4)), emptySet()))
    store.publishStore.save(generation, FacesPublishState(snapshot = StagedSnapshot(SnapshotMark(9, 4), SyncStateSamples.snapshotEntries(1_500, 4, seed = 9))))
    sql(store).query("SELECT COUNT(*), MAX(LENGTH(data)) FROM faces_chunk").use { c ->
      c.moveToFirst()
      println("[sync] blocchi: ${c.getInt(0)}, la riga piu' grande: ${c.getInt(1)} byte")
      assertThat(c.getInt(0)).isAtLeast(10)
      assertThat(c.getInt(1)).isLessThan(1_000_000)
    }
    store.close()
  }

  @Test
  fun unaScritturaCheFallisceLasciaLoStatoDiPrima() = runBlocking<Unit> {
    val store = world.ready(generation)
    val good = SyncStateSamples.richReplica()
    store.replicaStore.save(generation, good)
    // Voci ripetute in un segmento: il formato le rifiuta, la scrittura lancia prima di toccare il database.
    val a = good.devices.getValue("dev-a")
    val hash = a.cluster.keys.first()
    val broken = good.copy(devices = good.devices + ("dev-a" to a.copy(cluster = a.cluster + ("nuovo" to a.cluster.getValue(hash) + a.cluster.getValue(hash)))))
    val failure = runCatching { store.replicaStore.save(generation, broken) }
    assertThat(failure.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    SyncStateSamples.assertSameReplica(store.replicaStore.load(generation), good)
    store.close()
    SyncStateSamples.assertSameReplica(world.ready(generation).replicaStore.load(generation), good)
  }

  @Test
  fun unBloccoAlteratoFaRifareCioCheContenevaSenzaRompereIlResto() = runBlocking<Unit> {
    val store = world.ready(generation)
    val state = SyncStateSamples.richReplica()
    store.replicaStore.save(generation, state)
    store.publishStore.save(generation, FacesPublishState(snapshot = StagedSnapshot(SnapshotMark(9, 4), SyncStateSamples.snapshotEntries(3, 4, 2)), pending = 5))
    val a = state.devices.getValue("dev-a")
    val clusterHash = a.cluster.keys.first()
    var current = store

    /** Cambia un byte in mezzo ai blocchi scelti, come farebbe un disco che si rovina o qualcuno che ci mette le mani; poi riapre il negozio. */
    suspend fun flipAndReopen(where: String): FacesStore {
      val sql = sql(current)
      val rows = sql.query("SELECT scope, owner, name, part, data FROM faces_chunk WHERE $where").use { c ->
        buildList { while (c.moveToNext()) add(listOf(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getBlob(4))) }
      }
      assertThat(rows).isNotEmpty()
      for (row in rows) {
        val data = (row[4] as ByteArray).also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        sql.execSQL("UPDATE faces_chunk SET data = ? WHERE scope = ? AND owner = ? AND name = ? AND part = ?", arrayOf<Any>(data, row[0]!!, row[1]!!, row[2]!!, row[3]!!))
      }
      current.close()
      current = world.ready(generation)
      return current
    }

    // Un segmento `cluster` alterato: torna "da fare" (non e' piu' fatto, l'istantanea si rigiudica); l'altro e gli altri dispositivi restano.
    var read = flipAndReopen("scope = 'replica.cluster' AND owner = 'dev-a' AND name = '$clusterHash'").replicaStore.load(generation)
    val readA: DeviceReplica = read.devices.getValue("dev-a")
    assertThat(clusterHash in readA.done).isFalse()
    assertThat(clusterHash in readA.counts).isFalse()
    assertThat(readA.checked).isNull()
    assertThat(readA.cluster.keys).isEqualTo(a.cluster.keys - clusterHash)
    assertThat(readA.snapshot).isNotNull() // l'istantanea ha i suoi blocchi
    SyncStateSamples.assertSameReplica(
      FacesReplicaState(mapOf("dev-b" to read.devices.getValue("dev-b")), read.revoked),
      FacesReplicaState(mapOf("dev-b" to state.devices.getValue("dev-b")), state.revoked),
    )

    // L'istantanea alterata: il dispositivo la perde (e con lei la testa da cui veniva), il resto resta.
    read = flipAndReopen("scope = 'replica.snapshot' AND owner = 'dev-a'").replicaStore.load(generation)
    assertThat(read.devices.getValue("dev-a").snapshot).isNull()
    assertThat(read.devices.getValue("dev-a").snapshotHead).isNull()

    // Quella in coda del pubblicatore: persa, il resto dello stato no.
    val publish = flipAndReopen("scope = 'publish.snapshot'").publishStore.load(generation)
    assertThat(publish.snapshot).isNull()
    assertThat(publish.pending).isEqualTo(5)
    current.close()
  }

  @Test
  fun dopoLaCancellazioneGliStatiLanciano() = runBlocking<Unit> {
    val store = world.ready(generation)
    store.replicaStore.save(generation, SyncStateSamples.richReplica())
    val replica = store.replicaStore
    val publish = store.publishStore
    assertThat(world.stores.eraseLocal(generation).done).isTrue()
    assertThat(runCatching { replica.load(generation) }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    assertThat(runCatching { replica.save(generation, FacesReplicaState.EMPTY) }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    assertThat(runCatching { publish.load(generation) }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    // E uno stato di un'altra generazione non si scrive in questo database.
    val fresh = world.ready(generation)
    val other = FaceGeneration.fromBytes(ByteArray(16) { 42 })
    assertThat(runCatching { fresh.publishStore.save(other, FacesPublishState(pending = 1)) }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    assertThat(fresh.publishStore.load(other)).isEqualTo(FacesPublishState.EMPTY)
    fresh.close()
  }

  @Test
  fun lePortePerLaSincronizzazioneSiApronoDaSole() = runBlocking<Unit> {
    val recovered = mutableListOf<FacesRecovery>()
    val stores = FacesStores(world.directory, dev.pampa.mycloudgallery.core.database.faces.FacesDatabases.forContext(world.context), world.keys, onRecovered = { recovered += it })
    val archives = stores.archives()
    val replica = stores.replicaStore()
    val publish = stores.publishStore()
    // Il negozio si apre alla prima richiesta; archivio, replica e pubblicatore sono quelli dello stesso database.
    val archive = archives.open(generation)!!
    assertThat(archive.generation).isEqualTo(generation)
    replica.save(generation, SyncStateSamples.richReplica())
    publish.save(generation, FacesPublishState(pending = 7))
    assertThat(stores.hasFiles(generation)).isTrue()
    SyncStateSamples.assertSameReplica(replica.load(generation), SyncStateSamples.richReplica())
    assertThat(publish.load(generation).pending).isEqualTo(7)
    assertThat(recovered).isEmpty()

    // La chiave sparita (E-F19): il negozio riparte vuoto, chi ascolta lo sa, e la replica e il pubblicatore sono vuoti.
    (stores.open(generation) as FacesStoreOpen.Ready).store.close()
    world.fake.lose(generation)
    assertThat(replica.load(generation).devices).isEmpty()
    assertThat(publish.load(generation).pending).isEqualTo(0)
    assertThat(recovered).containsExactly(FacesRecovery.KEY_LOST)
    (stores.open(generation) as FacesStoreOpen.Ready).store.close()
  }

  @Test
  fun iNuoviMetodiDellArchivioVeroSullaCacheDeiVolti() = runBlocking<Unit> {
    val store = world.ready(generation)
    val archive = store.archive
    val small = FaceFixtures.AUTHOR_SMALL
    val big = FaceFixtures.AUTHOR_BIG
    val person = PersonId.fromBytes(ByteArray(16) { 9 })
    archive.saveBody(FaceFixtures.body(FaceFixtures.key(1), small, faces = listOf(FaceFixtures.entry(0, FaceTier.CORE, seed = 1))))
    archive.saveBody(FaceFixtures.body(FaceFixtures.key(1), big, recipe = 2, faces = listOf(FaceFixtures.entry(0, FaceTier.CORE, seed = 2), FaceFixtures.entry(1, FaceTier.WEAK, seed = 3))))
    archive.addPeople(listOf(PeopleRecord(PeopleEdit.Name(person, "Nome-Di-Prova"), 10, big), PeopleRecord(PeopleEdit.Hidden(person, true), null, small)))
    // La cache dei volti da raggruppare e' calda: il vincitore e' quello di `big` (ricetta 2, due volti).
    assertThat(archive.groupFaces()).hasSize(2)
    // Un autore revocato: i suoi corpi e le sue voci spariscono, il vincitore torna a `small`, e i volti da raggruppare lo seguono.
    assertThat(archive.dropAuthors(setOf(big))).isEqualTo(2)
    assertThat(archive.groupFaces()).hasSize(1)
    assertThat(archive.winners(listOf(FaceFixtures.key(1))).getValue(FaceFixtures.key(1)).author).isEqualTo(small)
    assertThat(archive.bodiesOf(big, listOf(FaceFixtures.key(1)))).isEmpty()
    assertThat(archive.bodiesOf(small, listOf(FaceFixtures.key(1)))).hasSize(1)
    assertThat(archive.people().values.map { it.author }).containsExactly(small)
    // Le voci da datare prendono l'ora del NAS, una volta sola.
    assertThat(archive.datePeople(500)).isEqualTo(1)
    assertThat(archive.datePeople(900)).isEqualTo(0)
    assertThat(archive.people().values.single().atNas).isEqualTo(500)
    store.close()
  }
}
