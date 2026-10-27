package dev.pampa.mycloudgallery.core.nasops.p12

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nasops.clean.OwnFilesCleaner
import dev.pampa.mycloudgallery.core.nasops.contract.FacesCurrent
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseFacts
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseReason
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseStop
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderState
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashState
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.engine.NasOpsLock
import dev.pampa.mycloudgallery.core.nasops.faces.FacesOwnFilesCleaner
import dev.pampa.mycloudgallery.core.nasops.faces.NasFacesEraser
import dev.pampa.mycloudgallery.core.nasops.testing.FakeOpClocks
import dev.pampa.mycloudgallery.core.nasops.testing.KitEngine
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.nasops.trash.NasTrashService
import dev.pampa.mycloudgallery.core.nasops.trash.SignedTrashRecordSource
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.data.TrashRecord
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.File
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * "Nessun percorso letto dal NAS arriva a un'azione" (riga P12 del §4.4; formato §9 punto 7, M10; E-S11, C10).
 *
 * 1. **Nel codice**: il diario nasce solo da `start` (con la conferma) e le voci del cestino solo dall'esecuzione di un elemento del
 *    diario; l'esecuzione, la pulizia del cestino e quella dei propri file non nominano mai i record letti dal NAS; le primitive
 *    distruttive si chiamano solo dall'esecuzione, dall'eliminazione del proprio cestino e dalla pulizia dei propri file. Fase 8 (P8, D31):
 *    l'unica eccezione dichiarata sono i dati dei volti dell'account, e solo `deleteIfUnchanged`, solo da `faces/NasFacesEraser.kt` e
 *    `faces/FacesOwnFilesCleaner.kt` (il cammino stretto di `faces/<G>/`).
 * 2. **Sui dati**: un NAS pieno di contenuti ostili (record falsi e firmati da altri, finti cestini, teste e segmenti piantati, nomi
 *    strani, prenotazioni, temporanei altrui) attraversato da **tutti** gli ingressi automatici (ripresa dal diario vuoto, scadenza del
 *    cestino dopo un anno, pulizia dei propri file, "Eliminati di recente", Cartelle NAS, Spazio NAS; dalla Fase 8 anche la pulizia dei
 *    propri file dei volti e la cancellazione stretta dei volti, con file dei volti piantati) resta identico byte per byte.
 */
class NoRemoteDataToActionTest {

  private val main = File("src/main/kotlin/dev/pampa/mycloudgallery/core/nasops")

  private fun production(): Map<String, String> = main.walkTopDown()
    .filter { it.isFile && it.extension == "kt" && "/testing/" !in it.invariantSeparatorsPath }
    .associate { it.relativeTo(main).invariantSeparatorsPath to stripComments(it.readText()) }

  /** Il codice senza commenti (i commenti di questo modulo nominano spesso cio' che il codice non fa). */
  private fun stripComments(source: String): String =
    source.replace(Regex("""/\*[\s\S]*?\*/"""), "").lines().joinToString(System.lineSeparator()) { it.substringBefore("//") }

  private fun users(pattern: Regex): Set<String> = production().filterValues { pattern.containsMatchIn(it) }.keys

  @Test
  fun ilDiarioNasceSoloDallAvvioConLaConfermaELeVociDelCestinoSoloDallEsecuzione() {
    assertThat(users(Regex("""\bstore\.create\("""))).containsExactly("engine/NasOpsEngine.kt")
    assertThat(users(Regex("""\.recordTrash\("""))).containsExactly("engine/NasOpsEngine.kt")
    // L'avvio passa dalla conferma e dalle regole del piano prima di scrivere il diario.
    val engine = production().getValue("engine/NasOpsEngine.kt")
    val start = engine.substringAfter("override suspend fun start(").substringBefore("override suspend fun cancel(")
    assertThat(start.indexOf("ConfirmationGate.accept")).isLessThan(start.indexOf("store.create("))
    assertThat(start.indexOf("PlanRules.firstViolation")).isLessThan(start.indexOf("store.create("))
  }

