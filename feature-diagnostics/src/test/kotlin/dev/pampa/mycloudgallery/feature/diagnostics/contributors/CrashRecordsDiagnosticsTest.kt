package dev.pampa.mycloudgallery.feature.diagnostics.contributors

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.rules.platform.crash.CrashCause
import dev.pampa.mycloudgallery.core.rules.platform.crash.CrashDigest
import dev.pampa.mycloudgallery.core.rules.platform.crash.CrashKind
import dev.pampa.mycloudgallery.core.rules.platform.crash.CrashRecord
import dev.pampa.mycloudgallery.core.rules.platform.crash.ThreadKind
import dev.pampa.mycloudgallery.core.rules.platform.health.testing.FakeBackgroundHealthReader
import dev.pampa.mycloudgallery.feature.diagnostics.R
import dev.pampa.mycloudgallery.feature.diagnostics.aggregate.DiagnosticsSnapshot
import dev.pampa.mycloudgallery.feature.diagnostics.assertExportKeysAllowed
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import dev.pampa.mycloudgallery.feature.diagnostics.export.DiagnosticsExporter
import dev.pampa.mycloudgallery.feature.diagnostics.healthyHealth
import java.io.IOException
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Il registro locale dei crash nella Diagnostica (C472, OA-2 / OB-7): ogni crash recente e' una voce con quando e dove (la prima cornice nostra), e finisce
 * nell'esportazione come classi e cornici del codice. **Mai un messaggio di eccezione**, ne' nei dati ne' nel file esportato.
 */
@RunWith(RobolectricTestRunner::class)
class CrashRecordsDiagnosticsTest {

  private val now = 1_800_000_000_000L

  private fun contributor(vararg crashes: CrashRecord) = BackgroundHealthContributor(
    reader = FakeBackgroundHealthReader(healthyHealth().copy(recentCrashes = crashes.toList())),
    security = DeviceSecuritySource { true },
    nowMillis = { now },
  )

  private fun crash(
    agoMs: Long,
    frames: List<String> = listOf("dev.pampa.mycloudgallery.feature.library.LibraryViewModel\$load\$1.invokeSuspend(LibraryViewModel.kt:120)"),
    kind: CrashKind = CrashKind.CRASH,
  ) = CrashRecord(
    kind = kind,
    atMillis = now - agoMs,
    versionCode = 42,
    thread = ThreadKind.MAIN,
    mainProcess = true,
    chain = listOf(CrashCause("java.lang.IllegalStateException", frames), CrashCause("java.io.IOException", emptyList())),
  )

  private suspend fun crashItems(vararg crashes: CrashRecord): List<DiagItem> =
    contributor(*crashes).collect().filter { it.code == DiagCodes.AND_CRASH_RECORDED || it.code == DiagCodes.AND_ANR_RECORDED }

  @Test
  fun ognunoDeiCrashRecentiHaUnaVoceConQuandoEDove() = runTest {
    val items = crashItems(crash(agoMs = 3.hours.inWholeMilliseconds))

    val item = items.single()
    assertThat(item.code).isEqualTo(DiagCodes.AND_CRASH_RECORDED)
    assertThat(item.section).isEqualTo(DiagSection.ANDROID_WARNINGS)
    assertThat(item.severity).isEqualTo(DiagSeverity.ERROR)
    assertThat(item.titleRes).isEqualTo(R.string.diag_and_crash_recorded_title)
    // A schermo: "3 h fa · LibraryViewModel$load$1.invokeSuspend (LibraryViewModel.kt:120)".
    assertThat(item.detail!!.resId).isEqualTo(R.string.diagnostics_crash_detail_hours)
    assertThat(item.detail!!.args).containsExactly(3L, "LibraryViewModel\$load\$1.invokeSuspend (LibraryViewModel.kt:120)").inOrder()
    assertThat(DiagCatalog.guideOf(DiagCodes.AND_CRASH_RECORDED)).isEqualTo(R.string.diag_and_crash_recorded_guide)
  }

