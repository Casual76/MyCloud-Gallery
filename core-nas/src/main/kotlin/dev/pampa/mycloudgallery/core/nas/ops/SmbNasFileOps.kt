package dev.pampa.mycloudgallery.core.nas.ops

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nas.contract.NasAttributes
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.fs.ABSENT
import dev.pampa.mycloudgallery.core.nas.fs.rooted
import dev.pampa.mycloudgallery.core.nas.fs.smb
import dev.pampa.mycloudgallery.core.nas.noop.NoNasFileOps
import dev.pampa.mycloudgallery.core.nas.session.FailureKind
import dev.pampa.mycloudgallery.core.nas.session.NasOpException
import dev.pampa.mycloudgallery.core.nas.session.OpContext
import dev.pampa.mycloudgallery.core.nas.session.SmbErrors
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.smb.DirAccess
import dev.pampa.mycloudgallery.core.nas.smb.NtStatus
import dev.pampa.mycloudgallery.core.nas.smb.SmbChangeHandle
import dev.pampa.mycloudgallery.core.nas.smb.SmbCopyChunk
import dev.pampa.mycloudgallery.core.nas.smb.SmbFileInfo
import dev.pampa.mycloudgallery.core.nas.smb.SmbShare
import dev.pampa.mycloudgallery.core.nas.smb.SmbStatusException
import dev.pampa.mycloudgallery.core.nas.smb.SmbWriteFile
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.IOException
import java.util.Locale

// Fase 4B, P12 (docs/fase4-progetto.md §1.2 "core-nas (P12, 4B): primitive di scrittura", D2, D24, D25; formato-nas.md §9.3):
// le primitive di scrittura sopra la sessione SMB. Ogni chiamata passa dal gestore della sessione come la lettura (percorso,
// interruttore, recinzione D33, limitatore, sorvegliante) e **non viene mai ripetuta da sola** dopo una caduta (`idempotent =
// false`, tranne `mkdirs` e `space`): la risposta potrebbe essersi persa dopo che il NAS l'ha applicata, e solo chi chiama
// (`core-nasops`, con il suo diario) sa come riconoscerlo.
//
// Le modifiche avvengono **sullo stesso handle** su cui si e' letto lo stato (`SmbShare.openForChange`): il timbro della
// sorgente si controlla prima del `SET_INFO` della rinomina (E-D13, E-N11) e la dimensione prima della cancellazione alla
// chiusura (revisione B7), mai su due aperture diverse.

/**
 * Le primitive di scrittura per UN dispositivo ([ownerDeviceId]) sopra [manager]. La consegna solo [NasFileOpsFactory] a
 * `core-nasops`: `NasSessionManager` non la espone (a differenza di `mediaReader()`).
 *
 * @param root la radice al posto di quella della share (tutti i percorsi, media e area dell'app, finiscono sotto `root/`):
 *   solo l'area di prova dei test d'integrazione (§5.4, `mcg-it/<giro>/`).
 */
