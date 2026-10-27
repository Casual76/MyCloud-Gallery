package dev.pampa.mycloudgallery.core.faces.chaos

import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.faces.contract.ClusterSnapshot
import dev.pampa.mycloudgallery.core.faces.contract.FacesComputeLimits
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteGate
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.faces.noop.NoFacesPublisher
import dev.pampa.mycloudgallery.core.faces.sync.FacesArchives
import dev.pampa.mycloudgallery.core.faces.sync.InMemoryFacesReplicaStore
import dev.pampa.mycloudgallery.core.faces.sync.NasFacesSync
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesSwitch
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesArchive
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlin.time.Duration.Companion.hours

// IL SESTO E L'OTTAVO INVARIANTE, dal NAS com'e' (docs/fase8-progetto.md §5.5): le istantanee pubblicate si rileggono e sono coerenti; a fine prova il
// lavoro e' arrivato in fondo. Chi guarda e' un osservatore: un dispositivo nuovo senza stato, che legge le teste del NAS come le leggerebbe un altro
// telefono dell'account (la replica vera di P3, senza colpi, senza scrivere), e ricorda l'istantanea vincente di ogni giro.

internal class ChaosSnapshots(private val world: ChaosWorld, private val verifier: ChaosVerifier) {

  private class Watch(val sync: NasFacesSync, val archive: InMemoryFacesArchive, val lastSeq: LongBox, val lastIds: IdsBox)

  private class LongBox(var value: Long = 0)

  private class IdsBox(var value: Set<PersonId> = emptySet())

  private val watches = HashMap<FaceGeneration, Watch>()

  /** La generazione piu' recente che l'account ha acceso, se almeno un dispositivo la tratta ancora; altrimenti null. */
  fun currentGeneration(): FaceGeneration? {
    val latest = world.latestGeneration ?: return null
    return latest.takeIf { g -> world.devices.any { d -> d.switch.currentState().let { it is FacesState.On && it.generation == g } } }
  }

  /** Rilegge le teste del NAS e giudica le istantanee. */
  suspend fun check(phase: String) {
    val generation = currentGeneration() ?: return
    val watch = watches.getOrPut(generation) { create(generation) }
    val report = world.nas.pauseFaults().use { watch.sync.sync(1.hours) }
    world.inj.stats.snapshotsRead++
    if (report.headsRejected.isNotEmpty() || report.segmentsRejected.isNotEmpty()) {
      verifier.add(Invariant.SNAPSHOTS, phase, "teste o segmenti pubblicati che chi rilegge rifiuta: teste ${report.headsRejected}, segmenti ${report.segmentsRejected}")
    }
    val snapshots = world.nas.pauseFaults().use { watch.sync.latest(generation) }
    for (snapshot in snapshots) {
      val persons = snapshot.state.persons
      for (person in persons.values) {
        val alias = person.aliasOf
        if (person.state == PersonState.MERGED && (alias == null || alias !in persons)) {
          verifier.add(Invariant.SNAPSHOTS, phase, "un alias che punta a una persona che l'istantanea non ha")
        }
      }
      // Rilegge uguale: tutto cio' che l'istantanea pubblicata dice sta anche nell'istantanea che il dispositivo ha salvato con lo stesso numero. In piu' il salvato puo'
      // avere le assegnazioni provvisorie di chi non e' (piu') custode, che non si pubblicano (§2.5): non sono una differenza.
      val author = world.devices.firstOrNull { it.id == snapshot.author }
      val local = author?.local?.raw(generation)?.grouping()
      if (local != null && local.seq == snapshot.state.seq) {
        val missingPersons = snapshot.state.persons.keys - local.persons.keys
        val missingFaces = snapshot.state.assignments.keys - local.assignments.keys
        val changed = snapshot.state.assignments.count { (face, a) -> local.assignments[face]?.let { it.person != a.person || it.origin != a.origin } == true }
        if (missingPersons.isNotEmpty() || missingFaces.isNotEmpty() || changed > 0) {
          verifier.add(
            Invariant.SNAPSHOTS, phase,
            "l'istantanea pubblicata da ${author.name} non rilegge uguale a quella salvata (seq ${snapshot.state.seq} base ${snapshot.state.base}; persone che mancano qui ${missingPersons.size}, " +
              "volti che mancano ${missingFaces.size}, assegnazioni diverse $changed)",
          )
        }
      }
    }
    val winner = ClusterSnapshot.winner(snapshots) ?: return
    val seq = winner.state.seq
    if (seq < watch.lastSeq.value) {
      verifier.add(Invariant.SNAPSHOTS, phase, "l'istantanea vincente e' tornata indietro (${watch.lastSeq.value} -> $seq)")
    } else {
      val lost = watch.lastIds.value - winner.state.persons.keys
      if (lost.isNotEmpty()) verifier.add(Invariant.SNAPSHOTS, phase, "${lost.size} persone perdute fra due istantanee (senza un alias)")
      watch.lastSeq.value = seq
      watch.lastIds.value = winner.state.persons.keys
    }
  }