  @Test
  fun ilQuandoSiDiceInMinutiOreOGiorni() = runTest {
    val items = crashItems(crash(10.minutes.inWholeMilliseconds), crash(5.hours.inWholeMilliseconds), crash(3.days.inWholeMilliseconds))

    assertThat(items.map { it.detail!!.resId }).containsExactly(
      R.string.diagnostics_crash_detail_minutes,
      R.string.diagnostics_crash_detail_hours,
      R.string.diagnostics_crash_detail_days,
    ).inOrder()
    assertThat(items.map { it.detail!!.args.first() }).containsExactly(10L, 5L, 3L).inOrder()
  }

  @Test
  fun dalPiuRecenteAlMeno_AlMassimoCinque_EquelliFuoriFinestraNonCi_Sono() = runTest {
    val crashes = (1..8).map { crash(agoMs = it.hours.inWholeMilliseconds) } + crash(agoMs = 30.days.inWholeMilliseconds)

    val items = crashItems(*crashes.shuffled().toTypedArray())

    assertThat(items).hasSize(5)
    assertThat(items.map { (it.export["crashAge"] as ExportValue.Dur).millis })
      .containsExactlyElementsIn((1..5).map { it.hours.inWholeMilliseconds }).inOrder()
  }

  @Test
  fun unAnrHaIlSuoCodice() = runTest {
    val items = crashItems(crash(1.hours.inWholeMilliseconds, kind = CrashKind.ANR))

    assertThat(items.single().code).isEqualTo(DiagCodes.AND_ANR_RECORDED)
    assertThat(items.single().titleRes).isEqualTo(R.string.diag_and_anr_recorded_title)
  }

  @Test
  fun senzaCorniceNostraIlDoveEUnTrattino() = runTest {
    val item = crashItems(crash(1.hours.inWholeMilliseconds, frames = emptyList())).single()

    assertThat(item.detail!!.args.last()).isEqualTo("—")
  }

  @Test
  fun nessunCrashNessunaVoce() = runTest {
    assertThat(crashItems()).isEmpty()
  }

  @Test
  fun lEsportazioneHaLaCatenaDelleClassiELeCorniche_EChiaviSonoNellElencoChiuso() = runTest {
    val items = crashItems(crash(agoMs = 3.hours.inWholeMilliseconds))
    assertExportKeysAllowed(items)

    val export = items.single().export
    assertThat(export["crashAge"]).isEqualTo(ExportValue.Dur(3.hours.inWholeMilliseconds))
    assertThat(export["crashThread"]).isEqualTo(ExportValue.Enum.of(ThreadKind.MAIN))
    assertThat(export["crashVersion"]).isEqualTo(ExportValue.Num(42))
    assertThat(export["crashMainProcess"]).isEqualTo(ExportValue.Flag(true))
    assertThat((export["crashChain"] as ExportValue.Trace).entries).containsExactly("java.lang.IllegalStateException", "java.io.IOException").inOrder()
    assertThat((export["crashFrames0"] as ExportValue.Trace).entries)
      .containsExactly("dev.pampa.mycloudgallery.feature.library.LibraryViewModel\$load\$1.invokeSuspend(LibraryViewModel.kt:120)")
    assertThat((export["crashFrames1"] as ExportValue.Trace).entries).isEmpty()
  }