@DestructiveNasAccess
internal class SmbNasFileOps(
  private val manager: SmbNasSessionManager,
  override val ownerDeviceId: String,
  private val root: SharePath? = null,
) : NasFileOps {

  init {
    require(TrustIds.isValid(ownerDeviceId)) { "Il dispositivo proprietario e' un id valido." }
  }

  /** Il percorso SMB di [path] (con la radice dell'area di prova, se c'e'). */
  private fun at(path: SharePath): String = rooted(root, path).smb()

  private fun componentsOf(path: SharePath): List<String> = rooted(root, path).components

  // --- mkdirs -----------------------------------------------------------------------------------------------------------

  override suspend fun mkdirs(share: String, dir: SharePath, purpose: Purpose): NasResult<Unit> {
    require(NasOpsPaths.isMediaPath(dir)) { "mkdirs vale solo fuori da .mycloudgallery/." }
    return manager.runOp(share, purpose, idempotent = true, touchesDisk = true) { ctx -> ensureDir(ctx.share, componentsOf(dir)) }
  }

  // --- rename, trashMove, publishTemp ----------------------------------------------------------------------------------

  override suspend fun rename(share: String, from: SharePath, to: SharePath, expect: NasStamp?, purpose: Purpose): NasResult<RenameOutcome> {
    require(
      (NasOpsPaths.isMediaPath(from) && NasOpsPaths.isMediaPath(to)) || (NasOpsPaths.isTrashItem(from) && NasOpsPaths.isMediaPath(to)),
    ) { "Rinomina non ammessa: solo fra percorsi di media, o dal cestino al posto d'origine." }
    // Stesso posto, anche solo per le maiuscole (E-N7): il chiamante passa da un nome temporaneo. Nessuna richiesta.
    if (from.value.lowercase(Locale.ROOT) == to.value.lowercase(Locale.ROOT)) return NasResult.Ok(RenameOutcome.Collision)
    return manager.runOp(share, purpose, idempotent = false, touchesDisk = true) { ctx ->
      renameOnce(ctx, from, to, expect, createParents = false)
    }
  }

  override suspend fun trashMove(share: String, path: SharePath, trashId: String, expect: NasStamp?, purpose: Purpose): NasResult<RenameOutcome> {
    require(NasOpsPaths.isMediaPath(path)) { "Nel cestino si mette un media, mai un file dell'app." }
    require(TrustIds.isValid(trashId)) { "Un id di cestino valido." }
    val target = NasLayout.trashItemFile(ownerDeviceId, trashId)
    return manager.runOp(share, purpose, idempotent = false, touchesDisk = true) { ctx ->
      renameOnce(ctx, path, target, expect, createParents = true)
    }
  }

  override suspend fun publishTemp(share: String, temp: SharePath, final: SharePath, purpose: Purpose): NasResult<PublishOutcome> {
    require(NasOpsPaths.isTempOf(temp, ownerDeviceId)) { "Un temporaneo di questo dispositivo." }
    require(NasOpsPaths.isMediaPath(final)) { "La destinazione e' un percorso di media." }
    return manager.runOp(share, purpose, idempotent = false, touchesDisk = true) { ctx ->
      when (val outcome = renameOnce(ctx, temp, final, expect = null, createParents = true)) {
        is RenameOutcome.Renamed -> PublishOutcome.Published(outcome.stamp)
        RenameOutcome.Collision -> PublishOutcome.Collision
        // Un temporaneo e' un file: "cartella in uso" non puo' arrivare di qui, ma se arrivasse e' un'attesa come le altre.
        RenameOutcome.Locked, RenameOutcome.FolderInUse -> PublishOutcome.Locked
        RenameOutcome.SourceMissing, is RenameOutcome.SourceChanged -> PublishOutcome.TempMissing
      }
    }
  }

  /**
   * UNA rinomina su un handle: apertura con `DELETE`, timbro letto sullo stesso handle e confrontato con [expect] (solo per i
   * file), poi `SET_INFO` senza sostituire. Con [createParents] le cartelle mancanti di [to] si creano e si riprova una volta.
   *
   * Una **cartella** rifiutata con `ACCESS_DENIED` non e' per forza un permesso negato: se sotto c'e' un handle nostro aperto (la Libreria che
   * legge una miniatura) Samba risponde cosi' ([retryWithoutOwnHandles]). Senza un handle nostro sotto, resta l'errore di sempre.
   */
  private fun renameOnce(ctx: OpContext, from: SharePath, to: SharePath, expect: NasStamp?, createParents: Boolean): RenameOutcome {
    val share = ctx.share
    val handle = openChange(share, from, exclusive = false) ?: return RenameOutcome.SourceMissing
    if (handle === LOCKED) return RenameOutcome.Locked
    handle.use { h ->
      val info = h.info
      if (expect != null && !info.isDirectory) {
        val actual = stampOf(info)
        if (!expect.sameAs(actual)) return RenameOutcome.SourceChanged(actual)
      }
      fun attempt(): RenameOutcome? = try {
        h.renameNoReplace(at(to))
        null
      } catch (e: SmbStatusException) {
        when (e.status) {
          NtStatus.OBJECT_NAME_COLLISION -> RenameOutcome.Collision
          NtStatus.SHARING_VIOLATION, NtStatus.FILE_LOCK_CONFLICT -> RenameOutcome.Locked
          NtStatus.OBJECT_PATH_NOT_FOUND -> MISSING_PARENT
          NtStatus.ACCESS_DENIED -> if (info.isDirectory) FOLDER_DENIED else throw e
          else -> throw e
        }
      }
      var outcome = attempt()
      if (outcome === FOLDER_DENIED) outcome = retryWithoutOwnHandles(ctx, from, ::attempt)
      if (outcome === MISSING_PARENT) {
        if (!createParents) throw NasOpException(NasError.NotFound)
        to.parent()?.let { ensureDir(share, componentsOf(it)) }
          ?: root?.let { ensureDir(share, it.components) }
        outcome = attempt()
        if (outcome === FOLDER_DENIED) outcome = retryWithoutOwnHandles(ctx, from, ::attempt)
        if (outcome === MISSING_PARENT) throw NasOpException(NasError.NotFound)
      }
      return outcome ?: RenameOutcome.Renamed(stampOf(info))
    }
  }

  /**
   * La rinomina della cartella [dir] e' stata rifiutata con `ACCESS_DENIED`. Se sotto la cartella il registro degli handle propri ne ha di
   * aperti, il rifiuto e' di Samba che non rinomina una cartella con un file aperto sotto dallo stesso client: si rilasciano i nostri handle di
   * lettura (il proprietario li riapre da solo alla lettura dopo), si aspetta che la `CLOSE` arrivi al NAS e si riprova, al massimo
   * [RELEASE_ROUNDS] volte. Se alla fine qualcuno e' ancora aperto (un elenco a meta', una lettura che si e' riaperta) la risposta e'
   * [RenameOutcome.FolderInUse]: l'esecutore riprova fra poco.
   *
   * **Un vero permesso negato resta un errore**: senza alcun handle nostro sotto la cartella (la prima volta), o con handle visti ma nessuno
   * piu' sotto dopo i tentativi (il rifiuto non dipendeva da loro), si rilancia lo stesso `ACCESS_DENIED` di prima.
   */
  private fun retryWithoutOwnHandles(ctx: OpContext, dir: SharePath, attempt: () -> RenameOutcome?): RenameOutcome? {
    val share = ctx.share.name
    val path = at(dir)
    var seenOwn = false
    repeat(RELEASE_ROUNDS) {
      if (ctx.ownHandles.countBelow(share, path) > 0) {
        seenOwn = true
        ctx.ownHandles.releaseBelow(share, path)
        // Le chiusure chieste (anche adesso) arrivano al NAS prima della richiesta che segue: e' il caso che si vuole sistemare.
        manager.awaitCloses()
      } else if (!seenOwn) {
        throw SmbStatusException(NtStatus.ACCESS_DENIED)
      }
      val outcome = attempt()
      if (outcome !== FOLDER_DENIED) return outcome
    }
    if (ctx.ownHandles.countBelow(share, path) > 0) return RenameOutcome.FolderInUse
    throw SmbStatusException(NtStatus.ACCESS_DENIED)
  }

  // --- copia lato server e temporanei ----------------------------------------------------------------------------------

  override suspend fun copyToTemp(
    fromShare: String,
    from: SharePath,
    toShare: String,
    temp: SharePath,
    expect: NasStamp?,
    purpose: Purpose,
  ): NasResult<CopyOutcome> {
    require(NasOpsPaths.isMediaPath(from)) { "La sorgente di una copia e' un media." }
    require(NasOpsPaths.isTempOf(temp, ownerDeviceId)) { "La destinazione e' un temporaneo di questo dispositivo." }
    // D33: anche la sorgente, prima di qualunque richiesta (la destinazione la controlla la sessione).
    manager.fenced(fromShare)?.let { return NasResult.Err(it) }
    return manager.runOp(toShare, purpose, idempotent = false, touchesDisk = true) { ctx -> copyOnce(ctx, fromShare, from, temp, expect) }
  }

  private fun copyOnce(ctx: OpContext, fromShare: String, from: SharePath, temp: SharePath, expect: NasStamp?): CopyOutcome {
    val sourceShare = try {
      ctx.otherShare(fromShare)
    } catch (e: SmbStatusException) {
      if (e.status == NtStatus.BAD_NETWORK_NAME) throw NasOpException(NasError.ShareMissing(fromShare))
      throw e
    }
    val source = try {
      sourceShare.openRead(at(from))
    } catch (e: SmbStatusException) {
      return when (e.status) {
        in ABSENT -> CopyOutcome.SourceMissing
        NtStatus.SHARING_VIOLATION, NtStatus.FILE_LOCK_CONFLICT -> CopyOutcome.Locked
        else -> throw e
      }
    }
    source.use {
      val size = source.declaredSize
      if (size < 0) throw NasOpException(NasError.Unexpected(null))
      val info = source.info ?: sourceShare.stat(at(from))
      if (info.isDirectory) throw NasOpException(NasError.Unexpected(null))
      val actual = NasStamp(size, info.lastWriteMs, info.fileId)
      if (expect != null && !expect.sameAs(actual)) return CopyOutcome.SourceChanged(actual)
      val key = try {
        source.resumeKey()
      } catch (e: SmbStatusException) {
        if (e.status in COPY_REFUSED) return CopyOutcome.NotSupported
        throw e
      }
      val file = createTemp(ctx.share, temp) ?: return CopyOutcome.TempExists
      var keep = false
      try {
        ctx.startTransfer()
        var offset = 0L
        while (offset < size) {
          val chunks = ArrayList<SmbCopyChunk>(CHUNKS_PER_REQUEST)
          var next = offset
          while (chunks.size < CHUNKS_PER_REQUEST && next < size) {
            val length = minOf(CHUNK_BYTES, size - next)
            chunks += SmbCopyChunk(next, next, length)
            next += length
          }
          val written = try {
            file.copyChunks(key, chunks)
          } catch (e: SmbStatusException) {
            // E-D12: la copia lato server non c'e' o e' negata. Il temporaneo si toglie (finally) e il chiamante ripiega.
            if (e.status in COPY_REFUSED) return CopyOutcome.NotSupported
            throw e
          }
          if (written <= 0) throw NasOpException(NasError.Unexpected(null))
          offset += written
          ctx.progress(written.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        file.flush()
        if (file.length() != size) throw NasOpException(NasError.Unexpected(null))
        keep = true
      } catch (e: SmbStatusException) {
        if (e.status == NtStatus.DISK_FULL) throw NasOpException(SmbErrors.diskFull(spaceOrNull(ctx.share)), FailureKind.FINAL)
        throw e
      } finally {
        if (!keep) {
          try {
            file.deleteOnClose()
          } catch (_: IOException) {
            // connessione persa: il temporaneo resta, lo riconosce la ripresa dal diario (dimensione sbagliata = si rifa')
          }
        }
        file.close()
      }
      val copied = try {
        ctx.share.stat(at(temp))
      } catch (_: SmbStatusException) {
        null
      }
      return CopyOutcome.Copied(size, NasStamp(size, copied?.lastWriteMs ?: info.lastWriteMs, copied?.fileId))
    }
  }

  override suspend fun openTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<NasTempWriter> {
    require(NasOpsPaths.isTempOf(temp, ownerDeviceId)) { "Un temporaneo di questo dispositivo." }
    // Prova F16: annullati mentre il NAS risponde, lo scrittore non arriva a nessuno; il suo handle esclusivo si chiude qui.
    var undelivered: SmbTempWriter? = null
    try {
      val result = manager.runOp(share, purpose, idempotent = false, touchesDisk = true) { ctx ->
        val file = createTemp(ctx.share, temp) ?: throw NasOpException(NasError.Exists)
        SmbTempWriter(share, temp, purpose, file, ctx.sessionKey).also { undelivered = it }
      }
      undelivered = null
      return result
    } finally {
      undelivered?.close()
    }
  }

  /** Crea [temp] in esclusiva (creando `tmp/` se manca); null se il nome c'e' gia'. */
  private fun createTemp(share: SmbShare, temp: SharePath): SmbWriteFile? {
    fun create(): SmbWriteFile? = try {
      share.createExclusive(at(temp))
    } catch (e: SmbStatusException) {
      if (e.status == NtStatus.OBJECT_NAME_COLLISION) null else throw e
    }
    return try {
      create()
    } catch (e: SmbStatusException) {
      if (e.status != NtStatus.OBJECT_PATH_NOT_FOUND) throw e
      ensureDir(share, componentsOf(checkNotNull(temp.parent())))
      create()
    }
  }

  /** Lo scrittore di un temporaneo: un handle legato alla sessione che l'ha aperto (dopo una riconnessione e' morto). */
  private inner class SmbTempWriter(
    private val share: String,
    override val temp: SharePath,
    private val purpose: Purpose,
    private val file: SmbWriteFile,
    private val session: Any,
  ) : NasTempWriter {
    @Volatile private var closed = false

    /** La fine dei dati scritti finora: una scrittura oltre lascerebbe un buco. */
    @Volatile private var end = 0L

    private fun sameSession(ctx: OpContext) {
      if (ctx.sessionKey !== session) throw NasOpException(NasError.SessionExpired, FailureKind.FINAL)
    }

    override suspend fun write(offset: Long, buf: ByteArray, off: Int, len: Int): NasResult<Unit> {
      check(!closed) { "Scrittore chiuso." }
      require(offset >= 0 && off >= 0 && len >= 0 && off + len <= buf.size) { "Intervallo non valido." }
      require(offset <= end) { "Scrittura oltre la fine del temporaneo." }
      return manager.runOp(share, purpose, idempotent = false, touchesDisk = true) { ctx ->
        sameSession(ctx)
        try {
          var done = 0
          while (done < len) {
            val written = file.write(buf, offset + done, off + done, len - done)
            if (written <= 0) throw NasOpException(NasError.Unexpected(null))
            done += written
          }
          end = maxOf(end, offset + len)
        } catch (e: SmbStatusException) {
          if (e.status == NtStatus.DISK_FULL) throw NasOpException(SmbErrors.diskFull(spaceOrNull(ctx.share)), FailureKind.FINAL)
          throw e
        }
      }
    }

    override suspend fun finish(expectedSize: Long): NasResult<TempFinish> {
      check(!closed) { "Scrittore chiuso." }
      return manager.runOp(share, purpose, idempotent = false, touchesDisk = true) { ctx ->
        sameSession(ctx)
        file.flush()
        val actual = file.length()
        if (actual != expectedSize) {
          TempFinish.SizeMismatch(actual)
        } else {
          val info = ctx.share.stat(at(temp))
          TempFinish.Complete(NasStamp(actual, info.lastWriteMs, info.fileId))
        }
      }
    }

    override fun close() {
      if (closed) return
      closed = true
      manager.closeLater(file, session)
    }
  }

  // --- cancellazioni (solo dentro .mycloudgallery/) --------------------------------------------------------------------

  override suspend fun deleteIfUnchanged(share: String, path: SharePath, size: Long, purpose: Purpose, expect: NasStamp?): NasResult<DeleteOutcome> {
    require(NasOpsPaths.isAppArea(path)) { "L'eliminazione definitiva vale solo dentro .mycloudgallery/: un media va nel cestino." }
    require(expect == null || expect.size == size) { "Il timbro atteso ha la sua dimensione." }
    return manager.runOp(share, purpose, idempotent = false, touchesDisk = true) { ctx -> deleteOnce(ctx.share, path, size, expect) }
  }

  override suspend fun deleteOwnTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<DeleteOutcome> {
    require(NasOpsPaths.isTempOf(temp, ownerDeviceId)) { "Un temporaneo di questo dispositivo." }
    return manager.runOp(share, purpose, idempotent = false, touchesDisk = true) { ctx -> deleteOnce(ctx.share, temp, expectedSize = null, expect = null) }
  }

  override suspend fun parentIdentity(share: String, dir: SharePath, purpose: Purpose): NasResult<Long?> {
    require(NasOpsPaths.isOwnTrashTree(dir, ownerDeviceId)) { "Solo nel proprio cestino." }
    return manager.runOp(share, purpose, idempotent = true, touchesDisk = true) { ctx ->
      try {
        ctx.share.openDirectory(at(dir), DirAccess.LIST).use { it.parentFileId() }
      } catch (e: SmbStatusException) {
        if (e.status in ABSENT || e.status == NtStatus.NOT_A_DIRECTORY) throw NasOpException(NasError.NotFound)
        throw e
      }
    }
  }

  /**
   * UNA cancellazione con UN handle (formato §9.3, revisione B7): apertura **esclusiva** (nessun altro puo' aprire il file finche'
   * l'handle e' aperto), dimensione (e, con [expect], data e `fileId`) letta su quell'handle, poi cancellazione alla chiusura dello
   * stesso handle. Un punto di rianalisi non si cancella (R3): l'handle e' aperto sul collegamento, non sulla destinazione.
   */
  private fun deleteOnce(share: SmbShare, path: SharePath, expectedSize: Long?, expect: NasStamp?): DeleteOutcome {
    val handle = openChange(share, path, exclusive = true) ?: return DeleteOutcome.Missing
    if (handle === LOCKED) return DeleteOutcome.Locked
    handle.use { h ->
      val info = h.info
      if (info.isDirectory || (info.attributes and NasAttributes.REPARSE_POINT) != 0) return DeleteOutcome.NotAFile
      if (expectedSize != null && info.size != expectedSize) return DeleteOutcome.SizeChanged(info.size)
      if (expect != null && !expect.sameAs(stampOf(info))) return DeleteOutcome.SizeChanged(info.size)
      try {
        h.deleteOnClose()
      } catch (e: SmbStatusException) {
        if (e.status == NtStatus.SHARING_VIOLATION || e.status == NtStatus.FILE_LOCK_CONFLICT) return DeleteOutcome.Locked
        if (e.status in ABSENT) return DeleteOutcome.Missing
        throw e
      }
      return DeleteOutcome.Deleted
    }
  }

  override suspend fun rmdirIfEmpty(share: String, dir: SharePath, purpose: Purpose): NasResult<RmdirOutcome> {
    require(NasOpsPaths.isMediaPath(dir) || NasOpsPaths.isOwnTrashTree(dir, ownerDeviceId)) {
      "rmdirIfEmpty vale fuori da .mycloudgallery/ o nel proprio cestino."
    }
    return manager.runOp(share, purpose, idempotent = false, touchesDisk = true) { ctx ->
      val handle = openChange(ctx.share, dir, exclusive = true)
      when {
        handle == null -> RmdirOutcome.Missing
        handle === LOCKED -> RmdirOutcome.Locked
        else -> handle.use { h ->
          if (!h.info.isDirectory) throw NasOpException(NasError.NotFound)
          // R3: un collegamento (punto di rianalisi) non si toglie, neanche "vuoto": resta dov'e'.
          if ((h.info.attributes and NasAttributes.REPARSE_POINT) != 0) return@use RmdirOutcome.NotEmpty
          try {
            h.deleteOnClose()
            RmdirOutcome.Removed
          } catch (e: SmbStatusException) {
            when (e.status) {
              NtStatus.DIRECTORY_NOT_EMPTY -> RmdirOutcome.NotEmpty
              NtStatus.SHARING_VIOLATION, NtStatus.FILE_LOCK_CONFLICT -> RmdirOutcome.Locked
              in ABSENT -> RmdirOutcome.Missing
              else -> throw e
            }
          }
        }
      }
    }
  }

  override suspend fun space(share: String, purpose: Purpose): NasResult<NasSpaceInfo> =
    manager.runOp(share, purpose, idempotent = true, touchesDisk = true) { ctx ->
      val space = ctx.share.space()
      val free = space.totalFreeBytes.coerceAtLeast(0)
      NasSpaceInfo(
        totalBytes = (space.totalBytes ?: free).coerceAtLeast(free),
        freeBytes = free,
        callerFreeBytes = space.callerFreeBytes.coerceIn(0, free),
      )
    }

  // --- interni ------------------------------------------------------------------------------------------------------------

  /** L'handle per cambiare [path]; null se non c'e'; [LOCKED] se e' aperto da un altro programma (E-N13). */
  private fun openChange(share: SmbShare, path: SharePath, exclusive: Boolean): SmbChangeHandle? =
    try {
      share.openForChange(at(path), exclusive)
    } catch (e: SmbStatusException) {
      when (e.status) {
        in ABSENT -> null
        NtStatus.SHARING_VIOLATION, NtStatus.FILE_LOCK_CONFLICT -> LOCKED
        else -> throw e
      }
    }

  /**
   * Crea la cartella [components] e quelle che mancano. "Esiste gia'" come cartella = fatto; come file (lei o un livello sopra)
   * = `NasError.Exists`. Prima si prova il livello cercato, poi si risale solo se manca il genitore (meno giri sulla VPN).
   */
  private fun ensureDir(share: SmbShare, components: List<String>) {
    if (components.isEmpty()) return
    val raw = components.joinToString("\\")
    try {
      share.mkdir(raw)
      return
    } catch (e: SmbStatusException) {
      when (e.status) {
        NtStatus.OBJECT_NAME_COLLISION -> if (isDirectory(share, raw)) return else throw NasOpException(NasError.Exists)
        NtStatus.NOT_A_DIRECTORY -> throw NasOpException(NasError.Exists)
        NtStatus.OBJECT_PATH_NOT_FOUND -> if (components.size == 1) throw e
        else -> throw e
      }
    }
    ensureDir(share, components.dropLast(1))
    try {
      share.mkdir(raw)
    } catch (e: SmbStatusException) {
      when (e.status) {
        NtStatus.OBJECT_NAME_COLLISION -> if (!isDirectory(share, raw)) throw NasOpException(NasError.Exists)
        NtStatus.NOT_A_DIRECTORY, NtStatus.OBJECT_PATH_NOT_FOUND -> throw NasOpException(NasError.Exists)
        else -> throw e
      }
    }
  }

  private fun isDirectory(share: SmbShare, raw: String): Boolean =
    try {
      share.stat(raw).isDirectory
    } catch (e: SmbStatusException) {
      if (e.status in ABSENT) false else throw e
    }

  private fun spaceOrNull(share: SmbShare) = try {
    share.space()
  } catch (_: IOException) {
    null
  }

  private fun stampOf(info: SmbFileInfo): NasStamp = NasStamp(if (info.isDirectory) 0 else info.size, info.lastWriteMs, info.fileId)

  private companion object {
    const val CHUNK_BYTES = 1024L * 1024
    const val CHUNKS_PER_REQUEST = 16

    /** `STATUS_INVALID_DEVICE_REQUEST`: un FSCTL che il server non conosce (copia lato server assente). */
    const val INVALID_DEVICE_REQUEST: Long = 0xC0000010L

    /** La copia lato server non c'e' o e' negata (E-D12): si ripiega dal telefono. */
    val COPY_REFUSED: Set<Long> = setOf(NtStatus.NOT_SUPPORTED, NtStatus.ACCESS_DENIED, INVALID_DEVICE_REQUEST)

    /** Quante volte, al massimo, una cartella rifiutata si riprova dopo aver rilasciato i nostri handle (poi `FolderInUse`). */
    const val RELEASE_ROUNDS = 3

    /** Segnaposto interni (mai restituiti a chi chiama). */
    val MISSING_PARENT: RenameOutcome = RenameOutcome.SourceChanged(null)

    /** `ACCESS_DENIED` su una cartella: ancora da capire se e' un permesso negato vero o un nostro handle sotto. */
    val FOLDER_DENIED: RenameOutcome = RenameOutcome.SourceChanged(null)
    val LOCKED: SmbChangeHandle = object : SmbChangeHandle {
      override val info: SmbFileInfo get() = error("segnaposto")

      override fun renameNoReplace(newPath: String) = error("segnaposto")

      override fun deleteOnClose() = error("segnaposto")

      override fun close() = Unit
    }
  }
}

/**
 * Chi consegna le primitive di scrittura a `core-nasops` (D2): l'unico modo di averle sopra una sessione vera. `NasSessionManager`
 * non le espone; questo oggetto richiede l'adesione al marcatore, che solo `core-nas` e `core-nasops` hanno.
 */
@DestructiveNasAccess
object NasFileOpsFactory {

  /** Le primitive per il dispositivo [ownerDeviceId] sopra [session]; con una sessione che non e' SMB (un fake), nessuna scrittura. */
  fun create(session: NasSessionManager, ownerDeviceId: String): NasFileOps = when (session) {
    is SmbNasSessionManager -> SmbNasFileOps(session, ownerDeviceId)
    else -> NoNasFileOps
  }

  /**
   * Come [create], ma con **tutti** i percorsi (media e area dell'app: temporanei, cestino) sotto [root] della share: solo per l'area di
   * prova dei test d'integrazione e del banco delle interruzioni (`mcg-it/<giro>/...`, §5.4). La radice restringe e basta: un percorso
   * relativo non puo' uscirne (i `SharePath` non hanno `..`), e la guardia dell'area di prova resta comunque davanti al trasporto.
   */
  fun createForTestArea(session: NasSessionManager, ownerDeviceId: String, root: SharePath): NasFileOps = when (session) {
    is SmbNasSessionManager -> SmbNasFileOps(session, ownerDeviceId, root)
    else -> NoNasFileOps
  }
}
