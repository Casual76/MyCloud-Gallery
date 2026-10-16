package dev.pampa.mycloudgallery.core.backup.testing

import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupItem
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupStore
import dev.pampa.mycloudgallery.core.backup.contract.HashRecord
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.VerifiedCopy
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId

// Fase 5A (P0, §4.3, §5.4): la "morte del processo" a ogni scrittura del diario del backup e del catalogo, per `EveryCallTest` e per il
// banco di P6. Il processo muore PRIMA o DOPO che la scrittura abbia avuto effetto; tutto cio' che sta in memoria si butta e si
// riparte dal diario (NAS, telefono, diario e catalogo sopravvivono: sono il NAS, il telefono e il disco).

/** La morte simulata: un `Error`, cosi' nessun `catch (e: Exception)` del codice sotto prova la ferma. */
class ProcessDeath : Error("morte simulata del processo")

/**
 * Il contatore dei passi di un processo: ogni scrittura del diario e del catalogo e' un passo; al passo [dieAt] il processo muore
 * prima o dopo l'effetto ([afterEffect]). Con i valori di default conta e basta (il giro pulito che dice quanti passi ci sono).
 */
class BackupDeath(private val dieAt: Int = Int.MAX_VALUE, private val afterEffect: Boolean = false) {
  var steps: Int = 0
    private set

  var died: Boolean = false
    private set

  suspend fun <T> around(block: suspend () -> T): T {
    val here = ++steps
    if (here == dieAt && !afterEffect) {
      died = true
      throw ProcessDeath()
    }
    val result = block()
    if (here == dieAt && afterEffect) {
      died = true
      throw ProcessDeath()
    }
    return result
  }
}

/** Il diario che muore: le scritture contano come passi (le letture no: non cambiano niente). */
class DyingBackupStore(private val inner: BackupStore, private val death: BackupDeath) : BackupStore by inner {
  override suspend fun create(items: List<NewBackupItem>): List<BackupItem> = death.around { inner.create(items) }

  override suspend fun update(item: BackupItem) = death.around { inner.update(item) }
}

/** Il catalogo che muore: le scritture del backup contano come passi. */
class DyingBackupCatalog(private val inner: BackupCatalog, private val death: BackupDeath) : BackupCatalog by inner {
  override suspend fun recordFullHash(mediaId: MediaId, versionId: VersionId, sha256: Sha256): HashRecord =
    death.around { inner.recordFullHash(mediaId, versionId, sha256) }

  override suspend fun attachVerifiedCopies(copies: List<VerifiedCopy>): Int = death.around { inner.attachVerifiedCopies(copies) }

  override suspend fun markProblem(mediaId: MediaId, problem: BackupProblemCode?) = death.around { inner.markProblem(mediaId, problem) }

  override suspend fun markVerified(location: LocationId, readBack: Sha256, atMillis: Long): Boolean =
    death.around { inner.markVerified(location, readBack, atMillis) }
}
