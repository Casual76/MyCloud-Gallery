package dev.pampa.mycloudgallery.core.backup.upload

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupCandidate
import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupPlanner
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupStore
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.core.backup.contract.HashRecord
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.backup.contract.PhoneDenied
import dev.pampa.mycloudgallery.core.backup.contract.PhoneLookup
import dev.pampa.mycloudgallery.core.backup.contract.PhoneOpen
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.testing.BackupInvariants
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupPaths
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasOp
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Le correzioni della revisione di sicurezza della 5A (`docs/review/5a-fase5a.md`) che toccano il **motore vero**: R1 (il nome "nome (n)"
 * oltre i 255 byte e la difesa in profondita'), R4 (la pausa dell'utente e' un cancello), R5 ("foto selezionate"), R6 (la fine del file del
 * telefono) e R7 (l'elemento di un'altra share). Ogni scenario finisce con gli invarianti del §5.4 veri.
 */
class Review5aEngineTest {

  private val h = RealEngineKit()
  private val kit get() = h.kit
  private val dir = "Backup/${BackupTestKit.DEVICE_FOLDER}/2026/10"

  private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * 131 + seed * 7 + (it shr 8)).toByte() }

  private suspend fun assertClean(before: BackupInvariants.Snapshot) {
    assertWithMessage("invarianti del §5.4").that(kit.violations(before, final = true)).isEmpty()
  }

  private fun utf8(text: String): Int = text.toByteArray(Charsets.UTF_8).size

  /** Un nome di esattamente [total] byte UTF-8: il riempitivo [filler] (anche multibyte) e, per arrivare al conto giusto, delle "a". */
  private fun nameOf(total: Int, filler: String, extension: String): String {
    val sb = StringBuilder()
    while (utf8(sb.toString()) + utf8(filler) <= total - utf8(extension)) sb.append(filler)
    while (utf8(sb.toString()) < total - utf8(extension)) sb.append('a')
    val name = sb.toString() + extension
    check(utf8(name) == total) { "il nome di prova non e' di $total byte" }
    return name
  }

  private val longNames = listOf(
    nameOf(252, "あ", ".jpg"),
    nameOf(253, "😀", ".jpg"),
    nameOf(254, "é", ".jpg"),
    nameOf(255, "日本語", ".jpg"),
    nameOf(255, "😀", ""),
  )

  // --- R1: il nome occupato con un nome da 252-255 byte --------------------------------------------------------------------------

  /**
   * La revisione: un file del telefono con un nome da 252-255 byte e, in `Backup/...`, un file **diverso** con lo stesso nome. La
   * pubblicazione risponde `Collision`, "nome (2)" non stava piu' in 255 byte: `TrustViolation`, giro morto, 5 giri su 5, il secondo media
   * mai in sicurezza. Adesso la radice del nome si accorcia a un confine di carattere e gli altri media arrivano al sicuro.
   */
  @Test
  fun r1_unNomeOccupatoDa252A255ByteDiventaNomeDueSenzaFermareGliAltri() = runTest {
    for (name in longNames) {
      val rig = RealEngineKit()
      val kit = rig.kit
      val long = kit.addPhoneMedia(name, NasContent.of(bytes(30_000, 1)))
      val other = kit.addPhoneMedia("IMG_2.jpg", NasContent.of(bytes(20_000, 2)))
      kit.putNasFile(kit.share, "$dir/$name", NasContent.of(bytes(31_000, 3)), knownToCatalog = false)
      val before = kit.snapshot()
      // I cinque giri della revisione: nessuno deve morire.
      repeat(5) { rig.runAll() }
      val items = kit.store.all().associateBy { it.mediaId }
      val item = items.getValue(long)
      assertWithMessage(name).that(item.state).isEqualTo(UploadState.DONE)
      assertThat(items.getValue(other).state).isEqualTo(UploadState.DONE)
      assertThat(kit.catalog.isSafe(long)).isTrue()
      assertThat(kit.catalog.isSafe(other)).isTrue()
      val final = item.finalPath!!
      assertWithMessage("il nome alternativo sta nei 255 byte").that(utf8(final.name)).isAtMost(255)
      assertThat(final.name).endsWith(if (name.endsWith(".jpg")) " (2).jpg" else " (2)")
      assertThat(final.parent()!!.value).isEqualTo(dir)
      // Il file diverso che c'era non si tocca; la copia nuova ha i byte del telefono.
      assertThat(kit.nas.bytes(kit.share, "$dir/$name")!!.toList()).isEqualTo(bytes(31_000, 3).toList())
      assertThat(kit.nas.bytes(kit.share, final.value)!!.toList()).isEqualTo(bytes(30_000, 1).toList())
      assertWithMessage("il nome si legge come UTF-16 valido").that(String(final.name.toByteArray(Charsets.UTF_8), Charsets.UTF_8)).isEqualTo(final.name)
      assertWithMessage("invarianti del §5.4 ($name)").that(kit.violations(before, final = true)).isEmpty()
    }
  }

  /** Lo stesso ingresso da' sempre lo stesso nome: il diario lo scrive prima di pubblicare e un processo nuovo lo ritrova uguale. */
  @Test
  fun r1_ilNomeAlternativoEDeterministico() = runTest {
    val name = nameOf(255, "あ", ".jpg")
    val media = kit.addPhoneMedia(name, NasContent.of(bytes(30_000, 4)))
    kit.putNasFile(kit.share, "$dir/$name", NasContent.of(bytes(31_000, 5)), knownToCatalog = false)
    h.resumeUntilQuiet()
    val item = kit.store.all().single { it.mediaId == media }
    assertThat(item.state).isEqualTo(UploadState.DONE)
    assertThat(item.nameAttempt).isEqualTo(1)
    // Il nome scritto nel diario e' quello che la funzione pura da' sempre per lo stesso ingresso.
    assertThat(item.finalPath).isEqualTo(UploadNames.nameFor(item.planned, 1))
    assertThat(UploadNames.nameFor(item.planned, 1)).isEqualTo(UploadNames.nameFor(SharePath.parse("$dir/$name"), 1))
    assertThat(UploadNames.nameFor(item.planned, 2)).isNotEqualTo(UploadNames.nameFor(item.planned, 1))
  }

  // --- R1: la difesa in profondita' ------------------------------------------------------------------------------------------------

  private fun catalogThrowingFor(bad: MediaId, base: BackupCatalog = kit.catalog): BackupCatalog = object : BackupCatalog by base {
    override suspend fun recordFullHash(mediaId: MediaId, versionId: VersionId, sha256: Sha256): HashRecord =
      if (mediaId == bad) throw IllegalStateException("errore inatteso di prova") else base.recordFullHash(mediaId, versionId, sha256)
  }

  /**
   * Un'eccezione di un solo elemento (che non sia l'annullamento ne' un `Error`) parcheggia **quell'elemento** con un codice di problema e
   * il giro continua: gli altri arrivano al sicuro, l'elemento non torna fra i primi a ogni giro e lo stato non resta "in corso".
   */
  @Test
  fun r1_unErroreInattesoDiUnElementoLoParcheggiaEGliAltriVannoAvanti() = runTest {
    val bad = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(25_000, 6)))
    val good1 = kit.addPhoneMedia("IMG_2.jpg", NasContent.of(bytes(26_000, 7)))
    val good2 = kit.addPhoneMedia("IMG_3.jpg", NasContent.of(bytes(27_000, 8)))
    val before = kit.snapshot()
    val engine = h.newEngine(catalog = catalogThrowingFor(bad))

    val first = h.runAll(engine).first()

    assertThat(first.done).isEqualTo(2)
    assertThat(first.failed).isEqualTo(1)
    val items = kit.store.all().associateBy { it.mediaId }
    assertThat(items.getValue(bad).state).isEqualTo(UploadState.FAILED)
    assertThat(items.getValue(bad).problem).isEqualTo(BackupProblemCode.UNEXPECTED)
    assertThat(kit.catalog.problemOf(bad)).isEqualTo(BackupProblemCode.UNEXPECTED)
    assertThat(kit.catalog.isSafe(bad)).isFalse()
    assertThat(kit.catalog.isSafe(good1) && kit.catalog.isSafe(good2)).isTrue()
    assertThat(engine.status.value).isNotInstanceOf(BackupStatus.Running::class.java)
    // Non torna: il diario non lo riprende e il piano non lo ripropone (il segno lo toglie dai candidati).
    repeat(3) { h.runAll(engine) }
    assertThat(kit.store.all().count { it.mediaId == bad }).isEqualTo(1)
    assertThat(kit.store.all().count { !it.state.terminal }).isEqualTo(0)
    // L'elemento parcheggiato e' fermo per scelta (FAILED chiede l'utente): gli invarianti "a lavoro finito" lo contano, quelli di sicurezza no.
    assertWithMessage("invarianti del §5.4").that(kit.violations(before, final = false)).isEmpty()
  }

  /** Anche un media la cui destinazione non si costruisce (il piano che lancia) non ferma il piano degli altri: porta il segno e basta. */
  @Test
  fun r1_unMediaCheIlPianoNonSaCostruireNonFermaGliAltri() = runTest {
    val bad = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(25_000, 28)))
    val good = kit.addPhoneMedia("IMG_2.jpg", NasContent.of(bytes(26_000, 29)))
    h.planner = object : BackupPlanner by kit.planner {
      override fun destination(candidate: BackupCandidate, folder: DeviceFolder): SharePath =
        if (candidate.mediaId == bad) throw IllegalStateException("destinazione di prova") else kit.planner.destination(candidate, folder)
    }
    h.restart()
    val before = kit.snapshot()

    val result = h.runAll().first()

    assertThat(result.done).isEqualTo(1)
    assertThat(kit.catalog.isSafe(good)).isTrue()
    assertThat(kit.catalog.isSafe(bad)).isFalse()
    assertThat(kit.catalog.problemOf(bad)).isEqualTo(BackupProblemCode.UNEXPECTED)
    assertThat(kit.store.all().none { it.mediaId == bad }).isTrue()
    assertWithMessage("invarianti del §5.4").that(kit.violations(before, final = true)).isEmpty()
  }

  /** Un errore che nemmeno il parcheggio puo' assorbire (il diario che non scrive): lo stato del motore non resta "in corso", poi risale. */
  @Test
  fun r1_unErroreDelGiroIntero_ChiudeLoStatoEdRisale() = runTest {
    kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(20_000, 9)))
    val brokenDiary = object : BackupStore by kit.store {
      override suspend fun update(item: dev.pampa.mycloudgallery.core.backup.contract.BackupItem) = throw IllegalStateException("diario rotto di prova")
    }
    val engine = h.newEngine(store = brokenDiary)

    val failure = runCatching { engine.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.SCHEDULED) }.exceptionOrNull()

    assertThat(failure).isInstanceOf(IllegalStateException::class.java)
    assertThat(engine.status.value).isEqualTo(BackupStatus.Problem(BackupProblemCode.UNEXPECTED, 0))
  }

  // --- R4: la pausa dell'utente e' un cancello -------------------------------------------------------------------------------------

  /** Con il cancello in pausa il giro non pianifica, non scrive niente e lo stato dice che l'utente ha fermato (non un problema). */
  @Test
  fun r4_conIlCancelloInPausaIlMotoreNonScriveNiente() = runTest {
    kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(20_000, 10)))
    kit.gate.default = BackupGateState.PAUSED
    val before = kit.snapshot()

    val result = h.runAll().first()

    assertThat(result.stoppedBy).isEqualTo(RunStop.STOPPED)
    assertThat(result.problem).isNull()
    assertThat(h.engine.status.value).isEqualTo(BackupStatus.Stopped(StopBy.USER))
    assertThat(kit.store.all()).isEmpty()
    assertThat(kit.nas.filePaths(kit.share)).isEmpty()
    // La ripresa dal diario non e' diversa: anche un `resumeAll` rispetta la pausa.
    val resumed = h.engine.resumeAll(1.hours)
    assertThat(resumed.stoppedBy).isIn(listOf(RunStop.COMPLETE, RunStop.STOPPED))
    assertThat(kit.nas.filePaths(kit.share)).isEmpty()
    // "Riprendi": il cancello torna aperto e il backup finisce il lavoro.
    kit.gate.default = BackupGateState.OPEN
    h.resumeUntilQuiet()
    assertThat(kit.store.all().single().state).isEqualTo(UploadState.DONE)
    assertClean(before)
  }

  /** La pausa toccata a giro iniziato ferma al passo successivo: il file in volo non diventa visibile e niente va perso. */
  @Test
  fun r4_laPausaAGiroIniziatoFermaAlPassoSuccessivo() = runTest {
    val first = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(20_000, 11)))
    val second = kit.addPhoneMedia("IMG_2.jpg", NasContent.of(bytes(21_000, 12)))
    val before = kit.snapshot()
    // L'utente tocca "Ferma" mentre il motore apre il primo temporaneo.
    kit.nas.fault = dev.pampa.mycloudgallery.core.nas.testing.FakeNasFault { op, _, _ ->
      if (op == FakeNasOp.OPEN_TEMP) kit.gate.default = BackupGateState.PAUSED
      null
    }
    val result = h.runAll().first()
    kit.nas.fault = null
    assertThat(result.stoppedBy).isEqualTo(RunStop.STOPPED)
    assertThat(kit.catalog.isSafe(first)).isFalse()
    assertThat(kit.catalog.isSafe(second)).isFalse()
    assertThat(kit.nas.filePaths(kit.share).filter { !it.startsWith(".mycloudgallery/") }).isEmpty()
    assertThat(kit.violations(before, final = false)).isEmpty()
    kit.gate.default = BackupGateState.OPEN
    h.resumeUntilQuiet()
    assertThat(kit.catalog.isSafe(first) && kit.catalog.isSafe(second)).isTrue()
    assertClean(before)
  }

  // --- R5: "foto selezionate" -------------------------------------------------------------------------------------------------------

  /** Un telefono che nega una riga sola con [reason] (come l'accesso parziale), sia all'apertura sia al controllo. */
  private class DenyingRow(private val inner: PhoneBytes, private val deniedId: Long, private val reason: PhoneDenied) : PhoneBytes by inner {
    var opens = 0

    override suspend fun open(ref: PhoneRef): PhoneOpen {
      if (ref.mediaStoreId != deniedId) return inner.open(ref)
      opens++
      return PhoneOpen.Denied(reason)
    }

    override suspend fun lookup(ref: PhoneRef): PhoneLookup =
      if (ref.mediaStoreId == deniedId) PhoneLookup.Denied(reason) else inner.lookup(ref)
  }

  /**
   * La revisione: con "foto selezionate" il primo elemento non scelto fermava il giro con `PARTIAL_ACCESS` a ogni giro (4 giri su 4), e
   * nessuna foto scelta arrivava sul NAS. Adesso quell'elemento aspetta e le altre vanno avanti.
   */
  @Test
  fun r5_unMediaNonScelto_AspettaEGliAltriTreArrivanoAlSicuro() = runTest {
    val notChosen = kit.addPhoneMedia("IMG_0.jpg", NasContent.of(bytes(20_000, 13)))
    val chosen = (1..3).map { kit.addPhoneMedia("IMG_$it.jpg", NasContent.of(bytes(20_000 + it * 1_000, 13 + it))) }
    val deniedId = kit.phoneIdOf(notChosen)
    val denying = DenyingRow(kit.phone.port, deniedId, PhoneDenied.NOT_SELECTED)
    h.phone = { denying }
    h.restart()
    val before = kit.snapshot()

    repeat(4) { round ->
      val result = h.runAll().first()
      assertWithMessage("giro $round").that(result.stoppedBy).isNotEqualTo(RunStop.PHONE)
      assertWithMessage("giro $round").that(result.problem).isNull()
    }

    for (media in chosen) assertThat(kit.catalog.isSafe(media)).isTrue()
    assertThat(kit.catalog.isSafe(notChosen)).isFalse()
    val skipped = kit.store.all().single { it.mediaId == notChosen }
    assertThat(skipped.state).isEqualTo(UploadState.PLANNED)
    assertThat(skipped.problem).isNull()
    assertThat(kit.catalog.problemOf(notChosen)).isNull()
    assertThat(denying.opens).isAtLeast(1)
    assertThat(kit.nas.filePaths(kit.share).filter { !it.startsWith(".mycloudgallery/") }).hasSize(3)
    // Torna l'accesso completo: anche l'elemento che aspettava arriva al sicuro.
    h.phone = { it }
    h.restart()
    h.resumeUntilQuiet()
    assertThat(kit.catalog.isSafe(notChosen)).isTrue()
    assertClean(before)
  }

  /** Anche quando il rifiuto arriva al controllo dopo la lettura o dopo un errore di lettura, una riga non scelta aspetta soltanto. */
  @Test
  fun r5_ancheIlControlloDellaRigaDopoLaLetturaFaSoloAspettareLElemento() = runTest {
    val notChosen = kit.addPhoneMedia("IMG_0.jpg", NasContent.of(bytes(20_000, 14)))
    val chosen = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(21_000, 15)))
    val deniedId = kit.phoneIdOf(notChosen)
    // L'apertura va (l'utente ha tolto la foto dalla selezione proprio ora), il controllo dopo la lettura dice "non scelta".
    h.phone = { inner ->
      object : PhoneBytes by inner {
        override suspend fun lookup(ref: PhoneRef): PhoneLookup =
          if (ref.mediaStoreId == deniedId) PhoneLookup.Denied(PhoneDenied.NOT_SELECTED) else inner.lookup(ref)
      }
    }
    h.restart()
    val result = h.runAll().first()
    assertThat(result.stoppedBy).isEqualTo(RunStop.COMPLETE)
    assertThat(kit.catalog.isSafe(chosen)).isTrue()
    assertThat(kit.catalog.isSafe(notChosen)).isFalse()
    assertThat(kit.store.all().single { it.mediaId == notChosen }.state).isNotEqualTo(UploadState.FAILED)
  }

  /** I rifiuti **globali** fermano ancora il giro: nessun altro elemento andrebbe meglio. */
  @Test
  fun r5_ILegittimiRifiutiGlobaliFermanoIlGiro() = runTest {
    for (reason in listOf(PhoneDenied.MEDIA_PERMISSION, PhoneDenied.MEDIA_LOCATION)) {
      val rig = RealEngineKit()
      rig.kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(20_000, 16)))
      rig.kit.addPhoneMedia("IMG_2.jpg", NasContent.of(bytes(21_000, 17)))
      rig.kit.phone.denied = reason
      val result = rig.runAll().first()
      assertThat(result.stoppedBy).isEqualTo(RunStop.PHONE)
      assertThat(result.problem).isEqualTo(reason.problem)
      assertThat(rig.kit.nas.filePaths(rig.kit.share)).isEmpty()
    }
  }

  // --- R6: la fine del file del telefono -------------------------------------------------------------------------------------------

  /**
   * Il file cresce dopo l'ultima lettura del MediaStore (la riga dice ancora la dimensione di prima, il timbro e' lo stesso prima e dopo): i
   * primi `size` byte sono un **prefisso**. Prima il motore li caricava, la rilettura tornava con il loro stesso hash e la versione diventava
   * "al sicuro" con una copia troncata. Adesso e' un file cambiato (E-B15): non si salva da qui, niente arriva sul NAS.
   */
  @Test
  fun r6_unFileCheContinuaOltreLaDimensioneDelMediaStoreNonDiventaAlSicuroTroncato() = runTest {
    val prefix = bytes(40_000, 18)
    val media = kit.addPhoneMedia("VID_1.mp4", NasContent.of(prefix), kind = MediaKind.VIDEO)
    val grown = prefix + bytes(5_000, 19)
    kit.phone.growSilently(kit.phoneIdOf(media), NasContent.of(grown))
    val before = BackupInvariants.Snapshot(kit.snapshot().files, kit.snapshot().phoneShas + kit.phone.allSha256())

    h.resumeUntilQuiet(maxRounds = 3)

    assertThat(kit.catalog.isSafe(media)).isFalse()
    val item = kit.store.all().single { it.mediaId == media }
    assertThat(item.state).isEqualTo(UploadState.GONE)
    assertThat(kit.nas.filePaths(kit.share)).isEmpty()
    assertThat(kit.nas.opCount(FakeNasOp.OPEN_TEMP)).isEqualTo(0)
    assertClean(before)
  }

  /** Il caso normale non cambia: il file finisce dove dice il MediaStore e arriva al sicuro (anche un file piccolo e uno grande). */
  @Test
  fun r6_unFileCheFinisceDoveDiceIlMediaStoreArrivaAlSicuro() = runTest {
    val small = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(1, 20)))
    val big = kit.addPhoneMedia("IMG_2.jpg", NasContent.of(bytes(100_000, 21)))
    h.resumeUntilQuiet()
    assertThat(kit.catalog.isSafe(small) && kit.catalog.isSafe(big)).isTrue()
  }

  /** Dopo un riavvio i byte non sono piu' in memoria: la ripresa riapre il telefono e controlla di nuovo la fine del file (R6). */
  @Test
  fun r6_ancheLaRipresaDopoUnRiavvioControllaLaFineDelFile() = runTest {
    val prefix = bytes(100_000, 22)
    val media = kit.addPhoneMedia("VID_2.mp4", NasContent.of(prefix), kind = MediaKind.VIDEO)
    // Il temporaneo non si crea (guasto prima): l'elemento resta in UPLOADING; poi il file cresce senza che il MediaStore lo sappia.
    kit.nas.opsFaults = FaultPlan().at(1, FaultPhase.BEFORE, SimFault.DropConnection, op = SimOp.CREATE)
    h.runAll()
    kit.nas.opsFaults = null
    assertThat(kit.store.all().single().state).isEqualTo(UploadState.UPLOADING)
    kit.phone.growSilently(kit.phoneIdOf(media), NasContent.of(prefix + bytes(3_000, 23)))
    h.restart()
    h.resumeUntilQuiet(maxRounds = 4)
    assertThat(kit.catalog.isSafe(media)).isFalse()
    assertThat(kit.store.all().first { it.mediaId == media }.state).isEqualTo(UploadState.GONE)
    assertThat(kit.nas.filePaths(kit.share).filter { !it.startsWith(".mycloudgallery/") }).isEmpty()
  }

  /** La lettura in se': dopo `size` byte il descrittore e il byte in piu' dicono se il file finisce li' (sul telefono finto e su byte in memoria). */
  @Test
  fun r6_laLetturaDiceSeIlFileFinisceDoveCiSiAspetta() = runTest {
    val data = bytes(5_000, 24)
    assertThat(PhoneReading.read(InMemoryMediaBytes(data), data.size.toLong(), 1024, keepInMemory = false)!!.endsAtSize).isTrue()
    // Si chiede una lettura piu' corta del file (il MediaStore e' rimasto indietro): la coda c'e', il file non finisce li'.
    val prefix = PhoneReading.read(InMemoryMediaBytes(data), 4_000, 1024, keepInMemory = false)!!
    assertThat(prefix.endsAtSize).isFalse()
    // Un file piu' corto di cio' che si aspetta: lettura corta, nessun risultato (come prima).
    assertThat(PhoneReading.read(InMemoryMediaBytes(data), 6_000, 1024, keepInMemory = false)).isNull()
    // Un telefono che dichiara una dimensione diversa dai byte letti (descrittore piu' lungo) non e' la versione.
    val lying = object : MediaBytes by InMemoryMediaBytes(data) {
      override val size: Long get() = data.size + 10L
    }
    assertThat(PhoneReading.read(lying, data.size.toLong(), 1024, keepInMemory = false)!!.endsAtSize).isFalse()
  }

  // --- R7: l'elemento di un'altra share ---------------------------------------------------------------------------------------------

  /**
   * L'area privata cambia a motore costruito: un elemento del diario con la share di prima aspetta (niente si scrive la'), e non ferma gli
   * elementi della share di adesso.
   */
  @Test
  fun r7_unElementoDiUnAltraShareAspettaEGliAltriVannoAvanti() = runTest {
    val old = kit.addPhoneMedia("IMG_OLD.jpg", NasContent.of(bytes(20_000, 25)))
    val fresh = kit.addPhoneMedia("IMG_NEW.jpg", NasContent.of(bytes(21_000, 26)))
    val media = checkNotNull(kit.catalog.media(old))
    kit.store.create(
      listOf(
        NewBackupItem(
          kind = UploadKind.NEW_COPY, mediaId = old, versionId = media.versionId, size = media.size, phone = media.phone,
          expected = media.phoneStamp, share = "altra-area", planned = SharePath.parse("$dir/IMG_OLD.jpg"),
        ),
      ),
    )
    val before = kit.snapshot()

    val result = h.runAll().first()

    assertThat(kit.catalog.isSafe(fresh)).isTrue()
    assertThat(kit.catalog.isSafe(old)).isFalse()
    val waiting = kit.store.all().single { it.mediaId == old }
    assertThat(waiting.state).isEqualTo(UploadState.PLANNED)
    assertThat(waiting.share).isEqualTo("altra-area")
    assertThat(result.waiting).isAtLeast(1)
    assertThat(result.stoppedBy).isEqualTo(RunStop.COMPLETE)
    // Il motore non ha toccato niente per quell'elemento: nessun temporaneo.
    assertThat(kit.nas.exists(kit.share, NasBackupPaths.tempFor(kit.deviceId, waiting.id.value).value)).isFalse()
    assertThat(kit.violations(before, final = false)).isEmpty()
  }

  /** Il confronto delle share non distingue le maiuscole (Samba): "TEST" e' la share del motore. */
  @Test
  fun r7_LaShareDiUnElementoSiConfrontaSenzaMaiuscole() = runTest {
    val media = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(20_000, 27)))
    val m = checkNotNull(kit.catalog.media(media))
    kit.store.create(
      listOf(
        NewBackupItem(
          kind = UploadKind.NEW_COPY, mediaId = media, versionId = m.versionId, size = m.size, phone = m.phone, expected = m.phoneStamp,
          share = kit.share.uppercase(), planned = SharePath.parse("$dir/IMG_1.jpg"),
        ),
      ),
    )
    h.resumeUntilQuiet()
    assertThat(kit.catalog.isSafe(media)).isTrue()
  }
}
