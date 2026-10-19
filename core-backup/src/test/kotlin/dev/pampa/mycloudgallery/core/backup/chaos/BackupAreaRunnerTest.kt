package dev.pampa.mycloudgallery.core.backup.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.backup.BackupTempWriter
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOps
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.File
import java.util.Properties
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Il runner dell'area di prova del backup (docs/fase5-progetto.md §5.3, §5.4): la guardia, il runner a giri brevi, la verifica dell'area dal
 * manifesto che si esegue anche da sola, e l'aggancio al NAS provato SENZA rete (la sessione SMB di produzione sopra il simulatore SMB). Qui
 * gira contro il NAS finto e il simulatore DIETRO LA STESSA GUARDIA del NAS vero; il NAS vero lo tocca solo [BackupNasIntegrationTest], e solo
 * con l'interruttore e il file delle credenziali (`tools\chaos\area.ps1 -Mode backup -Stage run`).
 */
class BackupAreaRunnerTest {

  private val runId = "20261016-101500-1a2b3c4d"
  private val password = "pw-di-prova"

  private fun properties(vararg pairs: Pair<String, String>): Properties = Properties().apply { pairs.forEach { (k, v) -> setProperty(k, v) } }

  private fun credentials(vararg extra: Pair<String, String>): BackupAreaCredentials = BackupAreaCredentials.parse(
    properties("user" to "test", "password" to password, "share" to "test", "share2" to "test-comune", "host" to "192.168.178.58", *extra),
  )!!

  // --- La configurazione: prima di qualunque socket -----------------------------------------------------------------------------------

  @Test
  fun laGuardiaAmmetteSoloLeShareDiProvaDelFileEDelCodice() {
    val guard = BackupAreaGuard.of(credentials(), runId)
    assertThat(guard.shares).containsExactly("test", "test-comune")
    assertThat(guard.root.value).isEqualTo("mcg-it/$runId")
    assertThat(guard.roundRoot(7).value).isEqualTo("mcg-it/$runId/r7")
    // Una share del file che non e' di prova allarga il recinto? No: la configurazione e' rifiutata.
    for (bad in listOf("alessio", "pampa", "NAS", "Public", "test2x", "ＮＡＳ")) {
      assertThrows(AreaViolation::class.java) { BackupAreaGuard.of(credentials("share2" to bad), runId) }
    }
    // Un account che non e' di prova.
    for (user in listOf("admin", "alessio", "PAMPA", "root", "Administrator", "test3")) {
      assertThrows(AreaViolation::class.java) { BackupAreaGuard.of(BackupAreaCredentials.parse(properties("user" to user, "password" to "x", "share" to "test"))!!, runId) }
    }
    // Il secondo account va controllato come il primo, e una password2 senza user2 e' un errore.
    assertThrows(AreaViolation::class.java) { BackupAreaGuard.of(credentials("user2" to "alessio", "password2" to "x"), runId) }
    assertThrows(AreaViolation::class.java) { BackupAreaGuard.of(credentials("password2" to "x"), runId) }
    // Numeri fuori misura e host che non e' un IP.
    assertThrows(AreaViolation::class.java) { BackupAreaGuard.of(credentials("backup.rounds" to "-5"), runId) }
    assertThrows(AreaViolation::class.java) { BackupAreaGuard.of(credentials("backup.rounds" to "abc"), runId) }
    assertThrows(AreaViolation::class.java) { BackupAreaGuard.of(credentials("backup.bigFileMb" to "0"), runId) }
    assertThrows(AreaViolation::class.java) { BackupAreaGuard.of(credentials("host" to "nas.local"), runId) }
    // Un nome di giro che non e' un nome di giro.
    assertThrows(IllegalArgumentException::class.java) { BackupAreaGuard.of(credentials(), "../x") }
  }

  @Test
  fun ilFileDelleCredenzialiNonSiStampaMai() {
    val credentials = credentials("password2" to "segreto2", "user2" to "test2")
    assertThat(credentials.toString()).doesNotContain(password)
    assertThat(credentials.toString()).doesNotContain("segreto")
  }

