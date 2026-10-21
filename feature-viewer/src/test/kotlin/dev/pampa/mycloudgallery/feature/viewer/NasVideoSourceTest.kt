package dev.pampa.mycloudgallery.feature.viewer

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.core.imaging.viewer.testing.ScriptedNasReader
import dev.pampa.mycloudgallery.core.imaging.viewer.MobileDataConsent
import dev.pampa.mycloudgallery.core.imaging.viewer.NasMediaLocation
import dev.pampa.mycloudgallery.core.imaging.viewer.NasNetwork
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.viewer.video.Media3ViewerPlayer
import dev.pampa.mycloudgallery.feature.viewer.video.NasVideoDataSource
import dev.pampa.mycloudgallery.feature.viewer.video.NasVideoGate
import dev.pampa.mycloudgallery.feature.viewer.video.NasVideoSource
import dev.pampa.mycloudgallery.feature.viewer.video.PlaybackSource
import dev.pampa.mycloudgallery.feature.viewer.video.PlaybackProblem
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Quando un video del NAS puo' partire (dati mobili, media sparito) e, soprattutto, **da dove leggono i byte i video**
 * (riga P8 del §4.4: nessun `DefaultDataSource`, con un test che lo vieta). La regola e' a tre livelli: i sorgenti non
 * nominano le sorgenti di default di Media3, ogni `ExoPlayer.Builder` riceve la nostra fabbrica, e le sole sorgenti che il
 * lettore crea sono quelle dell'elenco.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(UnstableApi::class)
class NasVideoSourceTest {

  private val nas = InMemoryNasMedia()
  private val reader = ScriptedNasReader(nas.reader)
  private val locations = HashMap<Long, NasMediaLocation?>()
  private var metered = false
  private val consent = MobileDataConsent()
  private val ref = MediaRef.Nas(locationId = 9, contentKey = 99, mime = "video/mp4")

  private fun nasVideo() = NasVideoSource(reader, { locations[it.locationId] }, link = { metered }, consent = consent)

  private fun add(size: Long = 1_234_567) {
    nas.addShare("test")
    val path = SharePath.of("Video", "gita.mp4")
    nas.putFile("test", path.value, NasContent.virtual(size, seed = 3))
    locations[9] = NasMediaLocation("test", path, size)
  }

  // --- Il cancello ----------------------------------------------------------------------------------------------------

  @Test
  fun unVideoSuCasaPuoPartireSenzaAprireIlNas() = runBlocking<Unit> {
    add()

    assertThat(nasVideo().check(ref)).isEqualTo(NasVideoGate.Ready(1_234_567))
    assertThat(reader.opens.get()).isEqualTo(0)
  }

  @Test
  fun coiDatiMobiliServeLaConfermaPerQuelVideoESoloPerLui_EV2() = runBlocking<Unit> {
    add()
    metered = true
    val video = nasVideo()

    assertThat(video.check(ref)).isEqualTo(NasVideoGate.NeedsMobileData(1_234_567))

    consent.confirm(ref)
    assertThat(video.check(ref)).isEqualTo(NasVideoGate.Ready(1_234_567))
    locations[10] = NasMediaLocation("test", SharePath.of("Video", "altro.mp4"), 99)
    assertThat(video.check(MediaRef.Nas(10, 100, "video/mp4"))).isEqualTo(NasVideoGate.NeedsMobileData(99))
    assertThat(reader.opens.get()).isEqualTo(0)
  }

  @Test
  fun unVideoSparitoDalCatalogoEMissing_EV6() = runBlocking<Unit> {
    assertThat(nasVideo().check(ref)).isEqualTo(NasVideoGate.Missing)
  }

  /**
   * D-15 delle prove sul campo: con la VPN spenta fuori casa il NAS non si raggiunge, e proporre i dati mobili sarebbe dire il falso. Il cancello guarda
   * prima il percorso (nessuna conferma lo supera), poi il consumo; ogni volta con la rete di adesso.
   */
  @Test
  fun d15_ilCancelloSegueLaReteDiAdessoEPrimaVieneIlPercorso() = runBlocking<Unit> {
    add()
    val link = ChangingLink(NasNetwork.VPN_OFF)
    val video = NasVideoSource(reader, { locations[it.locationId] }, link = link, consent = consent)

    assertThat(video.check(ref)).isEqualTo(NasVideoGate.Unreachable(UnavailableReason.VPN_OFF_AWAY))
    // Nemmeno la conferma dei dati mobili lo supera: il NAS non si raggiunge.
    consent.confirm(ref)
    assertThat(video.check(ref)).isEqualTo(NasVideoGate.Unreachable(UnavailableReason.VPN_OFF_AWAY))
    link.value = NasNetwork.OFFLINE
    assertThat(video.check(ref)).isEqualTo(NasVideoGate.Unreachable(UnavailableReason.OFFLINE))
    link.value = NasNetwork.NOT_ALLOWED
    assertThat(video.check(ref)).isEqualTo(NasVideoGate.Unreachable(UnavailableReason.NOT_ALLOWED_HERE))
    // La VPN si accende sui dati mobili: ora il percorso c'e', e vale la conferma data.
    link.value = NasNetwork.METERED
    assertThat(video.check(ref)).isEqualTo(NasVideoGate.Ready(1_234_567))
    consent.reset()
    assertThat(video.check(ref)).isEqualTo(NasVideoGate.NeedsMobileData(1_234_567))
    // Torna il Wi-Fi: libero, senza conferma.
    link.value = NasNetwork.OPEN
    assertThat(video.check(ref)).isEqualTo(NasVideoGate.Ready(1_234_567))
    assertThat(reader.opens.get()).isEqualTo(0)
  }

