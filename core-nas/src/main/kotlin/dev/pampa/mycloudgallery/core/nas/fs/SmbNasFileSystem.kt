package dev.pampa.mycloudgallery.core.nas.fs

import dev.pampa.mycloudgallery.core.nas.contract.DirEntry
import dev.pampa.mycloudgallery.core.nas.contract.DirPage
import dev.pampa.mycloudgallery.core.nas.contract.NasBrowser
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.session.Attempt
import dev.pampa.mycloudgallery.core.nas.session.FailureKind
import dev.pampa.mycloudgallery.core.nas.session.NasOpException
import dev.pampa.mycloudgallery.core.nas.session.OpContext
import dev.pampa.mycloudgallery.core.nas.session.SmbErrors
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.smb.DirAccess
import dev.pampa.mycloudgallery.core.nas.smb.NtStatus
import dev.pampa.mycloudgallery.core.nas.smb.SmbStatusException
import dev.pampa.mycloudgallery.core.nas.smb.SmbWriteFile
import dev.pampa.mycloudgallery.core.trust.TrustFormat
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasListing
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.IOException
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking

/** I temporanei della pubblicazione atomica (formato-nas.md §2.2): `<share>/.mycloudgallery/tmp/`. */
internal val TMP_DIR: SharePath = SharePath.of(TrustFormat.ROOT_DIR, "tmp")

/** Un id di dispositivo: 128 bit in base32, 26 caratteri `[0-9a-z]` (formato-nas.md §2.4). */
internal val DEVICE_ID: Regex = Regex("^[0-9a-z]{26}$")

/** Prefisso dei temporanei finche' il dispositivo non ha un id (onboarding): non coincide mai con un id valido. */
internal const val PENDING_TEMP_OWNER = "pending"

/** Il percorso SMB (separatore `\`) di un `SharePath`. La radice della share e' `""`. */
internal fun SharePath.smb(): String = components.joinToString("\\")

internal val ABSENT: Set<Long> = setOf(
  NtStatus.OBJECT_NAME_NOT_FOUND,
  NtStatus.OBJECT_PATH_NOT_FOUND,
  NtStatus.NO_SUCH_FILE,
  NtStatus.NOT_A_DIRECTORY,
  NtStatus.FILE_IS_A_DIRECTORY,
  NtStatus.DELETE_PENDING,
)

private const val CHUNK = 1024 * 1024
private const val FIRST_BUFFER = 64 * 1024
private const val MAX_ARRAY = Int.MAX_VALUE - 8
private const val RANDOM_BYTES = 16

/**
 * Il `NasFileSystem` di core-trust sopra la sessione SMB (formato-nas.md §2.3, progetto §3.1 riga
 * `SmbNasFileSystem`). Bloccante, come chiede il contratto: ogni chiamata attraversa la sessione (percorso,
 * interruttore, limitatore, timeout, watchdog, nuovi tentativi). Gli errori arrivano come `NasIoException`.
 */
internal class SmbNasFileSystem(
  private val manager: SmbNasSessionManager,
  override val shareName: String,
  private val purpose: Purpose,
) : NasFileSystem {

  override fun list(dir: SharePath, limit: Int): NasListing {
    require(limit >= 0) { "limite negativo" }
    return blocking { manager.runOp(shareName, purpose, idempotent = true, touchesDisk = true) { FsOps.list(it, dir, limit) } }
  }

  override fun read(path: SharePath, maxBytes: Int): NasRead {
    require(maxBytes >= 0) { "tetto negativo" }
    return blocking { manager.runOp(shareName, purpose, idempotent = true, touchesDisk = true) { FsOps.read(it, path, maxBytes) } }
  }

  override fun publishExclusive(path: SharePath, bytes: ByteArray): PublishResult =
    blocking { Publisher(manager, shareName, purpose).publish(path, bytes) }

  override fun toString(): String = "SmbNasFileSystem"

  private fun <T> blocking(block: suspend () -> NasResult<T>): T = when (val result = runBlocking { block() }) {
    is NasResult.Ok -> result.value
    is NasResult.Err -> throw NasIoException(result.error)
  }
}