  @Test
  fun leChiaviFacoltativeHannoIDefaultRagionevoli() {
    val plain = credentials()
    assertThat(plain.rounds).isNull()
    assertThat(plain.bigFileMb).isNull()
    assertThat(BackupAreaGate.interruptions(plain)).isEqualTo(400)
    assertThat(BackupAreaGate.hugeBytes(plain)).isNull()
    val tuned = credentials("backup.rounds" to "20", "backup.bigFileMb" to "3072")
    assertThat(BackupAreaGate.interruptions(tuned)).isEqualTo(20)
    assertThat(BackupAreaGate.hugeBytes(tuned)).isEqualTo(3072L shl 20)
    // La taglia dei file del giro segue le chiavi: 1 MB per le foto, 200 MB o il video enorme per i video.
    val settings = BackupAreaRunner.Settings(interruptions = 1, baseSeed = 1, hugeBytes = 3072L shl 20, hugePermille = 1_000)
    val config = BackupAreaRunner.config(settings)
    assertThat(config.sizeOf(Random(1), dev.pampa.mycloudgallery.core.model.MediaKind.VIDEO)).isEqualTo(3072L shl 20)
    val photo = config.sizeOf(Random(1), dev.pampa.mycloudgallery.core.model.MediaKind.PHOTO)
    assertThat(photo).isAtLeast(1L shl 20)
    assertThat(photo).isAtMost(3L shl 19)
  }

  // --- L'autotest: tutto cio' che deve essere rifiutato lo e', con zero chiamate al vero -------------------------------------------

  @Test
  fun lAutotestDellaGuardiaRifiutaTuttoConZeroChiamateAlVero(): Unit = runBlocking {
    val guard = BackupAreaGuard.of(credentials(), runId)
    val target = GuardedTarget(FakeNasTarget(), guard)
    val world = target.openWorld(0)
    val result = BackupAreaSelfTest.run(guard, world.nas.reader, world.nas.ops, target.counter)
    world.close()
    assertWithMessage(result.failures.joinToString("\n")).that(result.failures).isEmpty()
    assertThat(result.cases).isGreaterThan(100)
    assertThat(target.counter.calls.get()).isEqualTo(0)
  }

  @Test
  fun laLetturaDellaVerificaNonEsceDalleCartelleDelManifesto(): Unit = runBlocking {
    val guard = BackupAreaGuard.of(credentials(), runId)
    val world = FakeNasTarget().openWorld(0)
    val reader = GuardedReader(world.nas.reader, guard, readRoots = listOf(SharePath.of("mcg-campo", "Backup")))
    // Dentro la cartella ammessa la chiamata arriva al NAS (la cartella non c'e' ancora: NotFound, non un rifiuto della guardia).
    val inside = reader.list("test", SharePath.of("mcg-campo", "Backup"), Purpose.BACKGROUND) { }
    assertThat(inside is NasResult.Ok || (inside as NasResult.Err).error == NasError.NotFound).isTrue()
    assertThrows(AreaViolation::class.java) { runBlocking { reader.list("test", SharePath.of("Documenti"), Purpose.BACKGROUND) { } } }
    assertThrows(AreaViolation::class.java) { runBlocking { reader.list("test", null, Purpose.BACKGROUND) { } } }
    assertThrows(AreaViolation::class.java) { runBlocking { reader.stat("alessio", SharePath.of("mcg-campo", "Backup", "x"), Purpose.BACKGROUND) } }
    world.close()
  }

  // --- Il runner a giri brevi: al massimo 20 interruzioni --------------------------------------------------------------------------

  @Test
  fun ilRunnerSulNasFintoDietroLaGuardiaFaVentiInterruzioniSenzaViolazioni(): Unit = runBlocking {
    val guard = BackupAreaGuard.of(credentials(), runId)
    val target = GuardedTarget(FakeNasTarget(), guard)
    val settings = BackupAreaRunner.Settings(interruptions = 20, baseSeed = 20261016L, mediumBytes = 30_000, bigBytes = 60_000, bigPercent = 10)
    val result = BackupAreaRunner.run(target, ProductionStack, settings)
    println(result.summary())
    assertWithMessage(result.failureText()).that(result.failures).isEmpty()
    assertThat(result.stats.totalFired).isAtLeast(20)
    // Si ferma appena le interruzioni sono cadute: non va avanti per 400.
    assertThat(result.stats.totalFired).isAtMost(20 + 6)
    assertThat(target.counter.calls.get()).isGreaterThan(0)
    // I rapporti del NAS vero non dicono nomi di file ne' impronte.
    assertThat(result.summary()).doesNotContain("IMG_")
  }

