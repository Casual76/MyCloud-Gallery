package dev.pampa.mycloudgallery.core.faces.chaos

import dev.pampa.mycloudgallery.core.faces.contract.FaceBody
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.PeopleKey
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.erase.FacesLocalData
import dev.pampa.mycloudgallery.core.faces.sync.InMemoryFacesPublishStore
import dev.pampa.mycloudgallery.core.faces.sync.InMemoryFacesReplicaStore
import dev.pampa.mycloudgallery.core.faces.compute.MemoryUnits
import dev.pampa.mycloudgallery.core.faces.store.FacesUnitsDone
import dev.pampa.mycloudgallery.core.faces.store.LocalErase
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesArchive
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import java.io.IOException

// I DATI DEI VOLTI DI UN DISPOSITIVO, "SU DISCO" (sopravvivono alla morte del processo): per generazione la chiave del Keystore, il database (archivio e
// unita' finite) e, comuni a tutte le generazioni, lo stato del pubblicatore e della replica. Come `WorldLocalData` di P8, con la morte del processo
// anche a meta' della cancellazione locale (dopo aver tolto la chiave, prima di togliere i file).

internal class ChaosLocalData(
  private val inj: ChaosInjector,
  private val device: Int,
  /** L'errore `FILES_BEFORE_KEY`: la cancellazione locale toglie i file prima di distruggere la chiave. */
  private val filesFirst: Boolean = false,
  private val onWipe: (FaceGeneration) -> Unit = {},
  private val onBody: (FaceGeneration, FaceBody) -> Unit,
) : FacesLocalData {

  class Gen(val archive: InMemoryFacesArchive, val units: MemoryUnits) {
    var key = true
    var files = true
  }

  val gens = LinkedHashMap<FaceGeneration, Gen>()

  /** Cio' che la cancellazione locale ha tolto, nell'ordine: `key:<G>` e `files:<G>`. */
  val log: MutableList<String> = ArrayList()

  /** Le prossime N cancellazioni locali falliscono (un file bloccato). */
  var failErase = 0

  /** Il pubblicatore e la replica hanno il loro stato su disco, per tutte le generazioni (un solo posto per generazione, come nel database). */
  val publishStore = InMemoryFacesPublishStore()
  val replicaStore = InMemoryFacesReplicaStore()

  /** L'archivio di [generation] (null se non c'e'); con [create] lo fa nascere con la sua chiave (solo il recinto lo chiede). */
  fun archive(generation: FaceGeneration, create: Boolean): FacesArchive? = synchronized(this) {
    val gen = gens[generation]?.takeIf { it.files && it.key }
      ?: if (create && gens[generation] == null) Gen(InMemoryFacesArchive(generation), MemoryUnits()).also { gens[generation] = it } else null
    gen?.let { ChaosArchive(it.archive, inj, device, onBody) }
  }

  fun units(generation: FaceGeneration, create: Boolean): FacesUnitsDone? = synchronized(this) {
    val gen = gens[generation]?.takeIf { it.files && it.key }
      ?: if (create && gens[generation] == null) Gen(InMemoryFacesArchive(generation), MemoryUnits()).also { gens[generation] = it } else null
    gen?.let { ChaosUnits(it.units, inj, device) }
  }

  /** L'archivio "nudo" (senza colpi), per le verifiche del banco. */
  fun raw(generation: FaceGeneration): InMemoryFacesArchive? = synchronized(this) { gens[generation]?.takeIf { it.files && it.key }?.archive }

  fun has(generation: FaceGeneration): Boolean = synchronized(this) { gens[generation]?.let { it.files || it.key } == true }

  override suspend fun generationsOnDisk(): List<FaceGeneration> {
    inj.checkAlive(device)
    return synchronized(this) { gens.filterValues { it.files || it.key }.keys.toList() }
  }

  override suspend fun hasData(generation: FaceGeneration): Boolean {
    inj.checkAlive(device)
    return has(generation)
  }

  override suspend fun people(generation: FaceGeneration): Map<PeopleKey, PeopleRecord>? {
    inj.checkAlive(device)
    val gen = synchronized(this) { gens[generation]?.takeIf { it.files && it.key } } ?: return emptyMap()
    return gen.archive.people()
  }

  override suspend fun erase(generation: FaceGeneration): LocalErase {
    inj.checkAlive(device)
    val gen = synchronized(this) {
      if (failErase > 0) {
        failErase--
        throw IOException("file bloccato")
      }
      gens[generation]
    } ?: return LocalErase(keyDestroyed = true, filesGone = true)
    // La chiave per prima (la morte puo' cadere prima o dopo), poi i file (idem).
    if (filesFirst && gen.files) {
      inj.around(device, Surface.LOCAL) {
        synchronized(this) {
          log += "files:${generation.base32}"
          gen.files = false
        }
      }
    }
    if (gen.key) {
      inj.around(device, Surface.LOCAL) {
        synchronized(this) {
          log += "key:${generation.base32}"
          gen.key = false
        }
      }
    }
    gen.archive.close()
    if (gen.files) {
      inj.around(device, Surface.LOCAL) {
        synchronized(this) {
          log += "files:${generation.base32}"
          gen.files = false
        }
      }
    }
    synchronized(this) { gens.remove(generation) }
    // I dati locali di questa generazione non ci sono piu': il lavoro che ricomincia dopo (un'esclusione e poi il ritorno) non e' lavoro rifatto.
    onWipe(generation)
    return LocalErase(keyDestroyed = true, filesGone = true)
  }
}