  @Test
  fun ilFileEsportatoPortaDoveSiERottoEMaiIlMessaggio() = runTest {
    // Un'eccezione vera, con un messaggio pieno di dati dell'utente, e una causa di smbj: passa da CrashDigest come nel gestore.
    val cause = IOException("STATUS_ACCESS_DENIED su \\\\NAS\\famiglia\\Foto\\Mare 2019\\IMG_0001.jpg per mario@example.com a 192.168.178.58")
    cause.stackTrace = arrayOf(
      StackTraceElement("org.example.net.share.DiskShare", "open", "DiskShare.java", 10),
      StackTraceElement("dev.pampa.mycloudgallery.core.nasmedia.NasMediaReader", "read", "NasMediaReader.kt", 77),
    )
    val error = IllegalStateException("Impossibile aprire Foto/Mare 2019/IMG_0001.jpg", cause)
    error.stackTrace = arrayOf(StackTraceElement("dev.pampa.mycloudgallery.feature.viewer.ViewerController", "open", "ViewerController.kt", 311))
    val record = CrashRecord(CrashKind.CRASH, now - 1.hours.inWholeMilliseconds, 7, ThreadKind.COROUTINE, true, CrashDigest.chain(error))

    val snapshot = DiagnosticsSnapshot(mapOf(DiagSection.ANDROID_WARNINGS to crashItems(record)), recentEvents = 0)
    val text = DiagnosticsExporter.render(snapshot).text

    assertThat(DiagnosticsExporter.conforms(text)).isTrue()
    for (secret in listOf("IMG_0001", "Mare 2019", "STATUS_ACCESS_DENIED", "mario@example.com", "192.168.178.58", "famiglia", "Impossibile aprire")) {
      assertThat(text).doesNotContain(secret)
    }
    // E' JSON vero: le classi e le cornici nostre sono elenchi di stringhe.
    val values = JSONObject(text).getJSONObject("sections").getJSONArray("ANDROID_WARNINGS").getJSONObject(0).getJSONObject("values")
    assertThat(values.getJSONArray("crashChain").toString()).contains("java.lang.IllegalStateException")
    assertThat(values.getJSONArray("crashChain").toString()).contains("java.io.IOException")
    assertThat(values.getJSONArray("crashFrames0").getString(0)).isEqualTo("dev.pampa.mycloudgallery.feature.viewer.ViewerController.open(ViewerController.kt:311)")
    assertThat(values.getJSONArray("crashFrames1").getString(0)).isEqualTo("dev.pampa.mycloudgallery.core.nasmedia.NasMediaReader.read(NasMediaReader.kt:77)")
    assertThat(values.getString("crashThread")).isEqualTo("ThreadKind.COROUTINE")
  }

  @Test
  fun unaRigaDell_EsportazioneConUnMessaggioInChiaroNonRispettaLaGrammatica() {
    val clean = "{\"code\": \"AND_CRASH_RECORDED\", \"severity\": \"ERROR\", \"values\": {\"crashChain\": [\"java.io.IOException\"], \"crashFrames0\": [\"dev.pampa.Foo.bar(Foo.kt:3)\"]}}"
    val leaking = "{\"code\": \"AND_CRASH_RECORDED\", \"severity\": \"ERROR\", \"values\": {\"crashChain\": [\"Errore su Mare 2019/IMG_0001.jpg\"]}}"
    fun file(line: String) = "{\n  \"format\": \"${DiagnosticsExporter.FORMAT}\",\n  \"version\": 1,\n  \"sections\": {\n    \"ANDROID_WARNINGS\": [\n      $line\n    ]\n  }\n}\n"

    assertThat(DiagnosticsExporter.conforms(file(clean))).isTrue()
    assertThat(DiagnosticsExporter.conforms(file(leaking))).isFalse()
  }

  @Test
  fun lAnrEIlCrashDelSistemaConvivono_LaVoceDelConteggioResta() = runTest {
    val health = healthyHealth().copy(
      lastExitReasons = listOf(dev.pampa.mycloudgallery.core.rules.platform.health.ExitRecord(dev.pampa.mycloudgallery.core.rules.platform.health.ExitReasonKind.CRASH, now - 1.hours.inWholeMilliseconds)),
      recentCrashes = listOf(crash(1.hours.inWholeMilliseconds)),
    )
    val items = BackgroundHealthContributor(FakeBackgroundHealthReader(health), DeviceSecuritySource { true }, nowMillis = { now }).collect()

    assertThat(items.map { it.code }).containsAtLeast(DiagCodes.AND_EXIT_CRASH, DiagCodes.AND_CRASH_RECORDED)
  }
}