  @Test
  fun ilRunnerDellaCinqueBSulNasFintoDietroLaGuardiaFaVentiInterruzioniSenzaViolazioni(): Unit = runBlocking {
    for (mode in listOf(BackupAreaRunner.FiveB.ONLY, BackupAreaRunner.FiveB.WITH)) {
      val guard = BackupAreaGuard.of(credentials(), runId)
      val target = GuardedTarget(FakeNasTarget(), guard)
      val settings = BackupAreaRunner.Settings(interruptions = 20, baseSeed = 20261019L, mediumBytes = 30_000, bigBytes = 60_000, bigPercent = 10, fiveB = mode)
      val result = BackupAreaRunner.run(target, ProductionStack, settings)
      println(result.summary())
      assertWithMessage("${mode.code}: ${result.failureText()}").that(result.failures).isEmpty()
      assertThat(result.stats.totalFired).isAtLeast(20)
      // Le versioni e gli scambi (le due rinomine) e "Libera spazio" sono girati davvero, e il rapporto non dice nomi.
      assertThat(result.stats.callsOf(Primitive.ARCHIVE_VERSION)).isGreaterThan(0)
      assertThat(result.stats.counters.keys.any { it.startsWith("lavori version") }).isTrue()
      assertThat(result.summary()).doesNotContain("FOTO5B")
      assertThat(result.summary()).doesNotContain("VIDEO5B")
      assertThat(target.counter.calls.get()).isGreaterThan(0)
    }
  }

  // --- L'aggancio al NAS: la sessione di produzione sopra il simulatore SMB, senza rete -----------------------------------------------

  private fun backend(): InMemorySmbBackend = InMemorySmbBackend().apply {
    addUser("test", password)
    addShare("test", mapOf("test" to SimAccess.READ_WRITE))
    addShare("test-comune", mapOf("test" to SimAccess.READ_WRITE))
    addShare("alessio", mapOf("test" to SimAccess.READ_WRITE))
  }

  private fun InMemorySmbBackend.allFiles(share: String, dir: String = ""): List<String> =
    children(share, dir).flatMap { name ->
      val path = if (dir.isEmpty()) name else "$dir/$name"
      if (bytes(share, path) != null) listOf(path) else allFiles(share, path)
    }

  @Test
  fun ilRunnerSullaSessioneVeraDietroLaGuardiaNonEsceDallAreaDelGiro(): Unit = runBlocking {
    val backend = backend()
    val area = SmbBackupArea(transport = { backend }, socket = { backend.binding("pc") }, backoff = { })
    val guard = BackupAreaGuard.of(credentials(), runId)
    try {
      val target = GuardedTarget(area.openTarget(credentials(), guard), guard)
      // L'autotest della guardia: tutto rifiutato, zero chiamate al vero.
      val world = target.openWorld(0)
      val self = BackupAreaSelfTest.run(guard, world.nas.reader, world.nas.ops, target.counter)
      world.close()
      assertWithMessage(self.failures.joinToString()).that(self.failures).isEmpty()
      val settings = BackupAreaRunner.Settings(interruptions = 20, baseSeed = 20261016L, mediumBytes = 300_000, bigBytes = 600_000, bigPercent = 10)
      val result = BackupAreaRunner.run(target, ProductionStack, settings)
      println(result.summary())
      assertWithMessage(result.failureText()).that(result.failures).isEmpty()
      assertThat(result.stats.totalFired).isAtLeast(20)
      assertThat(target.counter.calls.get()).isGreaterThan(0)
    } finally {
      area.close()
    }
    // Una sola sessione, una sola prova della password (piu' le riconnessioni dopo le cadute, con la password gia' verificata).
    assertThat(area.sessionsOpened).isEqualTo(1)
    // Niente fuori da mcg-it/<giro>/, in nessuna share; la share di famiglia non e' mai stata toccata.
    for (share in listOf("test", "test-comune")) {
      val files = backend.allFiles(share)
      assertWithMessage("fuori dall'area in $share").that(files.filterNot { it.startsWith("mcg-it/$runId/r") }).isEmpty()
    }
    assertThat(backend.allFiles("alessio")).isEmpty()
  }

