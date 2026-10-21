package dev.pampa.mycloudgallery.feature.viewer.video

import android.app.Application
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.core.imaging.viewer.NasNetwork
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.feature.viewer.MotionTestFiles
import dev.pampa.mycloudgallery.feature.viewer.motion.MotionNasRig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * La parte video delle foto in movimento solo-NAS (D51, E-V15): coda letta a intervalli, tetto di 16 MB per la lettura automatica, nessuna
 * lettura sui dati mobili o senza rete, cache con il tetto, traffico contato.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NasMotionSourceTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private val video = MotionTestFiles.mp4(durationMs = 4_000L)
  private val photo = MotionTestFiles.google(video, declaredUs = 1_500_000L)

  private fun TestScope.rig(cap: Long = MotionCache.DEFAULT_CAP_BYTES) = MotionNasRig(tmp.root, testScheduler, cap)

  @Test
  fun conLaReteApertaLaParteVideoArrivaInCacheLettaAIntervalli() = runTest {
    val rig = rig()
    val ref = rig.add(1, photo)

    val result = rig.source.fetch(ref, NasMotionSource.AUTOMATIC_MAX_BYTES, automatic = true) as NasMotionResult.Ready

    assertThat(result.fromCache).isFalse()
    assertThat(result.clip.file.readBytes()).isEqualTo(video)
    assertThat(result.clip.stillUs).isEqualTo(1_500_000L)
    assertThat(result.clip.durationUs).isEqualTo(4_000_000L)
    assertThat(result.clip.found.range.length).isEqualTo(video.size.toLong())
    // Un'apertura sola; testa e coda (qui il file e' piu' corto dei tetti: due letture da tutto il file) e i byte del video.
    assertThat(rig.reader.opens.get()).isEqualTo(1)
    // Il traffico e' quello letto davvero: testa e coda, poche letture da 16 a 32 byte per la durata, i byte del video.
    val traffic = rig.traffic.bytes
    assertThat(traffic).isAtLeast(2L * photo.size + video.size)
    assertThat(traffic).isAtMost(2L * photo.size + video.size + SMALL_READS)
    assertThat(rig.traffic.meteredBytes).isEqualTo(0L)
    assertThat(rig.cacheFiles).hasSize(1)

    // Il secondo arrivo usa la cache: nessuna apertura, nessun traffico in piu'.
    val again = rig.source.fetch(ref, NasMotionSource.AUTOMATIC_MAX_BYTES, automatic = true) as NasMotionResult.Ready
    assertThat(again.fromCache).isTrue()
    assertThat(again.clip.stillUs).isEqualTo(1_500_000L)
    assertThat(rig.reader.opens.get()).isEqualTo(1)
    assertThat(rig.traffic.bytes).isEqualTo(traffic)
  }

  @Test
  fun oltre16MBLaLetturaAutomaticaNonCopiaIlVideo() = runTest {
    val rig = rig()
    val length = NasMotionSource.AUTOMATIC_MAX_BYTES + 1
    val head = MotionTestFiles.googleHead(length, declaredUs = 1_500_000L)
    val ref = rig.addVirtual(2, head, size = head.size + length)

    val result = rig.source.fetch(ref, NasMotionSource.AUTOMATIC_MAX_BYTES, automatic = true)

    assertThat(result).isEqualTo(NasMotionResult.TooBig(length))
    assertThat(rig.cacheFiles).isEmpty()
    // Solo testa e coda (384 KiB) e qualche lettura da pochi byte per la durata, mai il video.
    assertThat(rig.reader.bytesDelivered.get()).isAtMost(MotionLocating.HEAD_BYTES + MotionLocating.TAIL_BYTES + SMALL_READS)
  }

  @Test
  fun coiDatiMobili_NessunaLetturaEIlBadgeChiedeConferma_PoiLeggeSoloDopoIlSi() = runTest {
    val rig = rig()
    rig.link.value = NasNetwork.METERED
    val ref = rig.add(3, photo)

    // Il badge senza conferma: nessuna lettura, la dimensione per la domanda.
    val blocked = rig.source.fetch(ref, Long.MAX_VALUE, automatic = false)
    assertThat(blocked).isEqualTo(NasMotionResult.Blocked(NasVideoGate.NeedsMobileData(photo.size.toLong())))
    assertThat(rig.reader.opens.get()).isEqualTo(0)
    assertThat(rig.reader.reads.get()).isEqualTo(0)
    assertThat(rig.traffic.bytes).isEqualTo(0L)

    // Con il si dell'utente il badge legge, a consumo: il traffico lo dice.
    rig.consent.confirm(ref)
    val ready = rig.source.fetch(ref, Long.MAX_VALUE, automatic = false)
    assertThat(ready).isInstanceOf(NasMotionResult.Ready::class.java)
    assertThat(rig.traffic.meteredBytes).isEqualTo(rig.traffic.bytes)
    assertThat(rig.traffic.bytes).isGreaterThan(0L)
  }

  @Test
  fun lAnimazioneAutomaticaNonLeggeMaiSuiDatiMobili_NemmenoConfermati() = runTest {
    val rig = rig()
    rig.link.value = NasNetwork.METERED
    val ref = rig.add(4, photo)
    rig.consent.confirm(ref)

    val result = rig.source.fetch(ref, NasMotionSource.AUTOMATIC_MAX_BYTES, automatic = true)

    assertThat(result).isInstanceOf(NasMotionResult.Blocked::class.java)
    assertThat(rig.reader.opens.get()).isEqualTo(0)
    assertThat(rig.traffic.bytes).isEqualTo(0L)
  }

  @Test
  fun conLaVpnSpentaOSenzaRete_NessunaLettura() = runTest {
    val rig = rig()
    val ref = rig.add(5, photo)
    for ((network, reason) in listOf(
      NasNetwork.VPN_OFF to UnavailableReason.VPN_OFF_AWAY,
      NasNetwork.OFFLINE to UnavailableReason.OFFLINE,
      NasNetwork.NOT_ALLOWED to UnavailableReason.NOT_ALLOWED_HERE,
    )) {
      rig.link.value = network
      for (automatic in listOf(true, false)) {
        val result = rig.source.fetch(ref, Long.MAX_VALUE, automatic)
        assertThat(result).isEqualTo(NasMotionResult.Blocked(NasVideoGate.Unreachable(reason)))
      }
    }
    assertThat(rig.reader.opens.get()).isEqualTo(0)
    assertThat(rig.cacheFiles).isEmpty()
  }

  @Test
  fun unFileGiaInCacheSiUsaAncheSenzaRete() = runTest {
    val rig = rig()
    val ref = rig.add(6, photo)
    rig.source.fetch(ref, Long.MAX_VALUE, automatic = false)
    rig.link.value = NasNetwork.OFFLINE
    val opens = rig.reader.opens.get()

    val result = rig.source.fetch(ref, Long.MAX_VALUE, automatic = false) as NasMotionResult.Ready

    assertThat(result.fromCache).isTrue()
    assertThat(rig.reader.opens.get()).isEqualTo(opens)
  }

  @Test
  fun laCacheHaUnTetto_LaPiuVecchiaEsceAPrima_EAzzeraSvuotaTutto() = runTest {
    // Tre video da circa 3,2 KB con un tetto di 7 KB: ne stanno due.
    val rig = rig(cap = 7_000L)
    val refs = (1L..3L).map { rig.add(10 + it, photo) }
    for ((index, ref) in refs.withIndex()) {
      assertThat(rig.source.fetch(ref, Long.MAX_VALUE, automatic = false)).isInstanceOf(NasMotionResult.Ready::class.java)
      // L'ora dei file nel test e' scandita a mano: il piu' vecchio e' il primo.
      rig.cacheFiles.forEach { if (it.lastModified() > 100_000L * index) it.setLastModified(100_000L * (index + 1)) }
    }
    assertThat(rig.source.cachedBytes()).isAtMost(7_000L)
    assertThat(rig.cacheFiles).hasSize(2)
    assertThat(rig.cacheFiles.map { it.name }.joinToString()).doesNotContain("m11_")

    rig.source.clear()
    assertThat(rig.cacheFiles).isEmpty()
    assertThat(rig.source.cachedBytes()).isEqualTo(0L)
  }

  @Test
  fun unVideoPiuGrandeDelTettoDellaCacheNonEntra() = runTest {
    val rig = rig(cap = 1_000L)
    val ref = rig.add(20, photo)
    assertThat(rig.source.fetch(ref, Long.MAX_VALUE, automatic = false)).isEqualTo(NasMotionResult.TooBig(video.size.toLong()))
    assertThat(rig.cacheFiles).isEmpty()
  }

  @Test
  fun unNasCheNonRispondeDaFailedESenzaFileASuoMezzo() = runTest {
    val rig = rig()
    val ref = rig.add(30, photo)
    rig.reader.openError = NasError.Timeout
    assertThat(rig.source.fetch(ref, Long.MAX_VALUE, automatic = false)).isEqualTo(NasMotionResult.Failed)
    assertThat(rig.cacheFiles).isEmpty()
  }

  @Test
  fun e_M16_unaFotoCheNonHaIlVideoNonERiconosciuta() = runTest {
    val rig = rig()
    val ref = rig.add(40, MotionTestFiles.jpeg(xmp = null))
    assertThat(rig.source.fetch(ref, Long.MAX_VALUE, automatic = false)).isEqualTo(NasMotionResult.Unrecognized)
    assertThat(rig.cacheFiles).isEmpty()
  }

  @Test
  fun unMediaSparitoDalCatalogoEMissing() = runTest {
    val rig = rig()
    val ref = rig.add(50, photo)
    rig.locations[50] = null
    assertThat(rig.source.fetch(ref, Long.MAX_VALUE, automatic = false)).isEqualTo(NasMotionResult.Blocked(NasVideoGate.Missing))
  }

  @Test
  fun unFileCambiatoDalPcNonSiRiusa_ilTimbroEParteDelNome() = runTest {
    val rig = rig()
    val ref = rig.add(60, photo)
    rig.source.fetch(ref, Long.MAX_VALUE, automatic = false)
    assertThat(rig.cacheFiles).hasSize(1)
    // Il PC lo modifica: stesso media, un altro timbro. Il file vecchio non vale: si rilegge.
    val opens = rig.reader.opens.get()
    rig.locations[60] = rig.locations.getValue(60)!!.let { old ->
      dev.pampa.mycloudgallery.core.imaging.viewer.NasMediaLocation(
        old.share,
        old.path,
        old.sizeBytes,
        stamp = dev.pampa.mycloudgallery.core.model.NasStamp(old.sizeBytes, 999_999L),
      )
    }
    val result = rig.source.fetch(ref, Long.MAX_VALUE, automatic = false) as NasMotionResult.Ready
    assertThat(result.fromCache).isFalse()
    assertThat(rig.reader.opens.get()).isEqualTo(opens + 1)
  }

  private companion object {
    /** Le letture per la durata: al piu' 64 box da 16 byte e il corpo di `mvhd`. */
    const val SMALL_READS = 64L * 16L + 32L
  }
}