/** Sfoglia una share in sola lettura, a pagine (`Directory.iterator`). */
internal class SmbNasBrowser(
  private val manager: SmbNasSessionManager,
  private val shareName: String,
  private val purpose: Purpose,
) : NasBrowser {
  override suspend fun list(dir: SharePath?, limit: Int): NasResult<DirPage> {
    require(limit >= 0) { "limite negativo" }
    return manager.runOp(shareName, purpose, idempotent = true, touchesDisk = true) { ctx ->
      ctx.share.openDirectory(dir?.smb().orEmpty(), DirAccess.LIST).use { directory ->
        val entries = ArrayList<DirEntry>(minOf(limit, FIRST_LIST_CAPACITY))
        val iterator = directory.entries()
        var truncated = false
        while (iterator.hasNext()) {
          ctx.checkAborted()
          val entry = iterator.next()
          if (entries.size == limit) {
            truncated = true
            break
          }
          entries += DirEntry(entry.name, entry.isDirectory, entry.size, entry.lastWriteMs)
        }
        DirPage(entries, truncated)
      }
    }
  }

  private companion object {
    const val FIRST_LIST_CAPACITY = 256
  }
}

/** Le operazioni bloccanti, eseguite dentro un tentativo della sessione. */
internal object FsOps {

  /** Nomi dei figli di [dir] (al massimo [limit]); cartella assente = elenco vuoto. */
  fun list(ctx: OpContext, dir: SharePath, limit: Int): NasListing {
    val directory = try {
      ctx.share.openDirectory(dir.smb(), DirAccess.LIST)
    } catch (e: SmbStatusException) {
      if (e.status in ABSENT) return NasListing.EMPTY
      throw e
    }
    directory.use {
      val names = ArrayList<String>(minOf(limit, FIRST_LIST_CAPACITY))
      val iterator = directory.entries()
      while (iterator.hasNext()) {
        ctx.checkAborted()
        val entry = iterator.next()
        if (names.size == limit) return NasListing(names, truncated = true)
        names += entry.name
      }
      return NasListing(names, truncated = false)
    }
  }

  /**
   * Legge [path] se non supera [maxBytes]. La dimensione dichiarata dalla CREATE decide prima di allocare
   * qualunque cosa; poi la lettura ha comunque il tetto `maxBytes + 1` (un server che mente sulla dimensione non
   * fa allocare di piu'). Il buffer parte dalla dimensione dichiarata (entro il tetto) e raddoppia solo se il file
   * si rivela piu' lungo, sempre entro `maxBytes + 1`.
   */
  fun read(ctx: OpContext, path: SharePath, maxBytes: Int): NasRead {
    val file = try {
      ctx.share.openRead(path.smb())
    } catch (e: SmbStatusException) {
      if (e.status in ABSENT) return NasRead.NotFound
      throw e
    }
    file.use {
      val declared = file.declaredSize
      if (declared > maxBytes) return NasRead.TooLarge
      val cap = minOf(maxBytes.toLong() + 1, MAX_ARRAY.toLong()).toInt()
      val expected = if (declared >= 0) declared + 1 else FIRST_BUFFER.toLong()
      var buffer = ByteArray(minOf(expected, cap.toLong()).toInt().coerceAtLeast(1))
      var total = 0
      ctx.startTransfer()
      while (true) {
        if (total == buffer.size) {
          if (buffer.size >= cap) break
          buffer = buffer.copyOf(minOf(buffer.size.toLong() * 2, cap.toLong()).toInt())
        }
        val read = file.read(buffer, total.toLong(), total, minOf(buffer.size - total, CHUNK))
        if (read <= 0) break
        total += read
        ctx.progress(read)
        if (total > maxBytes) return NasRead.TooLarge
      }
      return NasRead.Data(if (total == buffer.size) buffer else buffer.copyOf(total))
    }
  }