  @Test
  fun ilRunnerDellaCinqueBSullaSessioneVeraNonEsceDallAreaDelGiroNemmenoConVersioni(): Unit = runBlocking {
    val backend = backend()
    val area = SmbBackupArea(transport = { backend }, socket = { backend.binding("pc") }, backoff = { })
    val guard = BackupAreaGuard.of(credentials(), runId)
    try {
      val target = GuardedTarget(area.openTarget(credentials(), guard), guard)
      val settings = BackupAreaRunner.Settings(
        interruptions = 20, baseSeed = 20261019L, mediumBytes = 300_000, bigBytes = 600_000, bigPercent = 10, fiveB = BackupAreaRunner.FiveB.ONLY,
      )
      val result = BackupAreaRunner.run(target, ProductionStack, settings)
      println(result.summary())
      assertWithMessage(result.failureText()).that(result.failures).isEmpty()
      assertThat(result.stats.totalFired).isAtLeast(20)
      // Le rinomine vere (`SmbNasFileOps.renameVersion`) sono state chiamate sulla sessione vera.
      assertThat(result.stats.callsOf(Primitive.ARCHIVE_VERSION) + result.stats.callsOf(Primitive.RESTORE_VERSION)).isGreaterThan(0)
    } finally {
      area.close()
    }
    assertThat(area.sessionsOpened).isEqualTo(1)
    // `Versioni/` sta alla radice del giro, mai alla radice della share; la share di famiglia non e' mai stata toccata.
    for (share in listOf("test", "test-comune")) {
      val files = backend.allFiles(share)
      assertWithMessage("fuori dall'area in $share").that(files.filterNot { it.startsWith("mcg-it/$runId/r") }).isEmpty()
    }
    assertThat(backend.allFiles("alessio")).isEmpty()
  }

  @Test
  fun lAreaDiProvaNonSiTocca_ilTettoDelleInterruzioniVinceSulFile() {
    // Un file con backup.rounds=400 e un giro breve di prova (MCG_BACKUP_CAP, lo imposta area.ps1): la prova breve resta breve.
    val credentials = credentials("backup.rounds" to "400")
    assertThat(BackupAreaGate.interruptions(credentials)).isEqualTo(400)
  }

  @Test
  fun unaPasswordSbagliataSiProvaUnaVoltaSola(): Unit = runBlocking {
    val backend = backend()
    val area = SmbBackupArea(transport = { backend }, socket = { backend.binding("pc") }, backoff = { })
    val wrong = credentials("password" to "sbagliata")
    val guard = BackupAreaGuard.of(wrong, runId)
    val failure = runCatching { area.openTarget(wrong, guard) }.exceptionOrNull()
    area.close()
    assertThat(failure).isInstanceOf(AssertionError::class.java)
    assertThat(failure!!.message).doesNotContain("sbagliata")
    assertThat(backend.authenticateCalls.get()).isEqualTo(1)
  }

  @Test
  fun laGuardiaFermaTuttoPrimaDelPrimoSocket(): Unit = runBlocking {
    val backend = backend()
    val area = SmbBackupArea(transport = { backend }, socket = { backend.binding("pc") }, backoff = { })
    val guard = BackupAreaGuard.of(credentials(), runId)
    // Credenziali diverse da quelle della guardia: una share fuori dall'area.
    val other = credentials("share2" to "alessio")
    assertThrows(AreaViolation::class.java) { runBlocking { area.openTarget(other, guard) } }
    assertThat(backend.tcpAttempts.get()).isEqualTo(0)
  }

  // --- Un errore di autenticazione ferma la prova subito, senza nuovi tentativi ---------------------------------------------------------

  /** Un NAS la cui password "cambia" a meta' prova: la prima apertura di un temporaneo risponde con un rifiuto d'autenticazione. */
  private class RejectingTarget(private val inner: BackupChaosTarget, val attempts: java.util.concurrent.atomic.AtomicInteger) : BackupChaosTarget {
    override val name: String = "NAS che rifiuta la password"

    override suspend fun openWorld(round: Int): BackupChaosWorld {
      val world = inner.openWorld(round)
      val rejecting = object : NasSide by world.nas {
        override val ops: NasBackupOps = object : NasBackupOps by world.nas.ops {
          override suspend fun openTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<BackupTempWriter> {
            attempts.incrementAndGet()
            return NasResult.Err(NasError.Auth(AuthBlockReason.AUTH_FAILED))
          }
        }
      }
      return BackupChaosWorld(world.clock, rejecting, world.deviceId, world.chunkBytes, world.checkpointBytes)
    }
  }

