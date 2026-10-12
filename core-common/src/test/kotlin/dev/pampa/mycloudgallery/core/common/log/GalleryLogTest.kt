package dev.pampa.mycloudgallery.core.common.log

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

class GalleryLogTest {

  private class RecordingSink : LogSink {
    val lines = mutableListOf<Triple<LogLevel, String, String>>()
    override fun write(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
      lines += Triple(level, tag, message)
    }
  }

  @After
  fun tearDown() {
    GalleryLog.uninstall()
  }

  @Test
  fun senzaSink_nonScriveNienteENonCostruisceIlMessaggio() {
    GalleryLog.uninstall()
    var costruito = false

    GalleryLog.d("Test") {
      costruito = true
      "non deve essere valutato"
    }

    assertThat(GalleryLog.isEnabled).isFalse()
    assertThat(costruito).isFalse()
  }

  @Test
  fun conSink_lasciaPassareLivelloTagEMessaggio() {
    val sink = RecordingSink()
    GalleryLog.install(sink)

    GalleryLog.i("Rete") { "NAS raggiungibile" }
    GalleryLog.e("Rete") { "NAS non raggiungibile" }

    assertThat(sink.lines).containsExactly(
      Triple(LogLevel.INFO, "Rete", "NAS raggiungibile"),
      Triple(LogLevel.ERROR, "Rete", "NAS non raggiungibile"),
    ).inOrder()
  }

  @Test
  fun dopoUninstall_tornaMuto() {
    val sink = RecordingSink()
    GalleryLog.install(sink)
    GalleryLog.uninstall()

    GalleryLog.w("Test") { "ignorato" }

    assertThat(sink.lines).isEmpty()
  }

  @Test
  fun unMessaggioOUnSinkCheEsplodono_nonFannoCadereIlChiamante() {
    GalleryLog.install(LogSink { _, _, _, _ -> error("sink rotto") })
    GalleryLog.d("Test") { "va bene" }

    GalleryLog.install(RecordingSink())
    GalleryLog.d("Test") { error("messaggio rotto") }
  }
}
