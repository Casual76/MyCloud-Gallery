package dev.pampa.mycloudgallery

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import java.io.IOException
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog

/**
 * Revisione 4A, I5 (correzione D della Fase 10): nella build debug il sink di logcat scrive lo stack di un'eccezione **senza i messaggi**.
 * Un messaggio di smbj puo' portare il percorso di un file del NAS: prima finiva in logcat con `Log.getStackTraceString`.
 */
@RunWith(AndroidJUnit4::class)
class AppLoggingTest {

  @Before
  fun setUp() {
    ShadowLog.clear()
    installLogging()
  }

  @After
  fun tearDown() {
    GalleryLog.uninstall()
    ShadowLog.clear()
  }

  private fun logged(tag: String): String = ShadowLog.getLogsForTag("MyCloud/$tag").joinToString("\n") { it.msg }

  @Test
  fun i5_loStackDiUnEccezioneArrivaSenzaIMessaggiConLeClassiEIPuntiDelCodice() {
    val cause = IllegalStateException("STATUS_ACCESS_DENIED su \\\\NAS\\famiglia\\Foto\\Mare 2019\\IMG_0001.jpg")
    val error = IOException("Errore SMB su Foto/Mare 2019/IMG_0001.jpg", cause)

    GalleryLog.e("Prova", error) { "tappa non riuscita" }

    val text = logged("Prova")
    assertThat(text).contains("tappa non riuscita")
    assertThat(text).contains("java.io.IOException")
    assertThat(text).contains("Causato da: java.lang.IllegalStateException")
    assertThat(text).contains("AppLoggingTest") // i punti del codice restano: si vede dove si e' rotto
    assertThat(text).doesNotContain("IMG_0001")
    assertThat(text).doesNotContain("Mare 2019")
    assertThat(text).doesNotContain("STATUS_ACCESS_DENIED")
  }

  /**
   * Prima questo test non finiva: `Log.getStackTraceString` scorre le cause cercando `UnknownHostException` senza fermarsi su una catena
   * circolare, e il thread che scriveva il log in debug restava li' per sempre. Il tetto di tempo lo dice subito, se tornasse.
   */
  @Test(timeout = 60_000)
  fun i5_unaCatenaCheSiRichiudeSuSeStessaSiScriveUnaVoltaSola() {
    val first = RuntimeException("primo con un nome: segreto.jpg")
    val second = IllegalArgumentException("secondo", first)
    first.initCause(second)

    GalleryLog.w("Ciclo", second) { "catena circolare" }

    val text = logged("Ciclo")
    assertThat(text).contains("java.lang.IllegalArgumentException")
    assertThat(text).contains("Causato da: java.lang.RuntimeException")
    assertThat(Regex("Causato da: ").findAll(text).count()).isEqualTo(1)
    assertThat(text).doesNotContain("segreto.jpg")
  }

  @Test
  fun senzaEccezioneIlMessaggioPassaComE() {
    GalleryLog.i("Semplice") { "NAS raggiungibile" }

    assertThat(logged("Semplice")).isEqualTo("NAS raggiungibile")
  }
}
