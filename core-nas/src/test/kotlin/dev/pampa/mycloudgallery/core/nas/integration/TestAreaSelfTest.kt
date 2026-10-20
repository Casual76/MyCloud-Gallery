package dev.pampa.mycloudgallery.core.nas.integration

import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.ListOutcome
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasRandomFile
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.ServerCopy
import dev.pampa.mycloudgallery.core.nas.runBlockingBounded
import dev.pampa.mycloudgallery.core.nas.smb.DirAccess
import dev.pampa.mycloudgallery.core.nas.smb.SmbChangeHandle
import dev.pampa.mycloudgallery.core.nas.smb.SmbConnection
import dev.pampa.mycloudgallery.core.nas.smb.SmbCopyChunk
import dev.pampa.mycloudgallery.core.nas.smb.SmbDirectory
import dev.pampa.mycloudgallery.core.nas.smb.SmbFileInfo
import dev.pampa.mycloudgallery.core.nas.smb.SmbOpenMode
import dev.pampa.mycloudgallery.core.nas.smb.SmbReadFile
import dev.pampa.mycloudgallery.core.nas.smb.SmbShare
import dev.pampa.mycloudgallery.core.nas.smb.SmbSpace
import dev.pampa.mycloudgallery.core.nas.smb.SmbTransport
import dev.pampa.mycloudgallery.core.nas.smb.SmbWriteFile
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.SocketBinding
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/**
 * L'autotest della guardia (§5.4, punto 4): prova `../`, un'altra share, la radice, la `.mycloudgallery` vera, i nomi
 * di famiglia scritti con maiuscole diverse e gli account vietati, a livello delle interfacce e del trasporto. Tutti
 * devono essere rifiutati con **zero** chiamate a cio' che sta dietro (contatori). Non apre socket: la suite lo esegue
 * prima di qualunque connessione, e gira anche da solo nei test JVM (`TestAreaGuardTest`).
 */
internal object TestAreaSelfTest {

  /** I casi che NON sono stati rifiutati (vuoto = guardia sana) e quante prove sono state fatte. */
  class Result(val failures: List<String>, val cases: Int)

