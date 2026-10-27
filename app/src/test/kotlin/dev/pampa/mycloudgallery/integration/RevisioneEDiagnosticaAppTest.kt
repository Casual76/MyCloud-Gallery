package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.albums.AlbumsDiagnostics
import dev.pampa.mycloudgallery.albums.AlbumsGraph
import dev.pampa.mycloudgallery.albums.AlbumsLive
import dev.pampa.mycloudgallery.albums.AlbumsWiring
import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.albums.contract.ShareFolder
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemAction
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemRecord
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemState
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpId
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpRecord
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanHash
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanKind
import dev.pampa.mycloudgallery.core.albums.contract.ShareWait
import dev.pampa.mycloudgallery.core.albums.shared.AlbumRejections
import dev.pampa.mycloudgallery.core.albums.shared.ReplicaHold
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.diagnostics.aggregate.DiagnosticsSnapshot
import dev.pampa.mycloudgallery.feature.diagnostics.export.DiagnosticsExporter
import dev.pampa.mycloudgallery.nas.NasGraph
import dev.pampa.mycloudgallery.nas.NasLibraryDiagnostics
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fase 7, revisione E, voci 1 e 2: i codici della Diagnostica che i pacchetti A, B, C e D avevano **preparato senza collegarli**, provati sul grafo vero
 * dell'app (`AlbumsWiring`, `NasGraph`), non sui numeri messi a mano. Ogni prova e' rossa senza il collegamento (il contatore resta a zero e la riga non
 * compare):
 * - R22, `ALBUM_REGISTRY_UNREADABLE`: un registro dei trasferimenti che non si legge (R9);
 * - R22, `ALBUM_CONTRIB_HELD`: un contributo tenuto da parte (R2);
 * - R22, `SHARE_PAUSED_CATALOG`: l'elemento che aspetta il catalogo (la porta di completamento, I2);
 * - DG-3: il rifiuto per motivo, durevole, fino all'esportazione;
 * - R11, `NAS_WRITES_STOP_NOT_SAVED`: «Ferma le scritture» che il disco non ha salvato (L163).
 * Solo numeri e codici: nessun nome di album, file, persona o percorso.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RevisioneEDiagnosticaAppTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val backend = TestNas.backend(time)
  private val rigs = mutableListOf<BackupRig>()
  private val graphs = mutableListOf<AlbumsGraph>()
  private val devices = mutableListOf<TestDevice>()
  private val today = Today { LocalDate.of(2026, 10, 27) }

  @After
  fun tearDown() {
    graphs.forEach { runBlocking { runCatching { it.stop() } } }
    rigs.forEach { rig ->
      runBlocking { runCatching { rig.gallery.shutdown() } }
      runCatching { rig.close() }
    }
    devices.forEach { runCatching { it.close() } }
  }

  private suspend fun phoneOnly(): TestDevice {
    val phone = TestDevice(app, File(temp.root, "telefono"), TestPlatform(time, backend), FeatureFlags.forBuild(debugBuild = true, nasLibrary = true)).also { devices += it }
    phone.graph.start()
    phone.graph.foundFamily()
    return phone
  }

  private suspend fun opened(device: TestDevice): Triple<BackupRig, AlbumsGraph, AlbumsDiagnostics> {
    val rig = BackupRig(app, device, TestNas.ADMIN_USER).also { rigs += it }
    rig.open()
    device.graph.trustGuard.preflight(Purpose.BACKGROUND)
    val albums = AlbumsWiring.build(device.context, device.graph, rig.gallery, search = null, today = today).also { graphs += it }
    checkNotNull(albums.ensureLive()) { "album non montati" }
    return Triple(rig, albums, AlbumsDiagnostics(albums))
  }

  private fun live(albums: AlbumsGraph): AlbumsLive = checkNotNull(albums.live.value)

  private val album = AlbumId.derived(AccountId.of("test"), ByteArray(16) { 3 })

  // --- R22 · ALBUM_REGISTRY_UNREADABLE (R9) ----------------------------------------------------------------------------------------------

  @Test
  fun unRegistroDeiTrasferimentiCheNonSiLeggeArrivaInDiagnostica() = runBlocking<Unit> {
    val phone = phoneOnly()
    // Il registro dei piani dei trasferimenti c'e' ma non si decodifica (formato rovinato): vale "non so", non "vuoto" (R9).
    val plans = File(phone.context.noBackupFilesDir, "albums/transfer-plans")
    plans.parentFile!!.mkdirs()
    plans.writeBytes(ByteArray(40) { 0x7F })
    val (_, albums, diagnostics) = opened(phone)

    // Il giro dell'indice pota i registri leggendoli: il file illeggibile lancia "non so" e il contatore sale.
    runCatching { live(albums).shared.pruneTransfers() }
    val facts = diagnostics.facts()
    assertWithMessage("registri illeggibili contati").that(facts.unreadableRegisters).isAtLeast(1)
    assertThat(plans.readBytes()).isEqualTo(ByteArray(40) { 0x7F })
    val item = diagnostics.render(facts).single { it.code.id == "ALBUM_REGISTRY_UNREADABLE" }
    assertThat((item.export["count"] as ExportValue.Num).value).isAtLeast(1)
  }

  // --- R22 · ALBUM_CONTRIB_HELD (R2) -----------------------------------------------------------------------------------------------------

  @Test
  fun iContributiTenutiDaParteArrivanoInDiagnosticaEDuranoOltreIlGiro() = runBlocking<Unit> {
    val phone = phoneOnly()
    val (_, albums, diagnostics) = opened(phone)
    assertThat(diagnostics.facts().heldContributions).isEqualTo(0)
    // La replica tiene da parte un contributo (un segmento sparito, un file che il NAS nega): l'attesa e' una riga durevole nel database.
    val store = live(albums).shared.replicaStore
    store.setHold(album, "dispositivo-a", ReplicaHold(HeadMark(2, "ab12"), retryAt = 9_999_999_999_999L, attempts = 1))
    store.setHold(album, "dispositivo-b", ReplicaHold(null, retryAt = 9_999_999_999_999L, attempts = 2))
    val facts = diagnostics.facts()
    assertThat(facts.heldContributions).isEqualTo(2)
    val item = diagnostics.render(facts).single { it.code.id == "ALBUM_CONTRIB_HELD" }
    assertThat(item.export["count"]).isEqualTo(ExportValue.Num(2))
    // Quando l'attesa finisce (il contributo si legge) la riga sparisce.
    store.setHold(album, "dispositivo-a", null)
    store.setHold(album, "dispositivo-b", null)
    assertThat(diagnostics.facts().heldContributions).isEqualTo(0)
  }

  // --- R22 · SHARE_PAUSED_CATALOG (I2) ---------------------------------------------------------------------------------------------------

  @Test
  fun lAttesaDelCatalogoDellaPortaDiCompletamentoArrivaInDiagnostica() = runBlocking<Unit> {
    val phone = phoneOnly()
    // Due media veri nel catalogo (l'elemento del diario ha una chiave esterna sul media).
    listOf("IMG_A.jpg", "IMG_B.jpg").forEachIndexed { n, name ->
      backend.put(TestNas.ADMIN_USER, "Foto/Prova/$name", BackupRig.jpeg(tone = 30 + n * 40) + ByteArray(120 + 17 * n) { (n * 29 + it).toByte() }, lastWriteMs = time.now() - 2 * DAY)
    }
    val (rig, albums, diagnostics) = opened(phone)
    val view = withTimeout(Attese.TETTO) { rig.gallery.repository.view(LibraryFilter.ALL).first { it.size == 2 } }
    val ids = (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }
    assertThat(diagnostics.facts().pausedCatalog).isNull()
    val diary = live(albums).shared.diary
    val op = ShareOpRecord(
      id = ShareOpId("op-e-1"), album = album, kind = SharePlanKind.SHARE, planHash = SharePlanHash("%064x".format(11)), title = "Prova", level = ShareLevel.ADD,
      nonceHex = "00".repeat(16), folder = ShareFolder("test-comune", SharePath.of("Album condivisi", "Prova")), hidden = HiddenChoice.LEAVE_OUT,
    )
    val waiting = ShareItemRecord(op.id, 0, ids[0], ShareItemAction.MOVE_IN, ShareItemState.TRANSFERRING, mine = true, size = 100, wait = ShareWait.CATALOG)
    val other = ShareItemRecord(op.id, 1, ids[1], ShareItemAction.MOVE_IN, ShareItemState.TRANSFERRING, mine = true, size = 100, wait = ShareWait.NO_SPACE)
    diary.create(op, listOf(waiting, other))
    val facts = diagnostics.facts()
    assertThat(facts.pausedCatalog).isEqualTo(1)
    assertThat(facts.pausedSpace).isEqualTo(1)
    val item = diagnostics.render(facts).single { it.code.id == "SHARE_PAUSED_CATALOG" }
    assertThat(item.export["count"]).isEqualTo(ExportValue.Num(1))
  }

  // --- DG-3 · i rifiuti per motivo, fino all'esportazione --------------------------------------------------------------------------------

  @Test
  fun iRifiutiPerMotivoDuranoEArrivanoNellEsportazione() = runBlocking<Unit> {
    val phone = phoneOnly()
    val (_, albums, diagnostics) = opened(phone)
    val store = live(albums).shared.replicaStore
    store.setRejectedHead(album, "dispositivo-a", HeadMark(2, "ab12"), AlbumRejections.RETRODATED)
    store.setRejectedHead(album, "dispositivo-b", HeadMark(1, "cd34"), "BAD_SIGNATURE")
    store.setRejectedHead(album, "dispositivo-c", HeadMark(4, "ef56"), AlbumRejections.RETRODATED)
    // Nessun giro e' passato: il motivo viene dai segni durevoli, non dalla memoria dell'ultimo giro (prima: solo "quanti", e solo nel giro che lo vedeva).
    val facts = diagnostics.facts()
    assertThat(facts.rejected).isEqualTo(3)
    assertThat(facts.rejectedByReason).containsExactly(AlbumRejections.RETRODATED, 2, "BAD_SIGNATURE", 1)

    val items = diagnostics.render(facts)
    val rejected = items.single { it.code.id == "ALBUM_CONTRIB_REJECTED" }
    assertThat(rejected.export["count"]).isEqualTo(ExportValue.Num(3))
    assertThat(rejected.export["reason.RETRODATED"]).isEqualTo(ExportValue.Num(2))
    assertThat(rejected.export["reason.BAD_SIGNATURE"]).isEqualTo(ExportValue.Num(1))

    // L'esportazione scarta le chiavi fuori dall'elenco chiuso: i motivi ci stanno (famiglia `reason.`) e sono solo numeri.
    val json = JSONObject(DiagnosticsExporter.render(DiagnosticsSnapshot(items.groupBy { it.section }, recentEvents = 0)).text)
    val exported = json.getJSONObject("sections").getJSONArray(DiagSection.ALBUMS.name)
    val values = (0 until exported.length()).map { exported.getJSONObject(it) }.single { it.getString("code") == "ALBUM_CONTRIB_REJECTED" }.getJSONObject("values")
    assertThat(values.getLong("reason.RETRODATED")).isEqualTo(2)
    assertThat(values.getLong("reason.BAD_SIGNATURE")).isEqualTo(1)
    assertThat(values.getLong("count")).isEqualTo(3)
    // Un riavvio del processo (store nuovo sullo stesso database) conserva il conto.
    assertThat(dev.pampa.mycloudgallery.albums.RoomAlbumReplicaStore(rigs.single().gallery.database).rejectedByReason())
      .containsExactly(AlbumRejections.RETRODATED, 2, "BAD_SIGNATURE", 1)
  }

  // --- R11 · NAS_WRITES_STOP_NOT_SAVED (L163) --------------------------------------------------------------------------------------------

  @Test
  fun unEmergenzaDelNasCheIlDiscoNonHaSalvatoArrivaInDiagnostica() = runBlocking<Unit> {
    val phone = phoneOnly()
    val rig = BackupRig(app, phone, TestNas.ADMIN_USER).also { rigs += it }
    rig.open()
    val nas: NasGraph = rig.nas
    val diagnostics = NasLibraryDiagnostics(nas)
    assertWithMessage("il NAS e' configurato").that(nas.runtime).isNotNull()
    assertThat(diagnostics.collect().ids()).containsNoneOf("NAS_WRITES_STOPPED", "NAS_WRITES_STOP_NOT_SAVED")

    // Il file dell'emergenza non si puo' scrivere (al suo posto c'e' una cartella piena): l'emergenza vale solo in memoria.
    val file = File(phone.context.noBackupFilesDir, NasGraph.EMERGENCY_FILE)
    file.delete()
    file.mkdirs()
    File(file, "occupato").writeText("x")
    assertThat(nas.setWritesStopped(true)).isFalse()
    val ids = diagnostics.collect().ids()
    assertThat(ids).containsAtLeast("NAS_WRITES_STOPPED", "NAS_WRITES_STOP_NOT_SAVED")
    val unsaved = diagnostics.collect().single { it.code.id == "NAS_WRITES_STOP_NOT_SAVED" }
    assertThat(unsaved.section).isEqualTo(DiagSection.NAS_LIBRARY)
    assertThat(unsaved.export).isEmpty()

    // Il disco torna a rispondere e l'emergenza si salva: la riga sparisce, quella dell'emergenza resta.
    File(file, "occupato").delete()
    file.delete()
    assertThat(nas.setWritesStopped(true)).isTrue()
    val saved = diagnostics.collect().ids()
    assertThat(saved).contains("NAS_WRITES_STOPPED")
    assertThat(saved).doesNotContain("NAS_WRITES_STOP_NOT_SAVED")
    nas.setWritesStopped(false)
    assertThat(diagnostics.collect().ids()).containsNoneOf("NAS_WRITES_STOPPED", "NAS_WRITES_STOP_NOT_SAVED")
  }

  private fun List<DiagItem>.ids() = map { it.code.id }

  private companion object {
    const val DAY = 24L * 60 * 60 * 1000
  }
}