  @Test
  fun leAzioniNonNominanoMaiIRecordLettiDalNas() {
    val remote = Regex("""\b(RemoteTrashEntry|RemoteTrashSource|RemoteTrashRead|SignedTrashRecordSource|TrashRecord\.verify)\b""")
    val offenders = users(remote) - setOf("trash/TrashRecords.kt", "trash/NasTrashService.kt", "wiring/NasOpsWiring.kt")
    assertThat(offenders).isEmpty()
    // Nel cestino i record remoti servono solo a "Eliminati di recente": la scadenza non li legge.
    val trash = production().getValue("trash/NasTrashService.kt")
    val purge = trash.substringAfter("private suspend fun purgeLocked(")
    assertThat(remote.containsMatchIn(purge)).isFalse()
    assertThat(purge).doesNotContain("remote.")
  }

  @Test
  fun lePrimitiveDistruttiveSoloDallEsecuzioneDalProprioCestinoEDallaPuliziaDeiPropriFile() {
    val destructive = mapOf(
      "rename" to Regex("""\bfileOps\.rename\("""),
      "trashMove" to Regex("""\bfileOps\.trashMove\("""),
      "publishTemp" to Regex("""\bfileOps\.publishTemp\("""),
      "deleteIfUnchanged" to Regex("""\bfileOps\.deleteIfUnchanged\("""),
      "deleteOwnTemp" to Regex("""\bfileOps\.deleteOwnTemp\("""),
      "rmdirIfEmpty" to Regex("""\bfileOps\.rmdirIfEmpty\("""),
      "copyToTemp" to Regex("""\bfileOps\.copyToTemp\("""),
      "openTemp" to Regex("""\bfileOps\.openTemp\("""),
    )
    val allowed = setOf("engine/NasOpsEngine.kt", "trash/OwnTrashPurger.kt", "clean/OwnFilesCleaner.kt")
    // Fase 8 (P8, D31): i dati dei volti dell'account si tolgono solo con un handle (deleteIfUnchanged), e solo da questi due file.
    val faces = setOf("faces/NasFacesEraser.kt", "faces/FacesOwnFilesCleaner.kt")
    for ((name, pattern) in destructive) {
      val ok = if (name == "deleteIfUnchanged") allowed + faces else allowed
      assertWithMessage(name).that(users(pattern) - ok).isEmpty()
    }
    // La pulizia dei propri file elimina solo con un handle (dimensione appena vista), mai rinomine o temporanei altrui.
    assertThat(users(Regex("""\bfileOps\.(rename|trashMove|publishTemp|copyToTemp|openTemp)\(""")) - setOf("engine/NasOpsEngine.kt")).isEmpty()
  }

  // --- Sui dati ------------------------------------------------------------------------------------------------------------------

  private val family = SigningFamily(FakeOpClocks.DEFAULT_NAS_NOW)
  private val device = family.approveDevice()
  private val other = family.approveDevice(name = "Tablet")

  private fun id(random: Random) = Base32.encode(random.nextBytes(16))