  enum class Comparison { SAME, DIFFERENT, MISSING }

  /** Il file in [path] contiene esattamente [bytes]? (Dopo una rinomina dall'esito incerto.) */
  fun compare(ctx: OpContext, path: SharePath, bytes: ByteArray): Comparison =
    when (val found = read(ctx, path, bytes.size)) {
      is NasRead.Data -> if (found.bytes.contentEquals(bytes)) Comparison.SAME else Comparison.DIFFERENT
      NasRead.NotFound -> Comparison.MISSING
      NasRead.TooLarge -> Comparison.DIFFERENT
    }

  /** Crea le cartelle di [dir], una per livello; "esiste gia'" va bene (due dispositivi insieme, §2.3). */
  fun mkdirs(ctx: OpContext, dir: SharePath?) {
    dir ?: return
    for (depth in 1..dir.components.size) {
      try {
        ctx.share.mkdir(dir.components.subList(0, depth).joinToString("\\"))
      } catch (e: SmbStatusException) {
        if (e.status != NtStatus.OBJECT_NAME_COLLISION) throw e
      }
    }
  }

  /** Cancella un proprio temporaneo; "non c'e' piu'" va bene. */
  fun deleteOwnTemp(ctx: OpContext, temp: SharePath) {
    require(temp.parent() == TMP_DIR) { "si cancellano solo i temporanei" }
    try {
      ctx.share.deleteFile(temp.smb())
    } catch (e: SmbStatusException) {
      if (e.status !in ABSENT) throw e
    }
  }

  /** Dove e' arrivata una pubblicazione: serve a capire cosa pulire o verificare dopo una caduta. */
  class PublishProgress {
    /** Il temporaneo creato e non ancora rinominato ne' cancellato. */
    var temp: SharePath? = null

    /** La rinomina e' partita: se la connessione cade adesso, l'esito e' incerto. */
    var renameStarted: Boolean = false
  }

  /**
   * UNA pubblicazione: temporaneo `tmp/<id>-<casuale>` con `FILE_CREATE` → scrittura → flush → verifica della
   * lunghezza → `rename(path, false)`. Le cartelle mancanti si creano solo se servono (meno giri sulla VPN).
   * Collisione → si cancella il PROPRIO temporaneo e il risultato e' `ALREADY_EXISTS`.
   */
  fun publishOnce(ctx: OpContext, path: SharePath, bytes: ByteArray, tempName: () -> String, progress: PublishProgress): PublishResult {
    try {
      val (temp, file) = createTemp(ctx, tempName)
      progress.temp = temp
      var renamed = false
      try {
        ctx.startTransfer()
        var offset = 0
        while (offset < bytes.size) {
          val written = file.write(bytes, offset.toLong(), offset, minOf(CHUNK, bytes.size - offset))
          if (written <= 0) throw NasOpException(NasError.Unexpected(null))
          offset += written
          ctx.progress(written)
        }
        file.flush()
        if (file.length() != bytes.size.toLong()) throw NasOpException(NasError.Unexpected(null))
        progress.renameStarted = true
        try {
          renameCreatingParents(ctx, file, path)
        } catch (e: SmbStatusException) {
          if (e.status == NtStatus.OBJECT_NAME_COLLISION) return PublishResult.ALREADY_EXISTS
          throw e
        }
        renamed = true
        progress.temp = null
        return PublishResult.CREATED
      } finally {
        if (!renamed) {
          try {
            file.deleteOnClose()
            progress.temp = null
          } catch (_: IOException) {
            // connessione persa: il temporaneo resta, lo pulisce il prossimo tentativo o NasHousekeeping
          }
        }
        file.close()
      }
    } catch (e: SmbStatusException) {
      if (e.status == NtStatus.DISK_FULL) {
        val space = try {
          ctx.share.space()
        } catch (_: IOException) {
          null
        }
        throw NasOpException(SmbErrors.diskFull(space), FailureKind.FINAL)
      }
      throw e
    }
  }

