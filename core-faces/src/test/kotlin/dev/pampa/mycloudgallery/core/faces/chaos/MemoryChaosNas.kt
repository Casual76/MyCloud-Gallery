package dev.pampa.mycloudgallery.core.faces.chaos

import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseOps
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteBlock
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteGate
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseCheck
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseReason
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseRound
import dev.pampa.mycloudgallery.core.faces.erase.FacesAccountFacts
import dev.pampa.mycloudgallery.core.faces.sync.MemoryShare
import dev.pampa.mycloudgallery.core.faces.sync.NasFacesRemote
import dev.pampa.mycloudgallery.core.faces.sync.chaos.ChaosFsKit
import dev.pampa.mycloudgallery.core.faces.sync.chaos.chaosFsKit
import dev.pampa.mycloudgallery.core.faces.sync.chaos.commonAreaProbe
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Collections
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers

/** I registri del banco condivisi da tutti i dispositivi: ogni scrittura (e tentativo) di pubblicazione e ogni file tolto dalla cancellazione sul NAS. */
internal class ChaosLogs {
  /** Ogni scrittura riuscita: dispositivo e percorso. */
  val written: MutableList<Pair<String, SharePath>> = Collections.synchronizedList(mutableListOf())

  /** Ogni tentativo di scrittura, anche finito con un guasto dopo l'effetto. */
  val attempts: MutableList<Pair<String, SharePath>> = Collections.synchronizedList(mutableListOf())

  /** Ogni file tolto dalla cancellazione sul NAS: dispositivo che ha chiesto e percorso. */
  val deleted: MutableList<Pair<String, SharePath>> = Collections.synchronizedList(mutableListOf())
}

/**
 * IL NAS FINTO del banco (2.000 giri a ogni `check.ps1`): la share privata in memoria di P3 (`MemoryShare`) con i colpi su ogni richiesta dei
 * dispositivi, e la cancellazione stretta che applica la regola di D31 ([erase] sotto), come l'esecutore di riferimento di core-nasops che core-faces non vede.
 */