  /** Un NAS pieno di contenuti ostili o altrui, generato a caso (seme ripetibile). */
  private fun hostile(kit: NasOpsTestKit, random: Random) {
    val me = kit.deviceId
    val them = other.deviceId()
    val names = listOf("a.jpg", "IMG_0001.JPG", "..txt", "con.jpg", "x y", "ＮＡＳ", "ü.png", "😀.heic", "file.", "a:b")
    repeat(20) { n ->
      val share = if (random.nextBoolean()) "test" else "test-comune"
      val bytes = random.nextBytes(1 + random.nextInt(40))
      val trashId = id(random)
      val owner = if (random.nextBoolean()) me else them
      when (n % 8) {
        // Un file in un cestino (mio o altrui) con un record firmato dall'altro, "scaduto" da un anno.
        0 -> {
          kit.nas.putFile(share, NasLayout.trashItemFile(owner, trashId).value, NasContent.of(bytes))
          val record = TrashRecord(family.familyId, them, share, trashId, SharePath.parse("Foto/${n}.jpg"), bytes.size.toLong(), kit.clocks.nasNow!! - 365L * 86_400_000)
          kit.nas.putFile(share, NasLayout.trashRecordFile(owner, trashId).value, NasContent.of(record.sign(other)))
        }
        // Un record senza file, con un percorso d'origine qualunque.
        1 -> kit.nas.putFile(share, NasLayout.trashRecordFile(owner, trashId).value, NasContent.of(bytes))
        // Una cartella intera nel cestino di qualcuno.
        2 -> kit.nas.putFile(share, NasLayout.trashItemFile(owner, trashId).value + "/sotto/" + names[n % names.size].filter { it.isLetterOrDigit() }.ifEmpty { "f" }, NasContent.of(bytes))
        // Teste e segmenti piantati nella mia cartella.
        3 -> kit.nas.putFile(share, NasLayout.headFile(me, "index", 1L + random.nextInt(50)).value, NasContent.of(bytes))
        4 -> kit.nas.putFile(share, NasLayout.segmentFile(me, "index", Base32.encode(random.nextBytes(32))).value, NasContent.of(bytes), modifiedAt = 0L)
        // Temporanei altrui e prenotazioni di nessuno.
        5 -> kit.nas.putFile(share, ".mycloudgallery/tmp/$them-op-${id(random)}-0", NasContent.of(bytes), modifiedAt = 0L)
        6 -> kit.nas.putFile(share, ".mycloudgallery/leases/${Base32.encode(random.nextBytes(32))}/00000000000000000001/00000000000000000001", NasContent.of(bytes))
        // Media dell'utente con nomi strani (anche non validi per SharePath: il NAS finto li accetta come il PC).
        else -> kit.nas.putFile(share, "Foto/${names[n % names.size]}", NasContent.of(bytes))
      }
    }
  }

  /**
   * Fase 8 (P8): file dei volti piantati sotto `faces/<G>/` dell'area privata (teste e segmenti "miei" e dell'altro, prenotazioni, nomi
   * strani), tutti con byte a caso: nessuno e' firmato da me.
   */
  private fun hostileFaces(kit: NasOpsTestKit, random: Random, g: FaceGeneration) {
    val me = kit.deviceId
    val them = other.deviceId()
    for (owner in listOf(me, them)) {
      kit.nas.putFile("test", NasLayout.facesHead(g, owner, 1L + random.nextInt(9)).value, NasContent.of(random.nextBytes(30)), modifiedAt = 0L)
      kit.nas.putFile("test", NasLayout.facesSegment(g, owner, Base32.encode(random.nextBytes(32))).value, NasContent.of(random.nextBytes(30)), modifiedAt = 0L)
      kit.nas.putFile("test", NasLayout.facesSegmentDir(g, owner).value + "/strano.jpg", NasContent.of(random.nextBytes(30)), modifiedAt = 0L)
    }
    kit.nas.putFile("test", NasLayout.facesLeaseFile(g, "compute", 1L + random.nextInt(9)).value, NasContent.of(random.nextBytes(30)), modifiedAt = 0L)
    kit.nas.putFile("test-comune", NasLayout.facesHead(g, them, 1L).value, NasContent.of(random.nextBytes(30)), modifiedAt = 0L)
  }

  private class Facts(private val current: FacesCurrent) : FacesEraseFacts {
    override suspend fun current(): FacesCurrent = current

    override suspend fun revokedOrLost(deviceId: String): Boolean = false
  }

  private fun everything(kit: NasOpsTestKit): Map<String, List<Byte>> =
    listOf("test", "test-comune").flatMap { share -> kit.filesIn(share).map { "$share/$it" to kit.bytes(share, it)!!.toList() } }.toMap()

