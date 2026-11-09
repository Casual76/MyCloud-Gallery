package dev.pampa.mycloudgallery.core.common.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `ExportValue.Trace` (C472, registro dei crash): un elenco di identificativi del codice. Niente testo libero neanche qui: una voce che non ha la forma di una classe o di
 * `classe.metodo(File.kt:riga)` non entra, e fa rifiutare l'elenco intero.
 */
class ExportValueTraceTest {

  @Test
  fun leClassiEIPuntiDelCodiceEntrano() {
    val trace = ExportValue.Trace.of(
      listOf(
        "java.io.IOException",
        "dev.pampa.mycloudgallery.feature.library.LibraryViewModel\$load\$1.invokeSuspend(LibraryViewModel.kt:120)",
        "dev.pampa.mycloudgallery.Foo.<init>(Foo.kt:4)",
        "dev.pampa.mycloudgallery.Foo.load-abc123",
        "Unknown",
      ),
    )

    assertThat(trace).isNotNull()
    assertThat(trace!!.entries).hasSize(5)
  }

  @Test
  fun nonEntraNienteCheSembriUnDatoDellUtente() {
    for (bad in listOf(
      "",
      "192.168.178.58",
      "/storage/emulated/0/DCIM/Camera/IMG_0001.jpg",
      "C:\\Users\\Mario\\Foto\\mare.jpg",
      "Mare 2019",
      "mario@example.com",
      "dev.pampa.Foo.bar(Mare 2019.kt:3)",
      "dev.pampa.Foo.bar(Foo.txt:3)",
      "dev.pampa.Foo.bar(Foo.kt:12345678)",
      "dev.pampa.Foo.bar\n",
      "dev.pampa.Foo.bar\"",
      "\\\\NAS\\famiglia\\Foto",
      "STATUS_ACCESS_DENIED su share",
      "x".repeat(ExportValue.Trace.MAX_ENTRY_LENGTH + 1),
    )) {
      assertThat(ExportValue.Trace.of(listOf(bad))).isNull()
      assertThat(ExportValue.Trace.isEntry(bad)).isFalse()
    }
  }

  @Test
  fun unaVoceSbagliataFaRifiutareTuttoLElenco_ETroppeVociNonPassano() {
    assertThat(ExportValue.Trace.of(listOf("java.io.IOException", "Mare 2019"))).isNull()
    assertThat(ExportValue.Trace.of(List(ExportValue.Trace.MAX_ENTRIES) { "a.B" })).isNotNull()
    assertThat(ExportValue.Trace.of(List(ExportValue.Trace.MAX_ENTRIES + 1) { "a.B" })).isNull()
    assertThat(ExportValue.Trace.of(emptyList())).isNotNull()
  }

  @Test
  fun siRilegge_DaLAnelloDegliEventi_ConLaStessaGrammatica() {
    val value = ExportValue.Trace.of(listOf("java.io.IOException", "dev.pampa.Foo.bar(Foo.kt:3)"))!!
    val event = DiagEvent(at = 1_000L, code = DiagCode("EVT_TEST_TRACE"), severity = DiagSeverity.ERROR, values = mapOf("frames" to value, "none" to ExportValue.Trace.of(emptyList())!!))

    val decoded = DiagEventCodec.decode(DiagEventCodec.encode(event))

    assertThat(decoded).isNotNull()
    assertThat(decoded!!.values["frames"]).isEqualTo(value)
    assertThat(decoded.values["none"]).isEqualTo(ExportValue.Trace.of(emptyList()))
  }

  @Test
  fun unaRigaManomessaDelLogNonDiventaUnaTrace() {
    val line = "1000|EVT_TEST_TRACE|ERROR|frames=tMario Rossi,/storage/emulated/0/DCIM"

    assertThat(DiagEventCodec.decode(line)).isNull()
  }
}
