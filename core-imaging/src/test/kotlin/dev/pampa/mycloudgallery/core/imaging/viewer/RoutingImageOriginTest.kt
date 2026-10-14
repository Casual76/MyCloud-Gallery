package dev.pampa.mycloudgallery.core.imaging.viewer

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.core.imaging.decode.ByteArrayImageBytes
import dev.pampa.mycloudgallery.core.imaging.decode.ImageBytes
import dev.pampa.mycloudgallery.core.model.MediaRef
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Un'origine per tipo di riferimento: telefono, NAS (anche assente) e libreria sintetica. */
class RoutingImageOriginTest {

  private val phoneRef = MediaRef.Phone("external_primary", 1, "image/jpeg")
  private val nasRef = MediaRef.Nas(5, 55, "image/jpeg")

  private class Recording(private val name: String, private val bytes: ImageBytes? = null) : ImageOrigin {
    val opened = mutableListOf<MediaRef>()
    val progress = mutableListOf<Float?>()

    override suspend fun open(ref: MediaRef): OriginResult {
      opened += ref
      return OriginResult.Missing
    }

    override suspend fun open(ref: MediaRef, onProgress: (Float?) -> Unit): OriginResult {
      onProgress(0.5f)
      return open(ref)
    }

    override fun openLocal(ref: MediaRef): ImageBytes? = bytes

    override fun declaredSize(ref: MediaRef): DeclaredSize? = if (name == "nas") DeclaredSize(40, 30) else null
  }

  @Test
  fun ogniRiferimentoVaAllaSuaOrigine() = runBlocking<Unit> {
    val phone = Recording("phone")
    val nas = Recording("nas")
    val routing = RoutingImageOrigin(phone, nas)

    routing.open(phoneRef) { }
    routing.open(nasRef) { }
    routing.open(MediaRef.Synthetic(1, 10, 10))

    assertThat(phone.opened).containsExactly(phoneRef)
    assertThat(nas.opened).containsExactly(nasRef)
  }

  @Test
  fun senzaOrigineDelNasUnMediaDelNasDiceCheIlNasNonRisponde_EL4() = runBlocking<Unit> {
    val routing = RoutingImageOrigin(Recording("phone"), nas = null)

    val result = routing.open(nasRef) as OriginResult.Unreachable

    assertThat(result.reason).isEqualTo(UnavailableReason.NAS_UNREACHABLE)
    assertThat(routing.openLocal(nasRef)).isNull()
    assertThat(routing.declaredSize(nasRef)).isNull()
  }

  @Test
  fun leRisposteSincroneEdIlProgressoSeguonoIlTipo() = runBlocking<Unit> {
    val phoneBytes = ByteArrayImageBytes(ByteArray(3))
    val nasBytes = ByteArrayImageBytes(ByteArray(7))
    val routing = RoutingImageOrigin(Recording("phone", phoneBytes), Recording("nas", nasBytes))
    val seen = mutableListOf<Float?>()

    assertThat(routing.openLocal(phoneRef)).isSameInstanceAs(phoneBytes)
    assertThat(routing.openLocal(nasRef)).isSameInstanceAs(nasBytes)
    assertThat(routing.openLocal(MediaRef.Synthetic(1, 2, 2))).isNull()
    assertThat(routing.declaredSize(nasRef)!!.width).isEqualTo(40)
    assertThat(routing.declaredSize(phoneRef)).isNull()
    routing.open(nasRef) { seen += it }
    assertThat(seen).containsExactly(0.5f)
  }
}