  @Test
  fun laSorgenteDelLettoreNonHaPercorsiEHaLEstensioneGiusta() {
    val video = nasVideo()

    val mp4 = video.playbackSource(MediaRef.Nas(9, 1, "video/mp4"))
    val mov = video.playbackSource(MediaRef.Nas(10, 1, "video/quicktime"))
    val unknown = video.playbackSource(MediaRef.Nas(11, 1, null))

    assertThat(mp4.uri.toString()).isEqualTo("nasmedia://v/9.mp4")
    assertThat(mov.uri.toString()).isEqualTo("nasmedia://v/10.mov")
    assertThat(unknown.uri.toString()).isEqualTo("nasmedia://v/11")
    assertThat(mp4.toString()).doesNotContain("9")
  }

  // --- Da dove leggono i byte i video ----------------------------------------------------------------------------------

  @Test
  fun ilLettoreCreaSoloLeSorgentiDellElenco() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val player = Media3ViewerPlayer(context, nasVideo())
    val uri = android.net.Uri.parse("content://media/external_primary/1")
    val allowed = setOf(ContentDataSource::class.java.name, "dev.pampa.mycloudgallery.feature.viewer.video.RangeDataSource", NasVideoDataSource::class.java.name)

    val sources = listOf(
      PlaybackSource.Whole(uri),
      PlaybackSource.Range(uri, 10, 100),
      nasVideo().playbackSource(ref),
    )
    val created = sources.map { source -> player.dataSourceFactoryFor(source)!!.createDataSource().javaClass.name }

    assertThat(created).containsExactly(
      ContentDataSource::class.java.name,
      "dev.pampa.mycloudgallery.feature.viewer.video.RangeDataSource",
      NasVideoDataSource::class.java.name,
    ).inOrder()
    assertThat(allowed).containsAtLeastElementsIn(created)
    assertThat(created.none { "DefaultDataSource" in it || "Http" in it }).isTrue()
    player.release()
  }

  @Test
  fun senzaIlCollegamentoAlNasUnVideoDelNasNonHaNessunaSorgente() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val player = Media3ViewerPlayer(context)

    assertThat(player.dataSourceFactoryFor(nasVideo().playbackSource(ref))).isNull()

    // E il lettore lo dice invece di restare muto: "il NAS non risponde".
    player.load(nasVideo().playbackSource(ref), autoplay = true, muted = false)
    assertThat(player.state.value.problem).isEqualTo(PlaybackProblem.NAS_UNREACHABLE)
    player.release()
  }

  @Test
  fun iSorgentiDiProduzioneNonNominanoLeSorgentiDiDefaultDiMedia3() {
    val forbidden = listOf(
      Regex("""\bDefaultDataSource\b"""),
      Regex("""\bDefaultMediaSourceFactory\b"""),
      Regex("""\bDefaultHttpDataSource\b"""),
      Regex("""\bHttpDataSource\b"""),
      Regex("""\b(OkHttpDataSource|CronetDataSource)\b"""),
    )
    val offenders = mutableListOf<String>()
    File("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
      val code = withoutComments(file.readText())
      code.lineSequence().forEachIndexed { index, line ->
        if (forbidden.any { it.containsMatchIn(line) }) offenders += "${file.name}:${index + 1}"
      }
    }

    assertThat(offenders).isEmpty()
  }

  @Test
  fun ogniExoPlayerBuilderRiceveLaFabbricaDelleSorgentiNostra() {
    // `ExoPlayer.Builder(context)` da solo costruirebbe un DefaultMediaSourceFactory, e con lui un DefaultDataSource.
    val singleArgument = Regex("""ExoPlayer\.Builder\(\s*[^,()]+\)""")
    val offenders = mutableListOf<String>()
    var builders = 0
    File("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
      val code = withoutComments(file.readText())
      builders += Regex("""ExoPlayer\.Builder\(""").findAll(code).count()
      singleArgument.findAll(code).forEach { offenders += file.name }
    }

    assertThat(builders).isAtLeast(1)
    assertThat(offenders).isEmpty()
  }

  @Test
  fun ilControlloDelleSorgentiVedeUnaViolazioneSeCeNe() {
    // Il test che vieta DefaultDataSource deve poter fallire: la regola e' la stessa di `NoNetworkSources`.
    val bad = "val s = androidx.media3.datasource.DefaultDataSource.Factory(context)"
    val violations = NoNetworkSources.FORBIDDEN.filter { it.pattern.containsMatchIn(bad) }

    assertThat(violations.map { it.why }.single()).contains("DefaultDataSource")
  }

  /** Toglie `//` e `/* */` (anche KDoc) rispettando le stringhe, lasciando le righe al loro posto. */
  private fun withoutComments(source: String): String {
    val out = StringBuilder(source.length)
    var i = 0
    var inString = false
    var block = 0
    var line = false
    while (i < source.length) {
      val c = source[i]
      val next = if (i + 1 < source.length) source[i + 1] else ' '
      when {
        line -> if (c == '\n') { line = false; out.append(c) }
        block > 0 -> when {
          c == '/' && next == '*' -> { block++; i++ }
          c == '*' && next == '/' -> { block--; i++ }
          c == '\n' -> out.append(c)
        }
        inString -> { out.append(c); if (c == '\\') { out.append(next); i++ } else if (c == '"') inString = false }
        c == '/' && next == '/' -> { line = true; i++ }
        c == '/' && next == '*' -> { block = 1; i++ }
        c == '"' -> { inString = true; out.append(c) }
        else -> out.append(c)
      }
      i++
    }
    return out.toString()
  }
}
