package dev.pampa.mycloudgallery.core.nas.integration

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.INTEGRATION_WAIT_SECONDS
import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasConnState
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaLimits
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.ShareAccess
import dev.pampa.mycloudgallery.core.nas.contract.SmbDialect
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.StageOutcome
import dev.pampa.mycloudgallery.core.nas.contract.TestTarget
import dev.pampa.mycloudgallery.core.nas.contract.errorOrNull
import dev.pampa.mycloudgallery.core.nas.contract.okOrNull
import dev.pampa.mycloudgallery.core.nas.fence.ShareFence
import dev.pampa.mycloudgallery.core.nas.fs.FsOps
import dev.pampa.mycloudgallery.core.nas.fs.SmbNasHousekeeping
import dev.pampa.mycloudgallery.core.nas.fs.SmbServerSideCopy
import dev.pampa.mycloudgallery.core.nas.fs.SmbShareProbe
import dev.pampa.mycloudgallery.core.nas.fs.smb
import dev.pampa.mycloudgallery.core.nas.ops.CopyOutcome
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOps
import dev.pampa.mycloudgallery.core.nas.ops.PublishOutcome
import dev.pampa.mycloudgallery.core.nas.ops.RenameOutcome
import dev.pampa.mycloudgallery.core.nas.ops.RmdirOutcome
import dev.pampa.mycloudgallery.core.nas.ops.SmbNasFileOps
import dev.pampa.mycloudgallery.core.nas.ops.TempFinish
import dev.pampa.mycloudgallery.core.nas.runBlockingBounded
import dev.pampa.mycloudgallery.core.nas.session.MonotonicClock
import dev.pampa.mycloudgallery.core.nas.session.NasSessionConfig
import dev.pampa.mycloudgallery.core.nas.session.OpContext
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.smb.SmbStatusException
import dev.pampa.mycloudgallery.core.nas.smb.SmbjTransport
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryBreakerStore
import dev.pampa.mycloudgallery.core.nas.tools.SmbConnectionTester
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.trust.TrustFormat
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * La suite autenticata contro il NAS vero (Fase 2 §5.2, esteso dalla Fase 4 §5.4). Solo con `-PnasIntegration` E con il
 * file delle credenziali scritto dall'utente (`%USERPROFILE%\.mycloudgallery\nas-it.properties`, chiavi in
 * [ItCredentials]). Senza file si salta in modo pulito.
 *
 * **Dietro la guardia** ([TestAreaGuard], D30): configurazione validata e autotest **prima** di qualunque socket; ogni
 * richiesta SMB passa dal trasporto recintato ([TestAreaTransport]); tutto avviene sotto `mcg-it/<giro>/` delle share
 * di prova, compresi i temporanei e l'indice di prova. Alla fine ([TestAreaCleanup]) ogni cartella `mcg-it/<giro>/`
 * delle share di prova, del giro e di quelli rimasti a meta' (qualunque eta'), si cancella **con l'account che ci ha
 * accesso** (`test`, e `test2` per la sua share privata e per cio' che ha creato in `test-comune`); poi, con entrambi
 * gli account, sotto `mcg-it/` non deve restare niente (altrimenti la suite fallisce) e `mcg-it` vuota si toglie. La
 * suite presuppone di essere l'unica a girare sulle share di prova. La sessione ha anche la recinzione del telefono
 * (`ShareFence`, D33).
 *
 * Nessun tentativo con una password sbagliata senza `m9.consent=yes`.
 */
@NasIntegration
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class NasIntegrationSuiteTest {

  private val share: String get() = credentials.share
  private val area: SharePath get() = guard.root

  private fun path(vararg names: String) = SharePath.of(*(area.components + names).toTypedArray())

  @Test
  fun t00_autotestDellaGuardiaPrimaDiQualunqueSocket() {
    val result = TestAreaSelfTest.run(guard)
    assertWithMessage(result.failures.joinToString()).that(result.failures).isEmpty()
    report += "guardia: ${result.cases} casi vietati rifiutati con zero chiamate (autotest), share ammesse ${guard.shares.size}"
  }

  @Test
  fun t01_sessioneCifrataConLaFirmaFinaleVerificataEGuidStabile() = runBlockingBounded("t01_sessioneCifrataConLaFirmaFinaleVerificataEGuidStabile", INTEGRATION_WAIT_SECONDS) {
    manager.withShare(share, Purpose.INTERACTIVE) { }.also { assertThat(it).isInstanceOf(NasResult.Ok::class.java) }
    val ready = manager.state.value as NasConnState.Ready
    // Arrivare qui vuol dire che la risposta finale di SESSION_SETUP era firmata e la firma tornava (SmbjTransport).
    report += "sessione: ${ready.info.dialect}, cifrata=${ready.info.encrypted}, cifrario=${ready.info.cipher}, " +
      "firma finale di SESSION_SETUP verificata=si', scarto ora NAS=${ready.info.nasTimeOffsetMs} ms"
    assertThat(ready.info.dialect).isEqualTo(SmbDialect.SMB_3_1_1)
    assertThat(ready.info.encrypted).isTrue()
    val guid = ready.info.serverGuid
    manager.invalidate(InvalidateReason.USER_REQUEST)
    manager.withShare(share, Purpose.INTERACTIVE) { }
    assertThat((manager.state.value as NasConnState.Ready).info.serverGuid).isEqualTo(guid)
    assertThat(manager.nasClock.nowMillisOrNull()).isNotNull()
  }

  @Test
  fun t02_pubblicazioneEsclusivaRealeNellAreaDiProva() {
    // L'indice di prova vive sotto mcg-it/<giro>/: il formato vede .mycloudgallery/ come sempre (§5.4, punto 3).
    val fs = manager.rootedFileSystem(share, area)
    val registry = SharePath.of(TrustFormat.ROOT_DIR, "registro", "00000000000000000001")
    val bytes = ByteArray(200_000) { (it * 31).toByte() }
    assertThat(fs.publishExclusive(registry, bytes)).isEqualTo(PublishResult.CREATED)
    assertThat(fs.publishExclusive(registry, byteArrayOf(1))).isEqualTo(PublishResult.ALREADY_EXISTS)
    val shouted = SharePath.of(TrustFormat.ROOT_DIR.uppercase(Locale.ROOT), "REGISTRO", "00000000000000000001")
    assertThat(fs.publishExclusive(shouted, byteArrayOf(2))).isEqualTo(PublishResult.ALREADY_EXISTS)
    assertThat((fs.read(registry, bytes.size) as NasRead.Data).bytes).isEqualTo(bytes)
    assertThat(fs.read(registry, bytes.size - 1)).isEqualTo(NasRead.TooLarge)
    assertThat(fs.read(SharePath.of(TrustFormat.ROOT_DIR, "registro", "manca"), 10)).isEqualTo(NasRead.NotFound)
    repeat(5) { fs.publishExclusive(SharePath.of(TrustFormat.ROOT_DIR, "elenco", "f$it"), byteArrayOf(it.toByte())) }
    val listing = fs.list(SharePath.of(TrustFormat.ROOT_DIR, "elenco"), 3)
    assertThat(listing.names).hasSize(3)
    assertThat(listing.truncated).isTrue()
    assertThat(fs.list(SharePath.of(TrustFormat.ROOT_DIR, "tmp"), 1000).names.filter { it.startsWith(DEVICE_ID) }).isEmpty()
    report += "publishExclusive reale sotto mcg-it/<giro>/.mycloudgallery: ok (collisione, maiuscole, tetto, elenco troncato, nessun temporaneo)"
  }

  @Test
  fun t03_provaDellaShareEPuliziaDeiSoliPropriTemporanei() = runBlockingBounded("t03_provaDellaShareEPuliziaDeiSoliPropriTemporanei", INTEGRATION_WAIT_SECONDS) {
    val access = SmbShareProbe(manager, Purpose.INTERACTIVE, area).probe(share)
    assertThat(access).isEqualTo(NasResult.Ok(ShareAccess.READ_WRITE))
    val tmp = SharePath.of(*(area.components + listOf(TrustFormat.ROOT_DIR, "tmp")).toTypedArray())
    val mine = "$DEVICE_ID-" + "ab".repeat(16)
    val other = "zzzzzzzzzzzzzzzzzzzzzzzzzz-" + "ab".repeat(16)
    op(share) { ctx ->
      FsOps.mkdirs(ctx, tmp)
      for (name in listOf(mine, other)) ctx.share.createExclusive(tmp.child(name).smb()).close()
    }
    // L'eta' si misura con l'ora del NAS: due secondi bastano a superare l'errore dell'ancora.
    Thread.sleep(2_000)
    val purged = SmbNasHousekeeping(manager, { listOf(share) }, area).purgeOwnStaleTemps(DEVICE_ID, 0.minutes)
    assertThat(purged).isEqualTo(NasResult.Ok(1))
    val left = names(share, tmp)
    assertThat(left).doesNotContain(mine)
    assertThat(left).contains(other)
    report += "prova della share (cartella del giro): $access; pulizia dei temporanei: solo i propri"
  }

  @Test
  fun t04_elencoAPagineConFileIdVeriEStabiliAllaRinomina() = runBlockingBounded("t04_elencoAPagineConFileIdVeriEStabiliAllaRinomina", INTEGRATION_WAIT_SECONDS) {
    val dir = path("elenco")
    val files = 1_500
    val createStarted = System.nanoTime()
    op(share) { ctx -> FsOps.mkdirs(ctx, dir) }
    // A lotti da 100: ogni lotto e' un'operazione corta (qualche centinaio di ms).
    for (batch in 0 until files / 100) {
      op(share) { ctx ->
        for (i in batch * 100 until (batch + 1) * 100) ctx.share.createExclusive(dir.child("IMG_%05d.jpg".format(Locale.ROOT, i)).smb()).close()
      }
    }
    val createMs = (System.nanoTime() - createStarted) / 1_000_000.0
    val reader = media.mediaReader
    val pages = ArrayList<Int>()
    val entries = ArrayList<NasEntry>()
    val listStarted = System.nanoTime()
    val outcome = reader.list(share, dir, Purpose.BACKGROUND, NasMediaLimits.DEFAULT_PAGE_SIZE) { page ->
      pages += page.size
      entries += page
    }.okOrNull()!!
    val listMs = (System.nanoTime() - listStarted) / 1_000_000.0
    assertThat(outcome.count).isEqualTo(files)
    assertThat(pages).containsExactly(1000, 500).inOrder()
    val ids = entries.mapNotNull { it.fileId }
    assertWithMessage("fileId riportati dal NAS").that(ids).hasSize(files)
    assertThat(ids.toSet()).hasSize(files)
    val sample = entries.first { it.name == "IMG_00042.jpg" }
    val stat = reader.stat(share, dir.child("img_00042.JPG"), Purpose.BACKGROUND).okOrNull()!!
    assertThat(stat.fileId).isEqualTo(sample.fileId)
    assertThat(stat.modifiedAt).isEqualTo(sample.modifiedAt)
    assertThat(stat.size).isEqualTo(0)

    // Rinomina sul NAS (come publishExclusive: temporaneo e rinomina senza sostituire): stesso fileId (M8, E-N1).
    val (before, after) = op(share) { ctx ->
      ctx.share.createExclusive(dir.child("rinomina.tmp").smb()).use { file ->
        val id = ctx.share.stat(dir.child("rinomina.tmp").smb()).fileId
        file.renameNoReplace(path("rinominato.jpg").smb())
        id to ctx.share.stat(path("rinominato.jpg").smb()).fileId
      }
    }
    assertThat(after).isEqualTo(before)
    report += String.format(
      Locale.ROOT,
      "elenco: %d file creati in %.0f ms; elenco a pagine %s in %.0f ms (%.0f voci/s); fileId presenti, unici, uguali a stat; " +
        "fileId stabile alla rinomina: %s",
      files, createMs, pages, listMs, files / (listMs / 1000.0), after == before,
    )
  }

  @Test
  fun t05_lettureAIntervalliEReadAllAQuattroFlussi() = runBlockingBounded("t05_lettureAIntervalliEReadAllAQuattroFlussi", INTEGRATION_WAIT_SECONDS) {
    val (written, writeMs) = bigFile()
    val reader = media.mediaReader
    val file = reader.open(share, BIG, Purpose.BACKGROUND).okOrNull()!!
    assertThat(file.size).isEqualTo(BIG_BYTES)
    assertThat(file.stamp.fileId).isNotNull()
    val head = file.readRange(0L until 64 * 1024)
    val tail = file.readRange((BIG_BYTES - 64 * 1024) until BIG_BYTES)
    assertThat(head).isEqualTo(chunk(0).copyOf(64 * 1024))
    assertThat(tail).isEqualTo(chunk(BIG_CHUNKS - 1).copyOfRange(MIB - 64 * 1024, MIB))
    assertThat(file.readRange((BIG_BYTES - 10) until BIG_BYTES + 100)).hasLength(10)
    assertThat(file.readRange(BIG_BYTES until BIG_BYTES + 10)).isEmpty()

    // Un flusso solo, a blocchi da 1 MiB, per confronto (16 MiB).
    val singleStarted = System.nanoTime()
    for (i in 0 until 16) assertThat(file.readRange((i.toLong() * MIB) until ((i + 1).toLong() * MIB)).size).isEqualTo(MIB)
    val singleMs = (System.nanoTime() - singleStarted) / 1_000_000.0

    val readStarted = System.nanoTime()
    val hash = reader.readAll(file, { _, _ -> }).okOrNull()
    val readMs = (System.nanoTime() - readStarted) / 1_000_000.0
    file.close()
    assertThat(hash).isEqualTo(written)
    report += String.format(
      Locale.ROOT,
      "letture: file da %d MiB scritto in %.0f ms (%.1f MB/s); intervalli testa/coda/oltre la fine giusti; " +
        "un flusso %.1f MB/s; readAll a %d flussi %.0f ms (%.1f MB/s), SHA-256 uguale",
      BIG_CHUNKS, writeMs, mbps(BIG_BYTES, writeMs), mbps(16L * MIB, singleMs), NasMediaLimits.READ_ALL_STREAMS, readMs,
      mbps(BIG_BYTES, readMs),
    )
  }

  @Test
  fun t06_ilSorveglianteAProgressoNonUccideUnaLetturaLungaSulNasVero() = runBlockingBounded("t06_ilSorveglianteAProgressoNonUccideUnaLetturaLungaSulNasVero", INTEGRATION_WAIT_SECONDS) {
    val (written, _) = bigFile()
    // Una sessione a parte con la scadenza interattiva di 1 s: il totale e' molto di piu', ogni blocco ci sta (D3).
    val short = newManager(NasSessionConfig(interactiveOpTimeout = 1.seconds))
    try {
      val reader = short.mediaReader()
      val file = reader.open(share, BIG, Purpose.INTERACTIVE).okOrNull()!!
      val started = System.nanoTime()
      val hash = reader.readAll(file, { _, _ -> }).okOrNull()
      val ms = (System.nanoTime() - started) / 1_000_000.0
      file.close()
      assertThat(hash).isEqualTo(written)
      report += String.format(Locale.ROOT, "sorvegliante: readAll interattivo da %d MiB in %.0f ms con scadenza per blocco di 1 s: ok", BIG_CHUNKS, ms)
    } finally {
      short.invalidate(InvalidateReason.USER_REQUEST)
    }
  }

  @Test
  fun t07_copiaLatoServerNellaShareETraShare() = runBlockingBounded("t07_copiaLatoServerNellaShareETraShare", INTEGRATION_WAIT_SECONDS) {
    val (written, _) = bigFile()
    val copy = media.serverCopy
    val started = System.nanoTime()
    assertThat(copy.copy(share, BIG, share, path("copia", "grande.bin"))).isEqualTo(NasResult.Ok(BIG_BYTES))
    val sameMs = (System.nanoTime() - started) / 1_000_000.0
    assertThat(copy.copy(share, BIG, share, path("copia", "GRANDE.bin")).errorOrNull()).isEqualTo(NasError.Exists)
    val copied = media.mediaReader.open(share, path("copia", "grande.bin"), Purpose.BACKGROUND).okOrNull()!!
    assertThat(media.mediaReader.readAll(copied, { _, _ -> }).okOrNull()).isEqualTo(written)
    val original = media.mediaReader.stat(share, BIG, Purpose.BACKGROUND).okOrNull()!!
    assertThat(copied.stamp.fileId).isNotEqualTo(original.fileId)
    copied.close()
    var line = String.format(Locale.ROOT, "copia lato server nella share: %d MiB in %.0f ms (%.0f MB/s), SHA-256 uguale, fileId nuovo", BIG_CHUNKS, sameMs, mbps(BIG_BYTES, sameMs))
    val second = credentials.share2
    if (second != null) {
      val crossStarted = System.nanoTime()
      assertThat(copy.copy(share, BIG, second, path("grande.bin"))).isEqualTo(NasResult.Ok(BIG_BYTES))
      val crossMs = (System.nanoTime() - crossStarted) / 1_000_000.0
      assertThat(media.mediaReader.stat(second, path("grande.bin"), Purpose.BACKGROUND).okOrNull()!!.size).isEqualTo(BIG_BYTES)
      line += String.format(Locale.ROOT, "; tra share: %.0f ms (%.0f MB/s)", crossMs, mbps(BIG_BYTES, crossMs))
    } else {
      line += "; tra share: saltata (share2 assente)"
    }
    report += line
  }

  @Test
  fun t08_laRecinzioneDelTelefonoRifiutaLeSharePubblicheSenzaRichieste() = runBlockingBounded("t08_laRecinzioneDelTelefonoRifiutaLeSharePubblicheSenzaRichieste", INTEGRATION_WAIT_SECONDS) {
    val before = transport.delegateCalls.get()
    for (public in listOf("Public", "NAS", "PUBLIC")) {
      assertThat(manager.withShare(public, Purpose.INTERACTIVE) { }.errorOrNull()).isEqualTo(NasError.PermissionDenied)
      assertThat(manager.mediaReader().list(public, null, Purpose.BACKGROUND) {}.errorOrNull()).isEqualTo(NasError.PermissionDenied)
    }
    assertThat(transport.delegateCalls.get()).isEqualTo(before)
    report += "recinzione D33 sul NAS vero: Public e NAS rifiutate, zero richieste"
  }

  @Test
  fun t09_ilSecondoAccountNonVedeLAreaPrivataDelPrimo() = runBlockingBounded("t09_ilSecondoAccountNonVedeLAreaPrivataDelPrimo", INTEGRATION_WAIT_SECONDS) {
    val user2 = credentials.user2
    val password2 = credentials.password2
    assumeTrue("user2/password2 assenti: prova fra account saltata", user2 != null && password2 != null)
    val other = newManager(NasSessionConfig(), user = checkNotNull(user2), password = checkNotNull(password2))
    try {
      val privateShare = credentials.privateShare2 ?: credentials.share2
      assumeTrue("privateShare2/share2 assenti", privateShare != null)
      assertThat(other.withShare(checkNotNull(privateShare), Purpose.INTERACTIVE) { }).isInstanceOf(NasResult.Ok::class.java)
      val foreign = other.withShare(share, Purpose.INTERACTIVE) { }
      report += "secondo account: la sua area ok; l'area privata del primo: ${foreign.errorOrNull() ?: "ACCESSIBILE"}"
      assertThat(foreign.errorOrNull()).isEqualTo(NasError.PermissionDenied)
    } finally {
      other.invalidate(InvalidateReason.USER_REQUEST)
    }
  }

  /** M9: UN solo tentativo con la password sbagliata, solo con il consenso scritto nel file (`m9.consent=yes`). */
  @Test
  fun t10_passwordSbagliataUnSoloTentativo() = runBlockingBounded("t10_passwordSbagliataUnSoloTentativo", INTEGRATION_WAIT_SECONDS) {
    assumeTrue("M9 senza consenso esplicito: saltato", credentials.wrongPasswordConsent)
    val isolated = AuthBreakerRegistry(InMemoryBreakerStore(), scope)
    val tester = SmbConnectionTester(gate, isolated, transport, clock)
    val target = TestTarget(NasAddress(credentials.host), credentials.user, credentials.share, account.pinnedGuid)
    val wrong = "sbagliata-${guard.runId}".toCharArray()
    val first = tester.run(target, NasCredentials(credentials.user, wrong))
    val second = tester.run(target, NasCredentials(credentials.user, wrong))
    val firstAuth = first.stages.first { it.stage == Stage.AUTHENTICATE }
    report += "M9: codice ${firstAuth.error}; secondo giro ${second.stages.first { it.stage == Stage.AUTHENTICATE }.outcome}"
    assertThat(firstAuth.error).isEqualTo(NasError.Auth(AuthBlockReason.AUTH_FAILED))
    assertThat(second.stages.first { it.stage == Stage.AUTHENTICATE }.outcome).isEqualTo(StageOutcome.SKIPPED)
  }

  // --- Fase 4B (P12): le primitive di scrittura di ops/ sul NAS vero, solo sotto mcg-it/<giro>/ (radice delle primitive) -------------

  /** Le primitive con la radice dell'area di prova: `x/y` diventa `mcg-it/<giro>/x/y`, cestino e temporanei compresi. */
  private val ops: NasFileOps get() = SmbNasFileOps(manager, OPS_DEVICE, area)

  private fun rel(path: String) = SharePath.parse(path)

  private fun full(path: SharePath) = SharePath.of(*(area.components + path.components).toTypedArray())

  /** Scrive un file nell'area di prova (percorso relativo alla radice del giro) e ne restituisce il timbro letto dal NAS. */
  private suspend fun writeFile(shareName: String, path: SharePath, bytes: ByteArray): NasStamp {
    op(shareName) { ctx ->
      FsOps.mkdirs(ctx, full(path).parent())
      ctx.share.createExclusive(full(path).smb()).use { file ->
        var offset = 0
        while (offset < bytes.size) offset += file.write(bytes, offset.toLong(), offset, minOf(MIB, bytes.size - offset))
        file.flush()
      }
    }
    return media.mediaReader.stat(shareName, full(path), Purpose.BACKGROUND).okOrNull()!!.stamp
  }

  private fun ms(started: Long) = (System.nanoTime() - started) / 1_000_000.0

  @Test
  fun t11_primitiveDiRinominaSulNasVero() = runBlockingBounded("t11_primitiveDiRinominaSulNasVero", INTEGRATION_WAIT_SECONDS) {
    val ops = ops
    assertThat(ops.mkdirs(share, rel("ops/A/B"), Purpose.BACKGROUND)).isEqualTo(NasResult.Ok(Unit))
    assertThat(ops.mkdirs(share, rel("ops/a/b"), Purpose.BACKGROUND)).isEqualTo(NasResult.Ok(Unit))
    val stamp = writeFile(share, rel("ops/A/x.jpg"), ByteArray(4096) { it.toByte() })
    assertThat(ops.mkdirs(share, rel("ops/A/x.jpg"), Purpose.BACKGROUND).errorOrNull()).isEqualTo(NasError.Exists)

    // Rinomina con il timbro controllato sullo stesso handle: stesso fileId (E-N1), mai sostituire.
    var started = System.nanoTime()
    val renamed = ops.rename(share, rel("ops/A/x.jpg"), rel("ops/A/B/y.jpg"), stamp, Purpose.BACKGROUND).okOrNull()
    val renameMs = ms(started)
    assertThat(renamed).isInstanceOf(RenameOutcome.Renamed::class.java)
    assertThat(media.mediaReader.stat(share, full(rel("ops/A/B/y.jpg")), Purpose.BACKGROUND).okOrNull()!!.fileId).isEqualTo(stamp.fileId)
    writeFile(share, rel("ops/A/B/z.jpg"), byteArrayOf(7))
    assertThat(ops.rename(share, rel("ops/A/B/y.jpg"), rel("ops/A/B/Z.JPG"), null, Purpose.BACKGROUND).okOrNull()).isEqualTo(RenameOutcome.Collision)
    assertThat(media.mediaReader.stat(share, full(rel("ops/A/B/z.jpg")), Purpose.BACKGROUND).okOrNull()!!.size).isEqualTo(1)
    // Il file non e' piu' quello confermato (E-D13, E-N11): niente.
    val wrong = NasStamp(stamp.size + 1, stamp.modifiedAtMillis, stamp.fileId)
    assertThat(ops.rename(share, rel("ops/A/B/y.jpg"), rel("ops/A/B/w.jpg"), wrong, Purpose.BACKGROUND).okOrNull()).isInstanceOf(RenameOutcome.SourceChanged::class.java)
    assertThat(ops.rename(share, rel("ops/A/B/manca.jpg"), rel("ops/A/B/w.jpg"), null, Purpose.BACKGROUND).okOrNull()).isEqualTo(RenameOutcome.SourceMissing)
    assertThat(ops.rename(share, rel("ops/A/B/y.jpg"), rel("ops/Manca/y.jpg"), null, Purpose.BACKGROUND).errorOrNull()).isEqualTo(NasError.NotFound)

    // E-N13: un file aperto da un altro (qui un nostro handle senza condivisione) = Locked, niente cambia.
    val holder = op(share) { ctx -> ctx.share.createExclusive(full(rel("ops/A/aperto.jpg")).smb()) }
    val locked = try {
      ops.rename(share, rel("ops/A/aperto.jpg"), rel("ops/A/chiuso.jpg"), null, Purpose.BACKGROUND).okOrNull()
    } finally {
      holder.close()
    }
    assertThat(locked).isEqualTo(RenameOutcome.Locked)

    // E-N7: la rinomina solo di maiuscole il contratto la rifiuta senza richieste; com'e' Samba con un colpo solo?
    val before = transport.delegateCalls.get()
    assertThat(ops.rename(share, rel("ops/A/B/y.jpg"), rel("ops/A/B/Y.JPG"), null, Purpose.BACKGROUND).okOrNull()).isEqualTo(RenameOutcome.Collision)
    assertThat(transport.delegateCalls.get()).isEqualTo(before)
    val sambaCaseOnly = op(share) { ctx ->
      ctx.share.openForChange(full(rel("ops/A/B/y.jpg")).smb(), exclusive = false).use { handle ->
        try {
          handle.renameNoReplace(full(rel("ops/A/B/Y.jpg")).smb())
          "accettata (" + (ctx.share.stat(full(rel("ops/A/B/y.jpg")).smb()).name ?: "?") + ")"
        } catch (e: SmbStatusException) {
          "rifiutata (0x%08X)".format(Locale.ROOT, e.status)
        }
      }
    }
    started = System.nanoTime()
    val dir = ops.rename(share, rel("ops/A/B"), rel("ops/C"), null, Purpose.BACKGROUND).okOrNull()
    val dirMs = ms(started)
    assertThat(dir).isInstanceOf(RenameOutcome.Renamed::class.java)
    report += String.format(
      Locale.ROOT,
      "4B rinomina: file %.0f ms (fileId uguale), cartella %.0f ms; collisione, timbro cambiato, sorgente assente, cartella mancante, " +
        "file aperto=Locked: ok; solo maiuscole in un colpo su Samba: %s",
      renameMs, dirMs, sambaCaseOnly,
    )
  }

  @Test
  fun t12_spostamentoTraShareConCopiaLatoServerVerificaECestino() = runBlockingBounded("t12_spostamentoTraShareConCopiaLatoServerVerificaECestino", INTEGRATION_WAIT_SECONDS) {
    val second = credentials.share2
    assumeTrue("share2 assente: spostamento tra share saltato", second != null)
    val (written, _) = bigFile()
    val source = rel("letture/grande.bin")
    val stamp = media.mediaReader.stat(share, full(source), Purpose.BACKGROUND).okOrNull()!!.stamp
    val temp = NasLayout.ROOT.child("tmp").child("$OPS_DEVICE-op-it-0")
    val ops = ops
    val total = System.nanoTime()
    // 1. Copia lato server sul temporaneo proprio della destinazione.
    var started = System.nanoTime()
    val copied = ops.copyToTemp(share, source, checkNotNull(second), temp, stamp, Purpose.BACKGROUND).okOrNull()
    val copyMs = ms(started)
    assertThat(copied).isInstanceOf(CopyOutcome.Copied::class.java)
    assertThat(ops.copyToTemp(share, source, second, temp, stamp, Purpose.BACKGROUND).okOrNull()).isEqualTo(CopyOutcome.TempExists)
    // 2. Verifica completa: SHA-256 della copia uguale a quello della sorgente.
    started = System.nanoTime()
    val tempFile = media.mediaReader.open(second, full(temp), Purpose.BACKGROUND).okOrNull()!!
    val tempHash = media.mediaReader.readAll(tempFile, { _, _ -> }).okOrNull()
    tempFile.close()
    val verifyMs = ms(started)
    assertThat(tempHash).isEqualTo(written)
    // 3. Pubblicazione senza sostituire, creando le cartelle.
    started = System.nanoTime()
    assertThat(ops.publishTemp(second, temp, rel("spostati/2026/grande.bin"), Purpose.BACKGROUND).okOrNull()).isInstanceOf(PublishOutcome.Published::class.java)
    val publishMs = ms(started)
    assertThat(ops.publishTemp(second, temp, rel("spostati/2026/grande.bin"), Purpose.BACKGROUND).okOrNull()).isEqualTo(PublishOutcome.TempMissing)
    // 4. La sorgente nel cestino della sua share (Q3), solo se e' ancora quella verificata.
    val trashId = TrustIds.random()
    started = System.nanoTime()
    assertThat(ops.trashMove(share, source, trashId, stamp, Purpose.BACKGROUND).okOrNull()).isInstanceOf(RenameOutcome.Renamed::class.java)
    val trashMs = ms(started)
    val totalMs = ms(total)
    assertThat(media.mediaReader.stat(share, full(source), Purpose.BACKGROUND).okOrNull()).isNull()
    val trashed = media.mediaReader.stat(share, full(NasLayout.trashItemFile(OPS_DEVICE, trashId)), Purpose.BACKGROUND).okOrNull()!!
    assertThat(trashed.size).isEqualTo(BIG_BYTES)
    assertThat(trashed.fileId).isEqualTo(stamp.fileId)
    assertThat(media.mediaReader.stat(second, full(rel("spostati/2026/grande.bin")), Purpose.BACKGROUND).okOrNull()!!.size).isEqualTo(BIG_BYTES)
    assertThat(names(second, full(NasLayout.ROOT.child("tmp")))).doesNotContain(temp.name)
    report += String.format(
      Locale.ROOT,
      "4B spostamento tra share (%d MiB): copia lato server %.0f ms (%.0f MB/s), verifica SHA-256 %.0f ms (%.0f MB/s), pubblicazione %.0f ms, " +
        "sorgente nel cestino %.0f ms; totale %.0f ms; fileId della sorgente conservato nel cestino",
      BIG_CHUNKS, copyMs, mbps(BIG_BYTES, copyMs), verifyMs, mbps(BIG_BYTES, verifyMs), publishMs, trashMs, totalMs,
    )
  }

  @Test
  fun t13_cestinoEdEliminazioneConUnSoloHandle() = runBlockingBounded("t13_cestinoEdEliminazioneConUnSoloHandle", INTEGRATION_WAIT_SECONDS) {
    val ops = ops
    val bytes = ByteArray(MIB) { (it * 13).toByte() }
    val stamp = writeFile(share, rel("ops/del/a.bin"), bytes)
    writeFile(share, rel("ops/del/Gita/1.jpg"), byteArrayOf(1))
    writeFile(share, rel("ops/del/Gita/Mare/2.jpg"), byteArrayOf(2, 2))
    val fileId = TrustIds.random()
    val folderId = TrustIds.random()
    var started = System.nanoTime()
    assertThat(ops.trashMove(share, rel("ops/del/a.bin"), fileId, stamp, Purpose.BACKGROUND).okOrNull()).isInstanceOf(RenameOutcome.Renamed::class.java)
    val trashFileMs = ms(started)
    started = System.nanoTime()
    assertThat(ops.trashMove(share, rel("ops/del/Gita"), folderId, null, Purpose.BACKGROUND).okOrNull()).isInstanceOf(RenameOutcome.Renamed::class.java)
    val trashFolderMs = ms(started)
    val inTrash = NasLayout.trashItemFile(OPS_DEVICE, fileId)
    // La dimensione si controlla sullo stesso handle della cancellazione: una dimensione diversa non elimina niente.
    assertThat(ops.deleteIfUnchanged(share, inTrash, MIB + 1L, Purpose.BACKGROUND).okOrNull()).isEqualTo(DeleteOutcome.SizeChanged(MIB.toLong()))
    assertThat(media.mediaReader.stat(share, full(inTrash), Purpose.BACKGROUND).okOrNull()).isNotNull()
    started = System.nanoTime()
    assertThat(ops.deleteIfUnchanged(share, inTrash, MIB.toLong(), Purpose.BACKGROUND).okOrNull()).isEqualTo(DeleteOutcome.Deleted)
    val deleteMs = ms(started)
    assertThat(ops.deleteIfUnchanged(share, inTrash, MIB.toLong(), Purpose.BACKGROUND).okOrNull()).isEqualTo(DeleteOutcome.Missing)
    // La cartella cestinata (E-D10): i file con la dimensione vista, poi le cartelle vuote; mai una cartella piena.
    val folder = NasLayout.trashItemFile(OPS_DEVICE, folderId)
    assertThat(ops.rmdirIfEmpty(share, folder, Purpose.BACKGROUND).okOrNull()).isEqualTo(RmdirOutcome.NotEmpty)
    assertThat(ops.deleteIfUnchanged(share, folder.child("1.jpg"), 1, Purpose.BACKGROUND).okOrNull()).isEqualTo(DeleteOutcome.Deleted)
    assertThat(ops.deleteIfUnchanged(share, folder.child("Mare").child("2.jpg"), 2, Purpose.BACKGROUND).okOrNull()).isEqualTo(DeleteOutcome.Deleted)
    assertThat(ops.rmdirIfEmpty(share, folder.child("Mare"), Purpose.BACKGROUND).okOrNull()).isEqualTo(RmdirOutcome.Removed)
    assertThat(ops.rmdirIfEmpty(share, folder, Purpose.BACKGROUND).okOrNull()).isEqualTo(RmdirOutcome.Removed)
    // Un media non si elimina mai: errore di programmazione prima di qualunque richiesta.
    val calls = transport.delegateCalls.get()
    org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
      runBlockingBounded("eliminazione vietata") { ops.deleteIfUnchanged(share, rel("ops/del/x.jpg"), 1, Purpose.BACKGROUND) }
    }
    assertThat(transport.delegateCalls.get()).isEqualTo(calls)
    report += String.format(
      Locale.ROOT,
      "4B cestino: file da 1 MiB %.0f ms, cartella intera (una rinomina) %.0f ms; eliminazione con un handle %.0f ms; dimensione diversa = " +
        "niente eliminato; cartella cestinata svuotata dal fondo",
      trashFileMs, trashFolderMs, deleteMs,
    )
  }

  @Test
  fun t14_temporaneoScrittoDalTelefonoESpazio() = runBlockingBounded("t14_temporaneoScrittoDalTelefonoESpazio", INTEGRATION_WAIT_SECONDS) {
    val ops = ops
    val temp = NasLayout.ROOT.child("tmp").child("$OPS_DEVICE-op-it-1")
    val writer = ops.openTemp(share, temp, Purpose.BACKGROUND).okOrNull()!!
    val data = ByteArray(3 * MIB + 17) { (it * 7).toByte() }
    var offset = 0
    val started = System.nanoTime()
    while (offset < data.size) {
      val n = minOf(256 * 1024, data.size - offset)
      assertThat(writer.write(offset.toLong(), data, offset, n)).isEqualTo(NasResult.Ok(Unit))
      offset += n
    }
    assertThat(writer.finish(data.size + 1L).okOrNull()).isInstanceOf(TempFinish.SizeMismatch::class.java)
    assertThat(writer.finish(data.size.toLong()).okOrNull()).isInstanceOf(TempFinish.Complete::class.java)
    val writeMs = ms(started)
    writer.close()
    assertThat(ops.openTemp(share, temp, Purpose.BACKGROUND).errorOrNull()).isEqualTo(NasError.Exists)
    writeFile(share, rel("ops/tmp/occupato.bin"), byteArrayOf(1))
    assertThat(ops.publishTemp(share, temp, rel("ops/tmp/occupato.bin"), Purpose.BACKGROUND).okOrNull()).isEqualTo(PublishOutcome.Collision)
    assertThat(ops.deleteOwnTemp(share, temp, Purpose.BACKGROUND).okOrNull()).isEqualTo(DeleteOutcome.Deleted)
    assertThat(ops.deleteOwnTemp(share, temp, Purpose.BACKGROUND).okOrNull()).isEqualTo(DeleteOutcome.Missing)
    val space = ops.space(share, Purpose.INTERACTIVE).okOrNull()!!
    assertThat(space.totalBytes).isAtLeast(space.freeBytes)
    report += String.format(
      Locale.ROOT,
      "4B temporaneo dal telefono (ripiego E-D12): %d MiB scritti in %.0f ms (%.1f MB/s), lunghezza controllata sullo stesso handle; " +
        "spazio: totale %.0f GB, libero %.0f GB, quota=%s",
      data.size / MIB, writeMs, mbps(data.size.toLong(), writeMs), space.totalBytes / 1e9, space.freeBytes / 1e9, space.quotaLimited,
    )
  }

  private suspend fun <T> op(shareName: String, block: (OpContext) -> T): T =
    when (val result = manager.runOp(shareName, Purpose.BACKGROUND, idempotent = false, touchesDisk = true, op = block)) {
      is NasResult.Ok -> result.value
      is NasResult.Err -> throw AssertionError("operazione nell'area di prova fallita: ${result.error}")
    }

  private suspend fun names(shareName: String, dir: SharePath): List<String> {
    val names = ArrayList<String>()
    media.mediaReader.list(shareName, dir, Purpose.BACKGROUND) { page -> page.mapTo(names) { it.name } }
    return names
  }

  companion object {
    private const val MIB = 1024 * 1024
    private const val BIG_CHUNKS = 64
    private const val BIG_BYTES = BIG_CHUNKS.toLong() * MIB
    private const val SEED = 20261014L

    /** L'interruttore condiviso da tutti i test della suite (vive quanto la JVM dei test). */
    private val SHARED_STORE = InMemoryBreakerStore()

    private lateinit var credentials: ItCredentials
    private lateinit var guard: TestAreaGuard
    private lateinit var transport: TestAreaTransport
    private lateinit var scope: CoroutineScope
    private lateinit var gate: FakeNasRouteGate
    private lateinit var account: NasAccount
    private lateinit var manager: SmbNasSessionManager
    private lateinit var media: TestAreaMedia
    private val clock = MonotonicClock { System.nanoTime() / 1_000_000 }
    private val report = ArrayList<String>()
    private var verified = false
    private var bigWritten: Pair<Sha256, Double>? = null

    private val RUN_ID: String = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).format(ZonedDateTime.now(ZoneOffset.UTC)) +
      "-" + ByteArray(4).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(Locale.ROOT, it) }

    /** L'id di "dispositivo" della suite: 26 caratteri `[0-9a-z]` (da' il nome ai temporanei). */
    private val DEVICE_ID: String = "itest" + RUN_ID.filter { it.isLetterOrDigit() }.lowercase(Locale.ROOT).take(21).padEnd(21, '0')

    /** Il dispositivo delle primitive di scrittura (4B): un id valido (`TrustIds`), proprietario di temporanei e cestino del giro. */
    private val OPS_DEVICE: String = TrustIds.random()

    private val BIG: SharePath get() = SharePath.of(TestAreaGuard.ROOT, RUN_ID, "letture", "grande.bin")

    private fun chunk(index: Int): ByteArray = Random(SEED + index).nextBytes(MIB)

    private fun mbps(bytes: Long, ms: Double): Double = bytes / 1_000_000.0 / (ms / 1000.0)

    @BeforeClass
    @JvmStatic
    fun setUpSuite() {
      credentials = NasIntegrationConfig.requireCredentials()
      // (1) La configurazione, prima di qualunque socket: un errore nel file fa fallire, non allarga il recinto.
      guard = TestAreaGuard.of(credentials, RUN_ID)
      // (4) L'autotest, ancora senza socket.
      val self = TestAreaSelfTest.run(guard)
      check(self.failures.isEmpty()) { "autotest della guardia fallito: ${self.failures}" }
      transport = TestAreaTransport(SmbjTransport(), guard)
      scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      gate = FakeNasRouteGate().apply { allow(Via.HOME_LAN, TimedDirectBinding()) }
      val breakers = AuthBreakerRegistry(SHARED_STORE, scope)
      val guid = credentials.guid ?: runBlockingBounded("il primo contatto col NAS", INTEGRATION_WAIT_SECONDS) {
        // Primo contatto come l'onboarding: GUID dalla NEGOTIATE, senza autenticare (passa comunque dal recinto).
        SmbConnectionTester(gate, breakers, transport, clock)
          .run(TestTarget(NasAddress(credentials.host), null, null, null), null, setOf(Stage.ROUTE, Stage.TCP, Stage.NEGOTIATE))
          .negotiation?.serverGuid
      }
      account = NasAccount(NasAddress(credentials.host), credentials.user, "it", guid)
      manager = newManager(NasSessionConfig(), breakers = breakers)
      // La copia lato server con i temporanei sotto mcg-it/<giro>/ (anche nella share di destinazione).
      media = TestAreaMedia(manager.mediaReader(), SmbServerSideCopy(manager, Purpose.BACKGROUND, SecureRandom(), guard.root), guard)
      // (7) La password si verifica UNA volta, con la sessione e il suo interruttore condiviso.
      val check = runBlockingBounded("la verifica della sessione", INTEGRATION_WAIT_SECONDS) { manager.withShare(credentials.share, Purpose.INTERACTIVE) { } }
      if (check is NasResult.Err) throw AssertionError("la sessione di prova non parte: ${check.error} (nessun altro tentativo in questa suite)")
      verified = true
      // (5) I giri rimasti a meta' (di qualunque eta') li toglie la pulizia finale, con l'account giusto per ogni share.
    }

    @AfterClass
    @JvmStatic
    fun tearDownSuite() {
      if (!::manager.isInitialized) return
      var second: SmbNasSessionManager? = null
      try {
        if (verified) {
          runBlockingBounded("la pulizia finale", INTEGRATION_WAIT_SECONDS) {
            // Ogni cartella mcg-it/<giro>/ si cancella con l'account che ha accesso alla share: `test` per la sua,
            // `test2` per la sua privata, e a turno entrambi in `test-comune` (le voci negate all'uno le toglie l'altro).
            val accounts = arrayListOf(TestAreaCleanup.Account(credentials.user, manager))
            val user2 = credentials.user2
            val password2 = credentials.password2
            if (user2 != null && password2 != null) {
              val other = newManager(NasSessionConfig(), user = user2, password = password2)
              second = other
              accounts += TestAreaCleanup.Account(user2, other)
            }
            val cleanup = TestAreaCleanup(guard, accounts, strict = accounts.size > 1) { report += it }
            // Dopo la pulizia, con ogni account, sotto mcg-it non resta niente (e mcg-it vuota si toglie): un residuo fa fallire.
            val problems = cleanup.sweep(includeCurrent = true) + cleanup.verifyAndRemoveRoot()
            report += if (problems.isEmpty()) {
              "pulizia: sotto mcg-it delle ${guard.shares.size} share di prova non resta niente (verificato con ${accounts.size} account)"
            } else {
              "pulizia: RESIDUI ${problems.size}"
            }
            if (problems.isNotEmpty()) throw AssertionError("pulizia incompleta sotto mcg-it: " + problems.joinToString("; "))
          }
        }
      } finally {
        runBlockingBounded("la chiusura delle sessioni", INTEGRATION_WAIT_SECONDS) {
          second?.invalidate(InvalidateReason.USER_REQUEST)
          manager.invalidate(InvalidateReason.USER_REQUEST)
        }
        scope.cancel()
        val text = (listOf("--- giro ${RUN_ID.take(15)}") + report).joinToString("\n")
        println("=== Integrazione NAS ===\n$text")
        NasIntegrationConfig.reportFile("integrazione.txt").appendText(text + "\n")
      }
    }

    private fun newManager(
      config: NasSessionConfig,
      breakers: AuthBreakerRegistry = AuthBreakerRegistry(SHARED_STORE, scope),
      user: String = credentials.user,
      password: CharArray = credentials.password,
    ): SmbNasSessionManager {
      val acc = if (user == credentials.user) account else NasAccount(account.address, user, "it2", account.pinnedGuid)
      return SmbNasSessionManager(
        account = { acc },
        routeGate = gate,
        credentials = { CredentialLoad.Found(NasCredentials(user, password.copyOf())) },
        breakers = breakers,
        scope = scope,
        transport = transport,
        clock = clock,
        config = config,
        deviceId = { DEVICE_ID },
        shareGuard = ShareFence,
      )
    }

    /** Il file grande delle letture (64 MiB, contenuto ripetibile): scritto una volta per giro; SHA-256 e tempo. */
    private suspend fun bigFile(): Pair<Sha256, Double> {
      bigWritten?.let { return it }
      val digest = MessageDigest.getInstance("SHA-256")
      val started = System.nanoTime()
      val result = manager.runOp(credentials.share, Purpose.BACKGROUND, idempotent = false, touchesDisk = true) { ctx ->
        FsOps.mkdirs(ctx, BIG.parent())
        ctx.share.createExclusive(BIG.smb()).use { file ->
          ctx.startTransfer()
          for (i in 0 until BIG_CHUNKS) {
            val bytes = chunk(i)
            digest.update(bytes)
            var offset = 0
            while (offset < bytes.size) {
              val written = file.write(bytes, i.toLong() * MIB + offset, offset, bytes.size - offset)
              offset += written
              ctx.progress(written)
            }
          }
          file.flush()
        }
      }
      check(result is NasResult.Ok) { "scrittura del file grande fallita: ${(result as NasResult.Err).error}" }
      val ms = (System.nanoTime() - started) / 1_000_000.0
      return (Sha256(digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }) to ms).also { bigWritten = it }
    }
  }
}