  fun createTemp(ctx: OpContext, tempName: () -> String): Pair<SharePath, SmbWriteFile> {
    var collisions = 0
    while (true) {
      val temp = TMP_DIR.child(tempName())
      try {
        return temp to ctx.share.createExclusive(temp.smb())
      } catch (e: SmbStatusException) {
        when {
          e.status == NtStatus.OBJECT_PATH_NOT_FOUND -> mkdirs(ctx, TMP_DIR)
          e.status == NtStatus.OBJECT_NAME_COLLISION && collisions++ == 0 -> Unit
          else -> throw e
        }
      }
    }
  }

  fun renameCreatingParents(ctx: OpContext, file: SmbWriteFile, path: SharePath) {
    try {
      file.renameNoReplace(path.smb())
    } catch (e: SmbStatusException) {
      if (e.status != NtStatus.OBJECT_PATH_NOT_FOUND) throw e
      mkdirs(ctx, path.parent())
      file.renameNoReplace(path.smb())
    }
  }

  private const val FIRST_LIST_CAPACITY = 256
}

/**
 * La pubblicazione esclusiva con i nuovi tentativi giusti (§3.1 "Riconnessione"): prima della rinomina una caduta
 * si ripete da capo con un temporaneo nuovo, dopo aver cancellato quello vecchio; durante la rinomina l'esito e'
 * incerto, quindi dopo la riconnessione si guarda il file finale (uguale ai nostri byte = pubblicato da noi).
 */
internal class Publisher(
  private val manager: SmbNasSessionManager,
  private val shareName: String,
  private val purpose: Purpose,
  private val random: SecureRandom = SecureRandom(),
) {
  suspend fun publish(path: SharePath, bytes: ByteArray): NasResult<PublishResult> {
    var attempt = 0
    var leftover: SharePath? = null
    var renameUncertain = false
    while (true) {
      val progress = FsOps.PublishProgress()
      val result = manager.attemptOnce(shareName, purpose, touchesDisk = true) { ctx ->
        leftover?.let {
          FsOps.deleteOwnTemp(ctx, it)
          leftover = null
        }
        if (renameUncertain) {
          when (FsOps.compare(ctx, path, bytes)) {
            FsOps.Comparison.SAME -> return@attemptOnce PublishResult.CREATED
            FsOps.Comparison.DIFFERENT -> return@attemptOnce PublishResult.ALREADY_EXISTS
            FsOps.Comparison.MISSING -> renameUncertain = false
          }
        }
        FsOps.publishOnce(ctx, path, bytes, ::tempName, progress)
      }
      when (result) {
        is Attempt.Done -> return NasResult.Ok(result.value)
        is Attempt.NoSession -> {
          if (result.error == NasError.SessionExpired && attempt < manager.config.maxRetries) {
            attempt++
            continue
          }
          return NasResult.Err(result.error)
        }
        is Attempt.Failed -> {
          val kind = result.failure.kind
          val retry = attempt < manager.config.maxRetries && (kind == FailureKind.RECONNECT || kind == FailureKind.CREDITS)
          if (!retry) return NasResult.Err(result.failure.error)
          progress.temp?.let { leftover = it }
          if (progress.renameStarted && progress.temp != null) renameUncertain = true
          manager.backoff(manager.config.backoff(attempt))
          attempt++
        }
      }
    }
  }

  private fun tempName(): String = newTempName(manager.deviceId(), random)
}

/** `<deviceId>-<32 cifre esadecimali>`: il nome che NasHousekeeping riconosce come proprio. */
internal fun newTempName(deviceId: String?, random: SecureRandom): String {
  val owner = deviceId?.takeIf { DEVICE_ID.matches(it) } ?: PENDING_TEMP_OWNER
  val bytes = ByteArray(RANDOM_BYTES).also(random::nextBytes)
  return owner + "-" + bytes.joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
}
