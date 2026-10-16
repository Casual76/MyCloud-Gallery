package dev.pampa.mycloudgallery.core.backup.chaos

import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupEngine
import dev.pampa.mycloudgallery.core.backup.contract.BackupStore
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.backup.safety.PrivateCopyFinder
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryBackupEngine
import dev.pampa.mycloudgallery.core.backup.upload.SafeUploadEngine
import dev.pampa.mycloudgallery.core.backup.upload.UploadConfig
import dev.pampa.mycloudgallery.core.backup.upload.UploadDescription
import dev.pampa.mycloudgallery.core.backup.upload.UploadFacts
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOps
import kotlin.random.Random

// Il banco gira contro due cose che non conosce: UN MONDO (il NAS, il telefono, il diario e il catalogo, con le azioni "da fuori") e UN MOTORE
// (il codice sotto prova). Cosi' lo stesso banco gira sul motore di riferimento di P0, sul motore vero di P2 con la deduplica di P3, e su
// qualunque altro motore (anche uno rotto apposta: BackupChaosMutationTest). Per provare un altro motore basta scrivere un [BackupChaosStackFactory].

/**
 * Tutto cio' che serve a costruire il motore di un "processo": le porte avvolte dai decoratori dei colpi (al posto delle vere) e il resto
 * del mondo. Un motore nuovo a ogni morte del processo, sopra lo stesso diario, catalogo, telefono e NAS.
 */
class EngineEnv(
  val world: BackupChaosWorld,
  val ops: NasBackupOps,
  val reader: NasMediaReader,
  val store: BackupStore,
  val catalog: BackupCatalog,
  val phone: PhoneBytes,
  /** Scelte del motore per questo giro, ripetibili dal seme (per esempio uno o due file in volo). */
  val random: Random,
) {
  fun with(
    ops: NasBackupOps = this.ops,
    reader: NasMediaReader = this.reader,
    store: BackupStore = this.store,
    catalog: BackupCatalog = this.catalog,
    phone: PhoneBytes = this.phone,
  ): EngineEnv = EngineEnv(world, ops, reader, store, catalog, phone, random)
}

interface BackupChaosStackFactory {
  val name: String

  fun create(env: EngineEnv): BackupEngine

  /**
   * Vero se la pulizia dei propri file di questo stack rispetta i temporanei citati dal diario (D33). Falso solo per la mutazione "temporaneo
   * tolto dalla pulizia" di [BackupChaosMutationTest]: la pulizia dei propri file sta in core-nasops, che core-backup non vede (D2), e il banco ne
   * tiene un modello con la stessa regola.
   */
  val cleanerHonorsCitations: Boolean get() = true
}

/** L'esecutore di riferimento di P0 (`InMemoryBackupEngine`): un elemento alla volta, le stesse regole di sicurezza del motore vero. */
object ReferenceStack : BackupChaosStackFactory {
  override val name: String = "esecutore di riferimento (InMemoryBackupEngine)"

  override fun create(env: EngineEnv): BackupEngine {
    val world = env.world
    return InMemoryBackupEngine(
      deviceId = world.deviceId,
      share = world.share,
      ops = env.ops,
      reader = env.reader,
      store = env.store,
      catalog = env.catalog,
      phone = env.phone,
      gate = world.gate,
      clock = world.clock,
      planner = world.planner,
      folder = { world.folder },
      chunkBytes = world.chunkBytes,
      checkpointBytes = world.checkpointBytes,
    )
  }
}

/**
 * Il motore vero di P2 ([SafeUploadEngine], lo stesso che `BackupEngineFactory` consegna a `:app`) con la deduplica vera di P3
 * ([PrivateCopyFinder]) e uno o due file in volo a sorte (D11). Le attese per un file aperto dal PC (E-N13) fanno avanzare l'orologio
 * finto invece di aspettare davvero. **E' l'unica riga da cambiare per provare un altro motore** (`BackupChaosBenchTest`).
 */
object ProductionStack : BackupChaosStackFactory {
  override val name: String = "motore vero (SafeUploadEngine di P2 con PrivateCopyFinder di P3)"

  override fun create(env: EngineEnv): BackupEngine {
    val world = env.world
    return SafeUploadEngine(
      deviceId = world.deviceId,
      share = world.share,
      ops = env.ops,
      reader = env.reader,
      store = env.store,
      catalog = env.catalog,
      phone = env.phone,
      gate = world.gate,
      clock = world.clock,
      planner = world.planner,
      folder = { world.folder },
      existing = PrivateCopyFinder(env.reader, env.catalog, world.clock),
      facts = UploadFacts { mediaId, versionId ->
        world.catalog.media(mediaId)?.takeIf { it.versionId == versionId }?.let { UploadDescription(it.kind, null) }
      },
      config = UploadConfig(
        chunkBytes = world.chunkBytes,
        checkpointBytes = world.checkpointBytes,
        parallel = 1 + env.random.nextInt(2),
        lockedFirstWaitMs = 10,
        lockedRetries = 3,
      ),
      sleep = { ms -> world.clock.advance(ms) },
    )
  }
}
