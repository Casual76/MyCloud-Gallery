package dev.pampa.mycloudgallery.core.nas.fs

import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasHousekeeping
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.ShareAccess
import dev.pampa.mycloudgallery.core.nas.contract.ShareProbe
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.smb.DirAccess
import dev.pampa.mycloudgallery.core.nas.smb.NtStatus
import dev.pampa.mycloudgallery.core.nas.smb.SmbStatusException
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration

/**
 * La prova di una share (passo 4 dell'onboarding, E-O2, E-O3): esiste, si legge, si scrive. La scrittura si
 * verifica aprendo la radice della share con `FILE_ADD_FILE` (il diritto di creare file), **senza creare nessun
 * file**. Gli errori di rete restano errori (una share irraggiungibile non e' una share assente).
 */
class SmbShareProbe internal constructor(
  private val manager: SmbNasSessionManager,
  private val purpose: Purpose,
  /** La cartella provata al posto della radice: solo l'area di prova dei test d'integrazione (§5.4). */
  private val root: SharePath?,
) : ShareProbe {

  constructor(manager: SmbNasSessionManager, purpose: Purpose = Purpose.INTERACTIVE) : this(manager, purpose, null)

  override suspend fun probe(share: String): NasResult<ShareAccess> {
    val dir = root?.smb().orEmpty()
    val result = manager.runOp(share, purpose, idempotent = true, touchesDisk = true) { ctx ->
      if (!opens(dir, ctx.share::openDirectory, DirAccess.LIST)) {
        ShareAccess.DENIED
      } else if (opens(dir, ctx.share::openDirectory, DirAccess.ADD_FILE)) {
        ShareAccess.READ_WRITE
      } else {
        ShareAccess.READ_ONLY
      }
    }
    return when (result) {
      is NasResult.Ok -> result
      is NasResult.Err -> when (result.error) {
        is NasError.ShareMissing -> NasResult.Ok(ShareAccess.MISSING)
        NasError.PermissionDenied -> NasResult.Ok(ShareAccess.DENIED)
        else -> result
      }
    }
  }

  private fun opens(dir: String, open: (String, DirAccess) -> AutoCloseable, access: DirAccess): Boolean =
    try {
      open(dir, access).close()
      true
    } catch (e: SmbStatusException) {
      if (e.status != NtStatus.ACCESS_DENIED && e.status != NtStatus.MEDIA_WRITE_PROTECTED) throw e
      false
    }
}

/**
 * La pulizia dei **propri** temporanei rimasti a meta' da un processo morto tra scrittura e rinomina:
 * `.mycloudgallery/tmp/<deviceId>-<32 cifre esadecimali>` e nient'altro (E-S11: nessun nome letto dal NAS fa
 * cancellare file altrui; l'id e' locale, il formato e' stretto). L'eta' si misura con l'ora del NAS.
 *
 * @param shares le share in cui questo dispositivo pubblica (area comune e area privata).
 */
class SmbNasHousekeeping internal constructor(
  private val manager: SmbNasSessionManager,
  private val shares: () -> Collection<String>,
  /** La radice al posto di quella della share: solo l'area di prova dei test d'integrazione (§5.4). */
  root: SharePath?,
) : NasHousekeeping {

  constructor(manager: SmbNasSessionManager, shares: () -> Collection<String>) : this(manager, shares, null)

  private val tmpDir: SharePath = tmpDirOf(root)


  override suspend fun purgeOwnStaleTemps(deviceId: String, olderThan: Duration): NasResult<Int> {
    require(DEVICE_ID.matches(deviceId)) { "id di dispositivo non valido" }
    val own = Regex("^" + Regex.escape(deviceId) + "-[0-9a-f]{32}$")
    var purged = 0
    var firstError: NasError? = null
    for (share in shares()) {
      val result = manager.runOp(share, Purpose.BACKGROUND, idempotent = true, touchesDisk = true) { ctx ->
        val nasNow = manager.nasClock.nowMillisOrNull() ?: return@runOp 0
        val directory = try {
          ctx.share.openDirectory(tmpDir.smb(), DirAccess.LIST)
        } catch (e: SmbStatusException) {
          if (e.status == NtStatus.OBJECT_NAME_NOT_FOUND || e.status == NtStatus.OBJECT_PATH_NOT_FOUND) return@runOp 0
          throw e
        }
        val stale = ArrayList<String>()
        directory.use {
          val iterator = it.entries()
          while (iterator.hasNext() && stale.size < MAX_PER_SHARE) {
            ctx.checkAborted()
            val entry = iterator.next()
            if (entry.isDirectory || !own.matches(entry.name)) continue
            if (nasNow - entry.lastWriteMs >= olderThan.inWholeMilliseconds) stale += entry.name
          }
        }
        var deleted = 0
        for (name in stale) {
          try {
            ctx.share.deleteFile(tmpDir.child(name).smb())
            deleted++
          } catch (e: SmbStatusException) {
            if (e.status != NtStatus.OBJECT_NAME_NOT_FOUND) throw e
          }
        }
        deleted
      }
      when (result) {
        is NasResult.Ok -> purged += result.value
        is NasResult.Err -> if (firstError == null) firstError = result.error
      }
    }
    return firstError?.let { NasResult.Err(it) } ?: NasResult.Ok(purged)
  }

  private companion object {
    /** Un tetto al lavoro di un giro (una cartella piena di nomi validi non tiene occupata la sessione). */
    const val MAX_PER_SHARE = 1000
  }
}
