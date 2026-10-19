package dev.pampa.mycloudgallery.core.backup.versions

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.PublishOutcome
import dev.pampa.mycloudgallery.core.nas.ops.RenameOutcome
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOps
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/**
 * Le primitive del backup con dei ganci **prima** di alcune chiamate: "il PC" che cambia il NAS proprio mentre il lavoro sta per spostare o
 * pubblicare (E-E6, `SOURCE_CHANGED`), un congelamento che arriva a meta' (D32). Il resto passa com'e'.
 */
class HookedOps(private val inner: NasBackupOps) : NasBackupOps by inner {
  var beforeArchive: suspend () -> Unit = {}
  var beforeRestore: suspend () -> Unit = {}
  var beforePublish: suspend () -> Unit = {}

  override suspend fun archiveVersion(share: String, from: SharePath, to: SharePath, expect: NasStamp, purpose: Purpose): NasResult<RenameOutcome> {
    beforeArchive()
    return inner.archiveVersion(share, from, to, expect, purpose)
  }

  override suspend fun restoreVersion(share: String, from: SharePath, to: SharePath, expect: NasStamp, purpose: Purpose): NasResult<RenameOutcome> {
    beforeRestore()
    return inner.restoreVersion(share, from, to, expect, purpose)
  }

  override suspend fun publishTemp(share: String, temp: SharePath, final: SharePath, purpose: Purpose): NasResult<PublishOutcome> {
    beforePublish()
    return inner.publishTemp(share, temp, final, purpose)
  }
}
