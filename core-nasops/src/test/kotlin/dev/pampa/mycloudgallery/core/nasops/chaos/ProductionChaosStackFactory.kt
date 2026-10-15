package dev.pampa.mycloudgallery.core.nasops.chaos

import dev.pampa.mycloudgallery.core.nasops.contract.OpsStore
import dev.pampa.mycloudgallery.core.nasops.engine.NasOpsEngine
import dev.pampa.mycloudgallery.core.nasops.engine.NasOpsLock
import dev.pampa.mycloudgallery.core.nasops.engine.ShareAccessView
import dev.pampa.mycloudgallery.core.nasops.engine.TrashRecordWriter
import dev.pampa.mycloudgallery.core.nasops.testing.InMemoryOpsStore
import dev.pampa.mycloudgallery.core.nasops.trash.NasTrashService
import dev.pampa.mycloudgallery.core.nasops.trash.RemoteTrashSource
import kotlinx.coroutines.delay

/**
 * Lo stack VERO di P12 nel banco delle interruzioni (docs/prova-500.md §9, punto 1): l'esecutore delle operazioni ([NasOpsEngine]) e il
 * cestino del NAS ([NasTrashService]) di produzione, con un lucchetto condiviso come in `NasOpsWiring`, sopra i decoratori del banco (colpi,
 * morti del processo). Il diario e' quello in memoria che sopravvive alle morti ([InMemoryOpsStore]); il diario Room e le sue morti a ogni
 * scrittura li provano `p12/RoomOpsStoreTest` ed `EveryCallTest`.
 *
 * Nessuna esclusione: niente `unfaultable`, `cancelIsSafe` o `knownDefectHit`. Se il banco trova qualcosa, va sistemato nel codice.
 *
 * I record firmati del cestino (`trash/<io>/rec/<id>`) non si scrivono qui ([TrashRecordWriter.NONE]): il mondo del banco da' allo stack solo
 * le primitive delle operazioni e la lettura, non la scrittura dei file del formato. Record e fault a ogni chiamata della loro scrittura sono
 * in `p12/EveryCallTest` e `p12/TrashPurgeTest`.
 */
object ProductionChaosStackFactory : ChaosStackFactory {
  override val name: String = "operazioni vere di P12 (NasOpsEngine, NasTrashService)"

  override fun newStore(world: ChaosWorld): OpsStore = InMemoryOpsStore()

  override fun create(env: StackEnv): ChaosStack {
    val lock = NasOpsLock()
    val access = ShareAccessView { share -> env.readOnly(share) }
    val engine = NasOpsEngine(
      deviceId = env.world.deviceId,
      accountId = env.world.accountId,
      fileOps = env.fileOps,
      reader = env.reader,
      store = env.store,
      clocks = env.clocks,
      gate = env.gate,
      access = access,
      records = TrashRecordWriter.NONE,
      lock = lock,
      newId = env.newId,
      // Le attese brevi per una cartella in uso dall'app fanno passare il tempo del mondo finto, non quello vero (il NAS vero lo aspetta davvero).
      waitShortly = { ms -> if (env.world.capabilities.advanceTime) env.world.advanceTime(ms) else delay(ms) },
    )
    val trash = NasTrashService(
      deviceId = env.world.deviceId,
      accountId = env.world.accountId,
      shares = { env.world.shares },
      store = env.store,
      fileOps = env.fileOps,
      reader = env.reader,
      clocks = env.clocks,
      gate = env.gate,
      access = access,
      remote = RemoteTrashSource.NONE,
      lock = lock,
      authorName = { null },
    )
    return ChaosStack(engine, trash)
  }
}
