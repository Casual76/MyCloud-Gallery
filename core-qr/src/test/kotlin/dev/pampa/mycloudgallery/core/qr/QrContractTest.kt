package dev.pampa.mycloudgallery.core.qr

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.qr.contract.QrBits
import dev.pampa.mycloudgallery.core.qr.noop.UnavailableQrCodec
import dev.pampa.mycloudgallery.core.qr.testing.FakeQrCodec
import org.junit.Assert.assertThrows
import org.junit.Test

class QrContractTest {

  @Test
  fun laMatriceEQuadrataEConfrontabile() {
    assertThrows(IllegalArgumentException::class.java) { QrBits(3, BooleanArray(8)) }
    val bits = BooleanArray(4) { it == 3 }
    val matrix = QrBits(2, bits)
    bits[0] = true // la matrice tiene una copia: l'array del chiamante non la cambia
    assertThat(matrix[1, 1]).isTrue()
    assertThat(matrix[0, 0]).isFalse()
    assertThat(matrix).isEqualTo(QrBits(2, BooleanArray(4) { it == 3 }))
  }

  @Test
  fun ilNullObjectNonLeggeEIlFakeRispondeComeImpostato() {
    assertThat(UnavailableQrCodec.decode(ByteArray(4), 2, 2)).isNull()
    assertThrows(UnsupportedOperationException::class.java) { UnavailableQrCodec.encode("MCG1:X") }

    val fake = FakeQrCodec(nextDecoded = "MCGK1:ABC")
    assertThat(fake.decode(ByteArray(1), 1, 1)).isEqualTo("MCGK1:ABC")
    assertThat(fake.encode("MCG1:X")).isEqualTo(fake.encode("MCG1:X"))
    assertThat(fake.encoded).containsExactly("MCG1:X", "MCG1:X")
  }
}