  @Test
  fun unNasOstileAttraversatoDaTuttiGliIngressiAutomaticiRestaIdentico() = runTest {
    for (seed in 1..25) {
      val kit = NasOpsTestKit(deviceId = device.deviceId(), engine = KitEngine.PRODUCTION, signing = family.identity(device))
      hostile(kit, Random(seed))
      val g = FaceGeneration.fromBytes(Random(seed).nextBytes(16))
      hostileFaces(kit, Random(seed), g)
      val before = everything(kit)
      kit.advance(400L * 86_400_000)
      // Ripresa dal diario (vuoto): niente da fare.
      kit.operations.resumeAll(1.minutes)
      // Scadenza del cestino con la lettura vera dei record firmati per "Eliminati di recente".
      val trash = NasTrashService(
        kit.deviceId, kit.accountId, { listOf("test", "test-comune") }, kit.store, kit.fileOps, kit.nas.reader, kit.clocks, kit.gate,
        remote = SignedTrashRecordSource({ kit.nas.fileSystem(it) }, { family.state }, Dispatchers.Unconfined), lock = NasOpsLock(),
      )
      trash.refresh()
      val shown = trash.items.value
      assertWithMessage("seme $seed").that(shown).isInstanceOf(NasTrashState.Ready::class.java)
      assertWithMessage("seme $seed: nessuna voce eliminabile").that((shown as NasTrashState.Ready).items.none { it.deletableNow }).isTrue()
      assertWithMessage("seme $seed").that(trash.purgeExpired(1.minutes).purged).isEqualTo(0)
      // Pulizia dei propri file: nessuna testa ne' segmento proprio vero, nessun temporaneo proprio.
      OwnFilesCleaner(kit.deviceId, kit.accountId, kit.fileOps, kit.nas.reader, kit.store, kit.clocks, kit.gate, NasOpsLock(), { family.state })
        .clean(listOf("test", "test-comune"), 1.minutes)
      // Fase 8 (P8, D31): la pulizia dei propri file dei volti (niente e' firmato da me) e la cancellazione stretta: con lo stato che non
      // si sa niente; la generazione corrente mai, ne' come "non corrente" ne' per un dispositivo non revocato; mai l'area comune.
      val current = Facts(FacesCurrent.Known(g))
      FacesOwnFilesCleaner(
        kit.deviceId, kit.accountId, kit.fileOps, kit.nas.reader, current, { family.state }, { emptySet() }, { "test" }, { "test-comune" },
        { null }, kit.clocks,
      ).clean(listOf("test", "test-comune"), 1.minutes)
      val unsure = NasFacesEraser(kit.fileOps, kit.nas.reader, Facts(FacesCurrent.Unknown), { "test" }, { "test-comune" }, { null }, kit.clocks)
      assertThat(unsure.eraseGeneration("test", g, FacesEraseReason.NOT_CURRENT, 1.minutes).stoppedBy).isEqualTo(FacesEraseStop.UNSURE)
      val strict = NasFacesEraser(kit.fileOps, kit.nas.reader, current, { "test" }, { "test-comune" }, { null }, kit.clocks)
      for (reason in FacesEraseReason.entries) {
        assertWithMessage("seme $seed: $reason").that(strict.eraseGeneration("test", g, reason, 1.minutes).stoppedBy).isEqualTo(FacesEraseStop.NOT_ALLOWED)
        assertWithMessage("seme $seed: $reason").that(strict.eraseDevice("test", g, other.deviceId(), reason, 1.minutes).stoppedBy).isEqualTo(FacesEraseStop.NOT_ALLOWED)
      }
      val elsewhere = FaceGeneration.fromBytes(ByteArray(16) { 1 })
      assertThat(strict.eraseGeneration("test-comune", elsewhere, FacesEraseReason.USER_CONFIRMED, 1.minutes).stoppedBy).isEqualTo(FacesEraseStop.NOT_PRIVATE_AREA)
      // Cartelle NAS e Spazio NAS: solo lettura.
      kit.folders.observe("test", SharePath.parse("Foto")).first { it !is NasFolderState.Loading }
      kit.space.load("test")
      assertWithMessage("seme $seed: il NAS e' cambiato").that(everything(kit)).isEqualTo(before)
      assertWithMessage("seme $seed: diario").that(kit.store.ops(OpState.entries.toSet())).isEmpty()
      assertWithMessage("seme $seed: cestino").that(kit.store.allTrash()).isEmpty()
    }
  }
}