internal class MemoryChaosNas(
  private val inj: ChaosInjector,
  private val logs: ChaosLogs,
  private val common: MemoryShare,
  override val privateShare: String = "test",
) : ChaosNas {
  override val name: String = "NAS finto in memoria (la share di P3 con i colpi su ogni richiesta)"
  override val real: Boolean = false
  override val commonShare: String get() = common.shareName
  private val commonName: () -> String = { common.shareName }
  override var frozen: Boolean = false
  override var recycleBin: Boolean = false
  override var maxFilesPerRound: Int = 1_000

  val share = MemoryShare(privateShare)
  private val binCopies = Collections.synchronizedSet(LinkedHashSet<SharePath>())

  override fun binCopies(): Int = binCopies.size

  override fun emptyRecycleBin() = binCopies.clear()

  override fun kit(device: ChaosDevice): ChaosKit {
    val kit: ChaosFsKit = chaosFsKit(
      share, inj, device.index, device.id, { device.online }, commonName, device.identity, device.publishSwitch, device.world.clock,
      FacesWriteGate { if (frozen) FacesWriteBlock.FROZEN else null }, logs.written, logs.attempts,
      { path -> device.world.writeAttempt(device, path) },
    )
    return ChaosKit(kit.remote, kit.leases)
  }

  override fun eraseOps(device: ChaosDevice, facts: FacesAccountFacts): FacesEraseOps = Erase(device, facts)

  override fun sever(device: Int) = Unit

  override fun paths(): List<SharePath> = share.paths()

  override fun bytes(path: SharePath): ByteArray? = share.bytes(path)

  override fun commonPaths(): List<SharePath> = common.paths()

  override fun plant(path: SharePath, bytes: ByteArray) {
    share.plant(path, bytes)
  }

  override fun removeAsUser(path: SharePath) {
    share.delete(path)
  }

  override fun commonProbe(device: ChaosDevice): Pair<Boolean, Int> =
    commonAreaProbe(common, device.id, common.shareName) { remote -> remote.generations() is RemoteResult.Failed }

  override fun observer(device: ChaosDevice): NasFacesRemote =
    NasFacesRemote(share, device.id, commonName, Dispatchers.Unconfined)

  override fun pauseFaults(): AutoCloseable = AutoCloseable { }

  override fun close() = Unit

  private fun facesFiles(generation: FaceGeneration): List<SharePath> = share.paths().filter { it.isWithin(NasLayout.facesGenerationDir(generation)) }

  private fun generationsOnNas(): Set<FaceGeneration> =
    share.paths().filter { it.isWithin(NasLayout.FACES_DIR) }.mapNotNull { if (it.components.size > 2) FaceGeneration.parse(it.components[2]) else null }.toSet()

  /** La cancellazione stretta sul NAS vista da un dispositivo: la regola di D31 con i fatti del dispositivo, il cancello, i giri a lotti. */
  private inner class Erase(private val device: ChaosDevice, private val facts: FacesAccountFacts) : FacesEraseOps {
    override suspend fun generations(): List<FaceGeneration>? {
      if (!device.online) return null
      return try {
        inj.around(device.index, Surface.ERASE_OPS) { generationsOnNas().sortedBy { it.base32 } }
      } catch (e: ChaosFault) {
        null
      }
    }

    override suspend fun eraseGeneration(generation: FaceGeneration, reason: NasEraseReason, budget: Duration): NasEraseRound {
      blocked()?.let { return it }
      if (reason == NasEraseReason.DEVICE_REVOKED) return NasEraseRound.NotAllowed
      when (val current = facts.current()) {
        FacesAccountFacts.Current.Unknown -> return NasEraseRound.Unsure
        is FacesAccountFacts.Current.Known -> if (current.generation == generation) return NasEraseRound.NotAllowed
      }
      return remove(facesFiles(generation))
    }

    override suspend fun eraseDevice(generation: FaceGeneration, deviceId: String, reason: NasEraseReason, budget: Duration): NasEraseRound {
      blocked()?.let { return it }
      val current = facts.current() as? FacesAccountFacts.Current.Known ?: return NasEraseRound.Unsure
      val allowed = when (reason) {
        NasEraseReason.DEVICE_REVOKED -> facts.revokedOrLost(deviceId)
        NasEraseReason.NOT_CURRENT, NasEraseReason.USER_CONFIRMED -> current.generation != generation
      }
      if (!allowed) return NasEraseRound.NotAllowed
      return remove(share.paths().filter { it.isWithin(NasLayout.facesDeviceDir(generation, deviceId)) })
    }

    override suspend fun verify(generation: FaceGeneration): NasEraseCheck? {
      if (!device.online) return null
      return try {
        inj.around(device.index, Surface.ERASE_OPS) { NasEraseCheck(facesFiles(generation).isNotEmpty(), recycleBin && binCopies.isNotEmpty()) }
      } catch (e: ChaosFault) {
        null
      }
    }

    private fun blocked(): NasEraseRound? = when {
      !device.online -> NasEraseRound.Waiting(EraseWait.NAS)
      frozen -> NasEraseRound.Waiting(EraseWait.FROZEN)
      else -> null
    }

    private fun remove(files: List<SharePath>): NasEraseRound {
      val batch = files.take(maxFilesPerRound)
      try {
        for (path in batch) {
          // Ogni file tolto e' una richiesta: la morte o la rete possono cadere a meta' giro (prima o dopo che quel file sia andato).
          inj.around(device.index, Surface.ERASE_OPS) {
            share.delete(path)
            logs.deleted += device.id to path
            if (recycleBin) binCopies += path
            // L'errore `DELETE_OUTSIDE_FACES`: insieme ai file dei volti se ne va un media dell'area privata.
            if (device.world.mutant == Mutant.DELETE_OUTSIDE_FACES) {
              device.world.untouchable.keys.firstOrNull { share.bytes(it) != null }?.let {
                share.delete(it)
                logs.deleted += device.id to it
              }
            }
          }
        }
      } catch (e: ChaosFault) {
        return NasEraseRound.Waiting(EraseWait.NAS)
      }
      return if (files.size > batch.size) NasEraseRound.More(batch.size, files.size - batch.size) else NasEraseRound.Done(batch.size)
    }
  }
}