  fun run(guard: TestAreaGuard): Result {
    val failures = ArrayList<String>()
    var cases = 0
    val share = guard.shares.first()
    val area = guard.root
    fun expectRefused(case: String, block: () -> Unit) {
      cases++
      try {
        block()
        failures += case
      } catch (_: TestAreaViolation) {
        // rifiutato, come deve
      } catch (_: IllegalArgumentException) {
        // lo rifiuta gia' SharePath: non arriva mai a una chiamata
      }
    }

    // Account e share, anche scritti in altre forme.
    for (account in listOf("alessio", "ALESSIO", "Admin", "pampa", " alessio", "ａｌｅｓｓｉｏ")) {
      expectRefused("account $account") { guard.checkAccount(account) }
    }
    for (other in listOf("NAS", "Nas", "PUBLIC", "Public", "ＮＡＳ", "alessio", "Alessio", "pampa", "test ", "ｔｅｓｔ", "IPC$")) {
      expectRefused("share $other") { guard.checkShare(other) }
    }

    // Percorsi grezzi (anche quelli che SharePath non sa costruire).
    val runPath = area.value
    for (raw in listOf("", "..", "../x", "$runPath/../x", "$runPath/../../NAS", "mcg-it/./${guard.runId}/x", "a//b", runPath)) {
      expectRefused("percorso '$raw'") { guard.checkRaw(share, raw) }
    }

    // Interfacce: un lettore e una copia finti che contano (devono restare a zero).
    val counting = CountingMedia()
    val media = TestAreaMedia(counting.reader, counting.copy, guard)
    val inside = area.child("a.jpg")
    val outside = listOf(
      SharePath.of("a.jpg"),
      SharePath.of(".mycloudgallery", "registry", "1"),
      SharePath.of(TestAreaGuard.ROOT, "x"),
      SharePath.of(TestAreaGuard.ROOT, "20200101-000000-00000000", "a.jpg"),
      SharePath.of("Foto", TestAreaGuard.ROOT, guard.runId, "a.jpg"),
    )
    runBlockingBounded("l'autotest della guardia") {
      for (path in outside) {
        val n = path.components.size
        expectRefused("stat ($n)") { runBlockingBounded("l'autotest della guardia") { media.mediaReader.stat(share, path, Purpose.BACKGROUND) } }
        expectRefused("open ($n)") { runBlockingBounded("l'autotest della guardia") { media.mediaReader.open(share, path, Purpose.BACKGROUND) } }
        expectRefused("copia verso fuori ($n)") { runBlockingBounded("l'autotest della guardia") { media.serverCopy.copy(share, inside, share, path) } }
        expectRefused("copia da fuori ($n)") { runBlockingBounded("l'autotest della guardia") { media.serverCopy.copy(share, path, share, inside) } }
      }
      expectRefused("elenco della radice") { runBlockingBounded("l'autotest della guardia") { media.mediaReader.list(share, null, Purpose.BACKGROUND) {} } }
      expectRefused("elenco di mcg-it") { runBlockingBounded("l'autotest della guardia") { media.mediaReader.list(share, SharePath.of(TestAreaGuard.ROOT), Purpose.BACKGROUND) {} } }
      for (other in listOf("NAS", "Public", "PUBLIC")) {
        expectRefused("stat su $other") { runBlockingBounded("l'autotest della guardia") { media.mediaReader.stat(other, inside, Purpose.BACKGROUND) } }
        expectRefused("copia verso $other") { runBlockingBounded("l'autotest della guardia") { media.serverCopy.copy(share, inside, other, inside) } }
      }
    }
    if (counting.calls.get() != 0 || media.delegateCalls.get() != 0) failures += "chiamate passate dalle interfacce"

    // Trasporto: una share finta che conta.
    val dummy = CountingShare(share)
    val transport = TestAreaTransport(RefusingTransport, guard)
    val wrapped = transport.wrap(share, dummy)
    val smbArea = area.components.joinToString("\\")
    val forbidden = listOf("", "a.jpg", ".mycloudgallery\\tmp\\x", "mcg-it\\altro\\x", "..\\x", "$smbArea\\..\\..\\x", "Foto\\$smbArea")
    for (path in forbidden) {
      expectRefused("trasporto apre '$path'") { wrapped.openRead(path) }
      expectRefused("trasporto crea '$path'") { wrapped.createExclusive(path) }
      expectRefused("trasporto cancella '$path'") { wrapped.deleteFile(path) }
      expectRefused("trasporto elenca '$path'") { wrapped.openDirectory(path, DirAccess.LIST) }
      expectRefused("trasporto stato '$path'") { wrapped.stat(path) }
      expectRefused("trasporto apre per cambiare '$path'") { wrapped.openForChange(path, exclusive = true) }
    }
    expectRefused("trasporto toglie mcg-it non creata da noi") { wrapped.removeEmptyDirectory(TestAreaGuard.ROOT) }
    expectRefused("trasporto cancella mcg-it") { wrapped.deleteFile(TestAreaGuard.ROOT) }
    expectRefused("trasporto su un'altra share") { transport.wrap("NAS", dummy) }
    // Una rinomina che porta fuori dall'area, da un file creato dentro (l'unica chiamata ammessa).
    val created = wrapped.createExclusive("$smbArea\\dentro.tmp")
    expectRefused("rinomina verso fuori") { created.renameNoReplace("fuori.jpg") }
    expectRefused("rinomina verso NAS") { created.renameNoReplace("..\\..\\fuori.jpg") }
    // 4B: un file dell'area aperto per cambiarlo (seconda chiamata ammessa) non si rinomina fuori dall'area.
    val opened = wrapped.openForChange("$smbArea\\dentro.tmp", exclusive = false)
    expectRefused("rinomina con l'handle verso fuori") { opened.renameNoReplace("fuori.jpg") }
    expectRefused("rinomina con l'handle verso mcg-it") { opened.renameNoReplace("${TestAreaGuard.ROOT}\\fuori.jpg") }
    expectRefused("rinomina con l'handle verso un giro vecchio") { opened.renameNoReplace("${TestAreaGuard.ROOT}\\20200101-000000-00000000\\x") }
    if (dummy.calls.get() != 2) failures += "chiamate passate dal trasporto: ${dummy.calls.get() - 2} oltre alle due ammesse"
    if (transport.delegateCalls.get() != 2) failures += "contatore del trasporto: ${transport.delegateCalls.get()}"
    return Result(failures, cases)
  }

