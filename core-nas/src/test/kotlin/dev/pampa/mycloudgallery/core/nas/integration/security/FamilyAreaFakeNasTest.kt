package dev.pampa.mycloudgallery.core.nas.integration.security

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nas.WAIT_SECONDS
import dev.pampa.mycloudgallery.core.nas.fs.smb
import dev.pampa.mycloudgallery.core.nas.integration.ItCredentials
import dev.pampa.mycloudgallery.core.nas.integration.SmbAccess
import dev.pampa.mycloudgallery.core.nas.integration.TestAreaViolation
import dev.pampa.mycloudgallery.core.nas.runBlockingBounded
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.crypto.RecoveryCode
import dev.pampa.mycloudgallery.core.trust.crypto.RecoveryKit
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.FamilyAuthority
import dev.pampa.mycloudgallery.core.trust.registry.FamilyConfig
import dev.pampa.mycloudgallery.core.trust.registry.RegistrationRequest
import java.io.File
import java.util.Properties
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * La regola nuova della seconda parte di P11 (C470, decisione 2 dell'utente del 02/11) e gli strumenti dell'area della famiglia di prova,
 * **provati contro un NAS finto** (`InMemorySmbBackend`: `test`, `test-comune`, `test2` e le share di famiglia `NAS`, `Public`, `alessio`,
 * che l'account di prova qui puo' perfino scrivere). Gira sempre in `check.ps1` (pochi secondi). Dimostra che: il reset della famiglia
 * ("Ricominciare da zero") mette da parte `family` e il confronto delle fotografie vede ogni cambiamento fuori da `.mycloudgallery/`; la
 * semina di S2, S3 (b), S3 (c) e S7 scrive **solo** nelle zone della regola e la pulizia toglie **solo** il suo registro (il manifest di
 * prima torna com'era); la guardia rifiuta ogni altro percorso prima della chiamata.
 */
class FamilyAreaFakeNasTest {

  /** Lo stato dei giri (chiavi di prova generate qui) sta in una cartella temporanea che JUnit toglie alla fine di ogni prova. */
  @get:Rule val temp = TemporaryFolder()

  private val password = "Prova-Famiglia-P11b-1"
  private val nasNow = 1_793_000_000_000L
  private val oldManifest = "manifest della famiglia di prima".toByteArray()

  private fun backend(): InMemorySmbBackend = InMemorySmbBackend().apply {
    addUser("test", password)
    addUser("test2", "Prova-Famiglia-P11b-2")
    addShare("test", mapOf("test" to SimAccess.READ_WRITE))
    addShare("test-comune", mapOf("test" to SimAccess.READ_WRITE, "test2" to SimAccess.READ_WRITE))
    addShare("test2", mapOf("test2" to SimAccess.READ_WRITE, "test" to SimAccess.READ_WRITE))
    for (family in listOf("NAS", "Public", "alessio")) {
      addShare(family, mapOf("test" to SimAccess.READ_WRITE))
      put(family, "famiglia.jpg", byteArrayOf(4, 5, 6))
      put(family, ".mycloudgallery/family/registry/00000000000000000001", byteArrayOf(7))
    }
    put("test-comune", "Foto/comune.jpg", byteArrayOf(1, 2, 3))
    put("test-comune", ".mycloudgallery/family/registry/00000000000000000001", byteArrayOf(9, 9))
    put("test-comune", ".mycloudgallery/release/manifest.mcgr", oldManifest)
    put("test-comune", "mcg-ostile/di-prima.jpg", byteArrayOf(8))
    put("test", "Foto/privata.jpg", byteArrayOf(1))
    put("test2", "Foto/di-test2.jpg", byteArrayOf(2))
  }

  private fun credentials(share: String = "test", share2: String? = "test-comune", user: String = "test"): ItCredentials = checkNotNull(
    ItCredentials.parse(
      Properties().apply {
        setProperty("user", user); setProperty("password", password); setProperty("share", share); setProperty("host", "192.168.178.58")
        share2?.let { setProperty("share2", it) }
      },
    ),
  )

  private fun state(): FamilyRunState = FamilyRunState.loadOrCreate(temp.newFolder()) { SecurityArea.newRunId() }

  private fun open(backend: InMemorySmbBackend, state: FamilyRunState) = FamilyArea.open(credentials(), backend, backend.binding("wifi-casa"), backend.serverGuid, state)

  /** La famiglia nuova come la scrive l'app con "Crea la famiglia": registro versione 1 nell'area comune, kit nell'area privata. */
  private fun foundNewFamily(backend: InMemorySmbBackend): String {
    val key = FamilyKey.generate()
    val familyId = TrustIds.random()
    val founder = PcDevice.generate("fondatore")
    val request = checkNotNull(RegistrationRequest.verify(RegistrationRequest.sign(founder, familyId, "S25", "SM-S931B", "test", nasNow), familyId, founder.id).acceptedOrNull())
    val registry = FamilyAuthority(key) { nasNow }.found(familyId, FamilyConfig(nasHost = "192.168.178.58", commonShare = "test-comune"), request)
    backend.put("test-comune", NasLayout.registryFile(1).value, registry.bytes)
    backend.put("test", NasLayout.recoveryKitFile(familyId, key.publicKey.fingerprint).value, RecoveryKit.seal(key, familyId, RecoveryCode.generate(), nasNow))
    backend.put("test-comune", NasLayout.statusFile(founder.id, 1).value, byteArrayOf(1))
    return familyId
  }

  /** Ogni file di ogni share, con i byte (per dire "niente e' cambiato"). */
  private fun everything(backend: InMemorySmbBackend, share: String): Map<String, List<Byte>> {
    val out = HashMap<String, List<Byte>>()
    fun walk(dir: String) {
      for (name in backend.children(share, dir)) {
        val path = if (dir.isEmpty()) name else "$dir/$name"
        val bytes = backend.bytes(share, path)
        if (bytes == null) walk(path) else out[path.lowercase()] = bytes.toList()
      }
    }
    walk("")
    return out
  }

  @Test
  fun ilResetDellaFamigliaMetteDaParteFamilyEIlConfrontoVedeOgniCambiamentoFuori() {
    runBlockingBounded("il reset della famiglia di prova", WAIT_SECONDS * 4) {
      val backend = backend()
      val state = state()
      open(backend, state).use { area ->
        area.verify()
        val before = area.snapshot("test") + area.snapshot("test-comune")
        assertThat(FamilyAreaProbes.archiveFamily(area)).isTrue()
        assertThat(FamilyAreaProbes.archiveFamily(area)).isFalse() // una seconda volta non c'e' piu' niente da mettere da parte
        val familyId = foundNewFamily(backend)
        val info = checkNotNull(FamilyAreaProbes.readFamily(area)) { "la famiglia nuova non si legge" }
        assertThat(info.familyId).isEqualTo(familyId)
        assertThat(info.registryVersion).isEqualTo(1)
        assertThat(info.statusOf.values.all { it == null }).isTrue()

        val after = area.snapshot("test") + area.snapshot("test-comune")
        val diff = FamilyAreaProbes.compare(before, after, area.guard.familyArchive)
        assertWithMessage(diff.line()).that(diff.clean).isTrue()
        assertThat(diff.familyArchived).isTrue()
        assertThat(diff.addedInside).isAtLeast(3)

        // Il controllo negativo: una foto nuova e una tolta fuori da .mycloudgallery si vedono.
        backend.put("test", "Foto/nuova.jpg", byteArrayOf(5))
        backend.delete("test-comune", "Foto/comune.jpg")
        val dirty = FamilyAreaProbes.compare(before, area.snapshot("test") + area.snapshot("test-comune"), area.guard.familyArchive)
        assertThat(dirty.addedOutside).isEqualTo(1)
        assertThat(dirty.removedOutside).isEqualTo(1)
        assertThat(dirty.clean).isFalse()
        println(diff.line() + " banco=finto")
      }
      assertThat(backend.exists("test-comune", ".mycloudgallery/family-vecchia-${state.runId}/registry/00000000000000000001")).isTrue()
      assertWithMessage("il registro della guardia: la famiglia vecchia non e' della prova").that(state.ledger().none { it.contains("family-vecchia") }).isTrue()
    }
  }

  @Test
  fun laSeminaScriveSoloNelleZoneDellaRegolaELaPuliziaToglieSoloIlSuo() {
    runBlockingBounded("la semina nell'area della famiglia di prova", WAIT_SECONDS * 10) {
      val backend = backend()
      val untouched = listOf("NAS", "Public", "alessio", "test2").associateWith { everything(backend, it) }
      val state = state()
      open(backend, state).use { area -> FamilyAreaProbes.archiveFamily(area) }
      foundNewFamily(backend)
      val start = listOf("test", "test-comune").associateWith { everything(backend, it) }
      val codes = File(state.dir, "codici.txt")
      val lines = ArrayList<String>()
      open(backend, state).use { area ->
        val info = checkNotNull(FamilyAreaProbes.readFamily(area))
        assertThat(FamilyAreaProbes.requests(area, info, nasNow, codes)).isEqualTo(2)
        val s2 = FamilyAreaProbes.seedRogue(area, info, nasNow)
        val s3b = FamilyAreaProbes.seedHostileSegments(area, info, nasNow)
        val s3c = FamilyAreaProbes.seedImages(area, null)
        val s7a = FamilyAreaProbes.seedTamperedModels(area, listOf("a".repeat(64), "b".repeat(64), "non-un-impronta"))
        assertThat(s7a.files).isEqualTo(2)
        val s1 = FamilyAreaProbes.seedPrivateMarks(area, info, nasNow, File(state.dir, "segno-s1.txt"))
        assertThat(s1.files).isEqualTo(2)
        assertThat(File(state.dir, "segno-s1.txt").readText().trim()).matches("MCG-PRIV-[0-9a-f]{12}")
        assertThat(FamilyAreaProbes.placeManifest(area, "busta 1".toByteArray())).isTrue()
        assertThat(FamilyAreaProbes.placeManifest(area, "busta 2".toByteArray())).isTrue()
        lines += listOf(s2.line(), s3b.line(), s3c.line())
        assertThat(s2.heads).isEqualTo(6)
        assertThat(s3b.segments).isAtLeast(16)
        assertThat(s3c.files).isEqualTo(3)
      }
      assertThat(codes.readLines().filter { it.matches(Regex("^(approvato|revocato)=[A-Z0-9]{4}(-[A-Z0-9]{4}){3}$")) }).hasSize(2)
      // Tutto cio' che e' nuovo sta nelle zone della regola, e solo sulle due share.
      for ((share, before) in start) {
        val added = everything(backend, share).keys - before.keys
        for (path in added) {
          val ok = (share == "test-comune" && path.startsWith("mcg-ostile/")) ||
            state.devices.values.any { path.startsWith(".mycloudgallery/devices/${it.id}/") } ||
            (share == "test-comune" && (path.startsWith(".mycloudgallery/albums/") || path.startsWith(".mycloudgallery/family/requests/") || path.startsWith(".mycloudgallery/release/")))
          assertWithMessage("$share/$path fuori dalle zone della regola").that(ok).isTrue()
        }
      }
      assertThat(backend.bytes("test-comune", ".mycloudgallery/release/manifest.mcgr")!!.toList()).isEqualTo("busta 2".toByteArray().toList())
      // Un comando dopo, con lo stesso stato: la pulizia toglie esattamente il registro.
      open(backend, state).use { area ->
        assertWithMessage("residui").that(FamilyAreaProbes.cleanup(area)).isEmpty()
      }
      for ((share, before) in start) assertWithMessage("$share dopo la pulizia").that(everything(backend, share)).isEqualTo(before)
      for ((share, before) in untouched) assertWithMessage("$share mai toccata").that(everything(backend, share)).isEqualTo(before)
      assertThat(backend.bytes("test-comune", ".mycloudgallery/release/manifest.mcgr")!!.toList()).isEqualTo(oldManifest.toList())
      assertThat(state.ledger()).isEmpty()
      println(lines.joinToString("\n") { "$it banco=finto" })
    }
  }

  @Test
  fun laGuardiaRifiutaOgniAltroPercorsoPrimaDellaChiamata() {
    runBlockingBounded("la guardia dell'area della famiglia di prova", WAIT_SECONDS * 4) {
      val backend = backend()
      val state = state()
      val p = state.device("approvato").id
      val other = TrustIds.random()
      open(backend, state).use { area ->
        val guard = area.guard
        val album = "0b6c1a52-9a0e-4b7e-8d55-1f2a3b4c5d6e"
        val allowed = listOf(
          Triple("test-comune", "mcg-ostile\\nuova.jpg", SmbAccess.CREATE), Triple("test", ".mycloudgallery\\devices\\$p\\personal\\head\\x", SmbAccess.CREATE),
          Triple("test-comune", ".mycloudgallery\\albums\\$album\\$p\\head\\x", SmbAccess.CREATE), Triple("test-comune", ".mycloudgallery\\family\\requests\\$p", SmbAccess.CREATE),
          Triple("test-comune", ".mycloudgallery\\release\\manifest.mcgr", SmbAccess.CREATE), Triple("test", "Foto", SmbAccess.LIST),
          Triple("test-comune", ".mycloudgallery\\family\\registry\\00000000000000000001", SmbAccess.OPEN_READ),
        )
        for ((share, raw, op) in allowed) guard.checkSmb(share, raw, op)
        val forbidden = listOf(
          // altre share, in ogni forma
          Triple("NAS", "x.jpg", SmbAccess.LIST), Triple("nas", "x.jpg", SmbAccess.STAT), Triple("\uFF2E\uFF21\uFF33", "x", SmbAccess.LIST),
          Triple("Public", "mcg-ostile\\x.jpg", SmbAccess.CREATE), Triple("PUBLIC", ".mycloudgallery", SmbAccess.LIST), Triple("alessio", ".mycloudgallery\\devices\\$p\\x\\y", SmbAccess.CREATE),
          Triple("test2", "mcg-ostile\\x.jpg", SmbAccess.CREATE), Triple("test2", ".mycloudgallery\\devices\\$p\\index\\head\\x", SmbAccess.CREATE), Triple("test ", "x", SmbAccess.LIST),
          // fuori dalle zone, sulle share giuste
          Triple("test", "mcg-ostile\\x.jpg", SmbAccess.CREATE), Triple("test-comune", "Foto\\x.jpg", SmbAccess.CREATE), Triple("test-comune", "x.jpg", SmbAccess.CREATE),
          Triple("test-comune", "Foto\\comune.jpg", SmbAccess.OPEN_READ), Triple("test-comune", "Foto\\comune.jpg", SmbAccess.DELETE),
          Triple("test-comune", ".mycloudgallery\\devices\\$other\\index\\head\\x", SmbAccess.CREATE), Triple("test-comune", ".mycloudgallery\\devices\\$other", SmbAccess.MKDIR),
          Triple("test-comune", ".mycloudgallery\\family\\registry\\00000000000000000002", SmbAccess.CREATE), Triple("test-comune", ".mycloudgallery\\family\\requests\\$other", SmbAccess.CREATE),
          Triple("test-comune", ".mycloudgallery\\family", SmbAccess.DELETE), Triple("test-comune", ".mycloudgallery\\family", SmbAccess.RMDIR),
          Triple("test-comune", ".mycloudgallery\\altra-famiglia", SmbAccess.RENAME), Triple("test", ".mycloudgallery\\family", SmbAccess.CHANGE),
          Triple("test", ".mycloudgallery\\recovery\\x\\y", SmbAccess.CREATE), Triple("test-comune", ".mycloudgallery\\release\\altro.mcgr", SmbAccess.CREATE),
          Triple("test-comune", ".mycloudgallery\\release\\manifest.mcgr", SmbAccess.DELETE), Triple("test-comune", "mcg-ostile\\di-prima.jpg", SmbAccess.DELETE),
          Triple("test-comune", ".mycloudgallery\\albums\\non-un-uuid\\$p\\head\\x", SmbAccess.CREATE), Triple("test-comune", ".mycloudgallery\\albums\\$album\\$other\\head\\x", SmbAccess.CREATE),
          // percorsi che potrebbero uscire
          Triple("test-comune", "mcg-ostile\\..\\x.jpg", SmbAccess.CREATE), Triple("test-comune", "mcg-ostile\\.\\x.jpg", SmbAccess.CREATE),
          Triple("test-comune", "mcg-ostile\\\\x.jpg", SmbAccess.CREATE), Triple("test-comune", "mcg-ostile\\x\u0000.jpg", SmbAccess.CREATE),
        )
        for ((share, raw, op) in forbidden) {
          assertThrows("$share $raw $op", TestAreaViolation::class.java) { guard.checkSmb(share, raw, op) }
        }
        for (user in listOf("test2", "admin", "ADMIN", "alessio", "root")) assertThrows(TestAreaViolation::class.java) { guard.checkAccount(user) }
        // Dal trasporto vero: cancellare dallo handle la famiglia non passa, e niente cambia.
        val error = runCatching { area.op("test-comune") { ctx -> ctx.share.openForChange(SharePath.of(".mycloudgallery", "family").smb(), exclusive = true).use { it.deleteOnClose() } } }.exceptionOrNull()
        assertThat(error).isNotNull()
        assertThat(backend.exists("test-comune", ".mycloudgallery/family/registry/00000000000000000001")).isTrue()
        val created = runCatching { area.create("test-comune", SharePath.of("Foto", "x.jpg"), byteArrayOf(1)) }.exceptionOrNull()
        assertThat(created).isNotNull()
        assertThat(backend.exists("test-comune", "Foto/x.jpg")).isFalse()
        println("MCG-SEC regola_famiglia ammessi=${allowed.size} rifiutati=${forbidden.size} banco=finto")
      }
      // Un file delle credenziali con una share fuori dalla regola non apre nemmeno l'area.
      for ((share, share2) in listOf("NAS" to "test-comune", "test" to "Public", "test2" to "test-comune", "test" to null)) {
        assertThrows(TestAreaViolation::class.java) { FamilyArea.open(credentials(share, share2), backend, backend.binding("wifi"), null, state) }
      }
      assertThrows(TestAreaViolation::class.java) { FamilyArea.open(credentials(user = "admin"), backend, backend.binding("wifi"), null, state) }
    }
  }

  @Test
  fun ilCambioDiMassaInDueTempiStaNelGiroEPulisceSoloQuello() {
    runBlockingBounded("S9 nell'app, in due tempi", WAIT_SECONDS * 10) {
      val backend = backend()
      val runId = SecurityArea.newRunId()
      val creds = checkNotNull(
        ItCredentials.parse(Properties().apply { setProperty("user", "test"); setProperty("password", password); setProperty("share", "test"); setProperty("share2", "test-comune"); setProperty("host", "192.168.178.58") }),
      )
      val start = everything(backend, "test-comune")
      SecurityArea.open(creds, backend, backend.binding("wifi-casa"), backend.serverGuid, runId).use { area ->
        assertThat(FamilyAreaProbes.massCreate(area, 300)).isEqualTo(300)
      }
      // Un comando dopo (l'app ha fatto il suo giro): il cambio, sullo stesso giro.
      SecurityArea.open(creds, backend, backend.binding("wifi-casa"), backend.serverGuid, runId).use { area ->
        assertThat(FamilyAreaProbes.massChange(area)).isEqualTo(Triple(50, 50, 50))
        val names = backend.children("test-comune", "mcg-it/$runId/massa")
        assertThat(names.count { it.endsWith(".locked") }).isEqualTo(50)
        assertThat(names.count { it.startsWith("rinominato-") }).isEqualTo(50)
        assertThat(area.cleanup()).isEmpty()
      }
      assertThat(everything(backend, "test-comune")).isEqualTo(start)
    }
  }

  @Test
  fun ilPrimoAuthFailedFermaSenzaNuoviTentativi() {
    runBlockingBounded("AUTH_FAILED sull'area della famiglia", WAIT_SECONDS) {
      val backend = backend().apply { changePassword("test", "Un-Altra-Password-P11b") }
      open(backend, state()).use { area ->
        assertThat(runCatching { area.verify() }.exceptionOrNull()).isInstanceOf(AuthStop::class.java)
        area.manager.withShare("test", dev.pampa.mycloudgallery.core.nas.contract.Purpose.INTERACTIVE) { }
        assertThat(backend.authenticateCalls.get()).isEqualTo(1)
      }
    }
  }
}