  private fun create(generation: FaceGeneration): Watch {
    val first = world.devices.first()
    val switch = FakeFacesSwitch(initial = FacesState.On(generation, HereState.ACTIVE))
    val archive = InMemoryFacesArchive(generation)
    val sync = NasFacesSync(
      first.identity, switch, world.nas.observer(first), FacesArchives { archive }, InMemoryFacesReplicaStore(), FacesWriteGate { null }, world.clock,
      NoFacesPublisher, FeatureFlags.RELEASE,
    )
    return Watch(sync, archive, LongBox(), IdsBox())
  }

  /**
   * L'ottavo invariante a fine prova (tutti a casa, niente congelamento, guasti spenti): ogni dispositivo acceso ha analizzato ogni media in ambito, non ha
   * niente da pubblicare, e tutti scelgono gli stessi corpi vincenti.
   */
  suspend fun converged(phase: String) {
    val expected = world.media.analyzable(listOf(dev.pampa.mycloudgallery.core.faces.contract.FacesArea.PRIVATE, dev.pampa.mycloudgallery.core.faces.contract.FacesArea.COMMON))
    val active = world.devices.filter { it.isActive() && it.online }
    val winners = HashMap<String, Map<dev.pampa.mycloudgallery.core.model.ContentKey, Pair<String, Long>>>()
    for (device in active) {
      val g = device.generation() ?: continue
      val archive = device.local.raw(g) ?: continue
      val missing = expected - archive.analyzed(expected, FacesComputeLimits.RECIPE)
      if (missing.isNotEmpty()) {
        verifier.add(Invariant.CONVERGENCE, phase, "${device.name} non ha analizzato ${missing.size} media in ambito (${missing.take(3).joinToString { world.media.describe(it) }})")
      }
      val pending = device.local.publishStore.load(g).pending
      if (pending != 0) verifier.add(Invariant.CONVERGENCE, phase, "${device.name} ha $pending voci ancora in coda dopo le riprese")
      winners[device.name] = archive.winners(expected).mapValues { it.value.author to it.value.digest }
    }
    val reference = winners.entries.firstOrNull() ?: return
    for ((name, other) in winners) {
      if (other == reference.value) continue
      val differing = (other.keys + reference.value.keys).filter { other[it] != reference.value[it] }
      verifier.add(
        Invariant.CONVERGENCE, phase,
        "i corpi vincenti di $name non sono quelli di ${reference.key}: ${differing.size} contenuti; " +
          differing.take(2).joinToString { key ->
            "${world.media.describe(key)} -> " + active.joinToString(" ") { d -> "${d.name}=${winners[d.name]?.get(key)?.let { w -> nameOf(w.first) + "/" + w.second.toString(16).takeLast(6) } ?: "-"}" } +
              " | " + active.joinToString(" ") { d -> publicationOf(d, key) }
          },
      )
    }
  }

  /** Dove sta il corpo di [key] di [device]: nel suo archivio, nell'outbox (chiavi gia' accodate, quante ancora da pubblicare), e quello che un osservatore legge dal NAS. */
  private fun publicationOf(device: ChaosDevice, key: dev.pampa.mycloudgallery.core.model.ContentKey): String = kotlinx.coroutines.runBlocking {
    val g = device.generation()!!
    val own = device.local.raw(g)?.bodiesOf(device.id, listOf(key))?.size ?: 0
    val state = device.local.publishStore.load(g)
    val seen = watches[g]?.archive?.bodiesOf(device.id, listOf(key))?.size ?: -1
    "${device.name}[proprio=$own accodato=${key in state.keys} dopo-la-coda=${state.pending} testa=${state.publication?.version} incerto=${state.uncertain} sul-NAS=$seen]"
  }

  private fun nameOf(deviceId: String): String = world.devices.firstOrNull { it.id == deviceId }?.name ?: "?"
}