  @Test
  fun unErroreDiAutenticazioneFermaLaProvaSubitoENonSiRiprova(): Unit = runBlocking {
    val guard = BackupAreaGuard.of(credentials(), runId)
    val attempts = java.util.concurrent.atomic.AtomicInteger()
    val target = GuardedTarget(RejectingTarget(FakeNasTarget(), attempts), guard)
    val settings = BackupAreaRunner.Settings(interruptions = 20, baseSeed = 1L, mediumBytes = 30_000, bigBytes = 60_000)
    val stop = runCatching { BackupAreaRunner.run(target, ProductionStack, settings) }.exceptionOrNull()
    assertThat(stop).isInstanceOf(AreaAuthStop::class.java)
    assertThat(stop!!.message).contains("nessun nuovo tentativo")
    // Una sola richiesta e' arrivata al NAS dopo il rifiuto: nessun altro tentativo.
    assertThat(attempts.get()).isEqualTo(1)
  }

  // --- Il manifesto e la verifica che si esegue da sola ------------------------------------------------------------------------------

  private fun sha(n: Int) = Sha256.of(byteArrayOf(n.toByte(), 3, 3))

  private fun manifest(vararg phone: Pair<Sha256, Long>, deviceId: String? = null, pre: List<BackupAreaManifest.Pre> = emptyList()) = BackupAreaManifest(
    runId = runId,
    deviceId = deviceId,
    dest = listOf("test" to SharePath.of("mcg-campo")),
    app = listOf("test" to null),
    phone = phone.mapIndexed { i, (sha, size) -> BackupAreaManifest.PhoneFile(sha, size, "foto$i") },
    pre = pre,
  )