  /** Un trasporto che non si deve mai chiamare. */
  private object RefusingTransport : SmbTransport {
    override fun connect(binding: SocketBinding, address: NasAddress, connectTimeout: Duration): SmbConnection =
      throw AssertionError("nessuna connessione nell'autotest")
  }

  /** Una share finta che conta le chiamate (e ne ammette una sola: la creazione dentro l'area). */
  private class CountingShare(override val name: String) : SmbShare {
    val calls = AtomicInteger(0)

    private fun count(): Nothing {
      calls.incrementAndGet()
      throw AssertionError("chiamata arrivata al trasporto")
    }

    override fun openRead(path: String): SmbReadFile = count()

    override fun createExclusive(path: String, shareMode: SmbOpenMode): SmbWriteFile {
      calls.incrementAndGet()
      return object : SmbWriteFile {
        override fun write(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int = count()

        override fun flush() = count()

        override fun length(): Long = count()

        override fun renameNoReplace(newPath: String) = count()

        override fun deleteOnClose() = count()

        override fun copyChunks(resumeKey: ByteArray, chunks: List<SmbCopyChunk>): Long = count()

        override fun close() = Unit
      }
    }

    override fun mkdir(path: String) = count()

    override fun openDirectory(path: String, access: DirAccess): SmbDirectory = count()

    override fun stat(path: String): SmbFileInfo = count()

    override fun removeEmptyDirectory(path: String) = count()

    override fun deleteFile(path: String) = count()

    override fun space(): SmbSpace = count()

    /** Ammessa una sola volta (l'apertura dentro l'area per provare la rinomina verso fuori); tutto il resto conta e fallisce. */
    override fun openForChange(path: String, exclusive: Boolean): SmbChangeHandle {
      calls.incrementAndGet()
      return object : SmbChangeHandle {
        override val info: SmbFileInfo get() = count()

        override fun renameNoReplace(newPath: String) = count()

        override fun deleteOnClose() = count()

        override fun close() = Unit
      }
    }

    override fun close() = Unit
  }

  /** Un lettore e una copia finti che contano. */
  private class CountingMedia {
    val calls = AtomicInteger(0)

    private fun count(): Nothing {
      calls.incrementAndGet()
      throw AssertionError("chiamata arrivata al lettore")
    }

    val reader: NasMediaReader = object : NasMediaReader {
      override suspend fun list(
        share: String,
        dir: SharePath?,
        purpose: Purpose,
        pageSize: Int,
        onPage: suspend (List<NasEntry>) -> Unit,
      ): NasResult<ListOutcome> = count()

      override suspend fun stat(share: String, path: SharePath, purpose: Purpose): NasResult<NasEntry?> = count()

      override suspend fun open(share: String, path: SharePath, purpose: Purpose): NasResult<NasRandomFile> = count()

      override suspend fun readAll(file: NasRandomFile, sink: (ByteArray, Int) -> Unit, progress: ((Long) -> Unit)?): NasResult<Sha256> =
        count()
    }

    val copy: ServerCopy = object : ServerCopy {
      override suspend fun copy(fromShare: String, from: SharePath, toShare: String, to: SharePath): NasResult<Long> = count()
    }
  }
}