  @Test
  fun ilManifestoSiScriveESiRilegge() {
    val file = File.createTempFile("mcg-backup-manifest", ".txt")
    try {
      val m = manifest(sha(1) to 10L, sha(2) to 20L, deviceId = "ABCDEFGHIJKLMNOPQRSTUVWXYZ", pre = listOf(BackupAreaManifest.Pre(sha(3), "test", "mcg-campo/Backup/vecchia.jpg")))
      m.write(file)
      val back = BackupAreaManifest.read(file)
      assertThat(back.runId).isEqualTo(runId)
      assertThat(back.deviceId).isEqualTo("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
      assertThat(back.dest.single().second?.value).isEqualTo("mcg-campo")
      assertThat(back.app.single().second).isNull()
      assertThat(back.phone.map { it.sha }).containsExactly(sha(1), sha(2)).inOrder()
      assertThat(back.pre.single().path).isEqualTo("mcg-campo/Backup/vecchia.jpg")
      assertThrows(IllegalArgumentException::class.java) { BackupAreaManifest.parse(listOf("run=x", "phone=zzz|1|a", "dest=test|")) }
      assertThrows(IllegalArgumentException::class.java) { BackupAreaManifest.parse(listOf("run=x")) }
    } finally {
      file.delete()
    }
  }

  /** Il manifesto come lo scrive `tools\chaos\s25.ps1 -Mode backup -Step seed` (l'uscita di `s25.ps1 -SelfTest`): deve restare leggibile da qui. */
  @Test
  fun ilManifestoDelloScriptDelTelefonoSiLegge() {
    val text = """
      # mcg-backup-manifest 1
      run=20261016-101500-1a2b3c4d
      device=
      dest=test|mcg-campo
      app=test|
      phone=2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae|1234|IMG_0001.jpg
      phone=fcde2b2edba56bf408601fb721fe9b5c338d10ee429ea04fae5511b68fbf8fb9|2684354560|VID_big_1.mp4
    """.trimIndent()
    val m = BackupAreaManifest.parse(text.lines())
    assertThat(m.runId).isEqualTo("20261016-101500-1a2b3c4d")
    assertThat(m.deviceId).isNull()
    assertThat(m.dest.single().first).isEqualTo("test")
    assertThat(m.dest.single().second?.value).isEqualTo("mcg-campo")
    assertThat(m.phone.map { it.size }).containsExactly(1234L, 2684354560L).inOrder()
    assertThat(m.phone.map { it.label }).containsExactly("IMG_0001.jpg", "VID_big_1.mp4").inOrder()
  }

  @Test
  fun laVerificaDaSolaTrovaUnaPerditaUnDoppioneUnFileAMetaEUnTemporaneoRimasto(): Unit = runBlocking {
    val world = FakeNasTarget().openWorld(0)
    val a = Random(1).nextBytes(2_000)
    val b = Random(2).nextBytes(3_000)
    val m = manifest(Sha256.of(a) to a.size.toLong(), Sha256.of(b) to b.size.toLong(), deviceId = BackupChaosWorld.DEVICE)
    fun put(share: String, path: String, bytes: ByteArray) = runBlocking { world.nas.pcPut(share, path, bytes, 1_000L) }
    // Il backup e' arrivato in fondo: i due file del telefono, una volta sola, in Backup/.
    put("test", "mcg-campo/Backup/S25/2026/10/foto0.jpg", a)
    put("test", "mcg-campo/Backup/S25/2026/10/foto1.jpg", b)
    val fine = BackupAreaVerifier.verify(world.nas.reader, m, settled = true)
    assertWithMessage(fine.text()).that(fine.violations).isEmpty()
    assertThat(fine.filesHashed).isEqualTo(2)
    // Un file a meta' (i primi byte soltanto) e una copia in piu': un file a meta' e un doppione.
    put("test", "mcg-campo/Backup/S25/2026/10/foto2.jpg", a.copyOf(500))
    put("test", "mcg-campo/Backup/S25/2026/10/foto0 (2).jpg", a)
    assertThat(BackupAreaVerifier.verify(world.nas.reader, m, settled = true).violations.map { it.invariant }).containsAtLeast(Invariant.HALF_FILE, Invariant.DUPLICATE)
    // Un temporaneo del dispositivo rimasto a lavoro finito (a meta' va bene), e uno di un altro dispositivo.
    put("test", ".mycloudgallery/tmp/${BackupChaosWorld.DEVICE}-bk-1", ByteArray(3))
    put("test", ".mycloudgallery/tmp/ZZZZZZZZZZZZZZZZZZZZZZZZZZ-bk-2", ByteArray(3))
    val temp = BackupAreaVerifier.verify(world.nas.reader, m, settled = true)
    assertThat(temp.violations.map { it.invariant }).contains(Invariant.TEMP)
    assertThat(temp.violations.count { it.invariant == Invariant.TEMP }).isEqualTo(1)
    assertThat(BackupAreaVerifier.verify(world.nas.reader, m, settled = false).violations.map { it.invariant }).doesNotContain(Invariant.TEMP)
    // Il rapporto non dice nomi.
    assertThat(temp.text()).doesNotContain("foto")
    world.close()
  }

  @Test
  fun laVerificaDaSolaSaCheUnFileMancanteAMetaVaBeneAFineNo(): Unit = runBlocking {
    val world = FakeNasTarget().openWorld(0)
    val a = Random(3).nextBytes(1_000)
    val b = Random(4).nextBytes(1_500)
    val m = manifest(Sha256.of(a) to a.size.toLong(), Sha256.of(b) to b.size.toLong())
    world.nas.pcPut("test", "mcg-campo/Backup/S25/2026/10/foto0.jpg", a, 1_000L)
    assertThat(BackupAreaVerifier.verify(world.nas.reader, m, settled = false).violations).isEmpty()
    assertThat(BackupAreaVerifier.verify(world.nas.reader, m, settled = true).violations.map { it.invariant }).containsExactly(Invariant.LOSS)
    // Un file che c'era prima del backup e che cambia e' una sostituzione.
    val old = Random(5).nextBytes(800)
    val withPre = manifest(Sha256.of(a) to a.size.toLong(), pre = listOf(BackupAreaManifest.Pre(Sha256.of(old), "test", "mcg-campo/Backup/vecchia.jpg")))
    world.nas.pcPut("test", "mcg-campo/Backup/vecchia.jpg", old.copyOf(799), 1_000L)
    assertThat(BackupAreaVerifier.verify(world.nas.reader, withPre, settled = false).violations.map { it.invariant }).contains(Invariant.REPLACED)
    world.close()
  }

  // --- 5B: le modifiche del telefono, l'originale in Versioni/, la modifica al solito percorso ---------------------------------------------

  @Test
  fun ilManifestoConLeModificheSiScriveESiRilegge() {
    val file = File.createTempFile("mcg-backup-manifest", ".txt")
    try {
      val m = BackupAreaManifest(
        runId, null, listOf("test" to SharePath.of("mcg-campo")), listOf("test" to null), listOf(BackupAreaManifest.PhoneFile(sha(1), 10, "foto0")), emptyList(),
        edits = listOf(BackupAreaManifest.Edit(sha(1), sha(2), "foto0")),
      )
      m.write(file)
      val back = BackupAreaManifest.read(file)
      assertThat(back.edits).hasSize(1)
      assertThat(back.edits.single().old).isEqualTo(sha(1))
      assertThat(back.edits.single().new).isEqualTo(sha(2))
      assertThat(back.edits.single().label).isEqualTo("foto0")
      assertThat(file.readText()).contains("edit=${sha(1)}|${sha(2)}|foto0")
    } finally {
      file.delete()
    }
  }

  @Test
  fun laVerificaDaSolaGiudicaLeModificheDelTelefono(): Unit = runBlocking {
    val old = Random(11).nextBytes(2_000)
    val edited = Random(12).nextBytes(2_100)
    val manifest = BackupAreaManifest(
      runId, BackupChaosWorld.DEVICE, listOf("test" to SharePath.of("mcg-campo")), listOf("test" to null),
      listOf(BackupAreaManifest.PhoneFile(Sha256.of(old), old.size.toLong(), "foto0")), emptyList(),
      edits = listOf(BackupAreaManifest.Edit(Sha256.of(old), Sha256.of(edited), "foto0")),
    )
    val usual = "mcg-campo/Backup/S25/2026/10/foto0.jpg"
    val archived = "Versioni/Backup/S25/2026/10/foto0 (originale).jpg"
    suspend fun verdict(settled: Boolean, build: suspend (BackupChaosWorld) -> Unit): List<Invariant> {
      val world = FakeNasTarget().openWorld(0)
      build(world)
      val result = BackupAreaVerifier.verify(world.nas.reader, manifest, settled)
      world.close()
      return result.violations.map { it.invariant }
    }
    // La versione nuova al solito percorso, l'originale in Versioni/: tutto in ordine.
    assertThat(verdict(true) { it.nas.pcPut("test", usual, edited, 1_000L); it.nas.pcPut("test", archived, old, 1_000L) }).isEmpty()
    // Dopo "Ripristina originale" (lo scambio) e' lo stesso, a parti invertite.
    assertThat(verdict(true) { it.nas.pcPut("test", usual, old, 1_000L); it.nas.pcPut("test", archived, edited, 1_000L) }).isEmpty()
    // La modifica non e' arrivata sul NAS (12).
    assertThat(verdict(true) { it.nas.pcPut("test", usual, old, 1_000L) }).contains(Invariant.USUAL_PATH)
    // L'originale non c'e' piu' (13): la modifica ha preso il suo posto.
    assertThat(verdict(true) { it.nas.pcPut("test", usual, edited, 1_000L) }).contains(Invariant.VERSIONS_KEPT)
    // Gli stessi byte due volte (2), a meta' come a lavoro finito.
    assertThat(verdict(false) { it.nas.pcPut("test", usual, edited, 1_000L); it.nas.pcPut("test", archived, edited, 1_000L) }).contains(Invariant.DUPLICATE)
    // Un file in Versioni/ con byte di nessuno (1).
    assertThat(verdict(false) { it.nas.pcPut("test", archived, Random(99).nextBytes(500), 1_000L) }).contains(Invariant.HALF_FILE)
    // A meta' (la prova non e' ferma) la modifica puo' non essere ancora arrivata: nessuna violazione.
    assertThat(verdict(false) { it.nas.pcPut("test", usual, old, 1_000L) }).isEmpty()
  }

  // --- Il cancello per il NAS vero ---------------------------------------------------------------------------------------------------

  @Test
  fun senzaInterruttoreNonSiLeggeNienteESiSalta() {
    // Questo test non apre sockets: senza MCG_NAS_INTEGRATION il cancello salta prima di leggere il file delle credenziali.
    if (!BackupAreaGate.enabled) {
      val outcome = BackupAreaGate.evaluate(runId)
      assertThat(outcome).isInstanceOf(BackupAreaGate.Outcome.Skip::class.java)
      assertThat((outcome as BackupAreaGate.Outcome.Skip).reason).contains("non richiesta")
    }
  }
}
