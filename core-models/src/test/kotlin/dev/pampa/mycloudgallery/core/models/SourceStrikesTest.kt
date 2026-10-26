package dev.pampa.mycloudgallery.core.models

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.models.contract.ModelLimits
import dev.pampa.mycloudgallery.core.models.manager.SourceStrikes
import dev.pampa.mycloudgallery.core.models.support.TestModels
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.hours
import org.junit.Test

/** E-A2: "dopo due errori della stessa sorgente in un giorno la sorgente si salta fino al giorno dopo". L'ora e' quella del NAS. */
class SourceStrikesTest {
  private val now = AtomicLong(1_700_000_000_000L)
  private val file = File(TestModels.tempDir(), "meta/strikes")
  private val key = "nas:abcdefghjkmnpqrstvwxyz0123:embeddinggemma2-270m-text"

  /** L'ora del NAS; `null` = non si sa (R10: fuori casa senza VPN o prima del primo accesso SMB). */
  @Volatile private var nasTime: Boolean = true
  private val mono = AtomicLong(5_000L)

  private fun strikes(file: File? = this.file) =
    SourceStrikes(file, nowMillis = { if (nasTime) now.get() else null }, monotonicMillis = { mono.get() })

  @Test
  fun unSoloErroreNonBasta() {
    val s = strikes()
    assertThat(s.isQuarantined(key)).isFalse()
    s.strike(key)
    assertThat(s.isQuarantined(key)).isFalse()
    s.strike(key)
    assertThat(s.isQuarantined(key)).isTrue()
    assertThat(ModelLimits.STRIKES_BEFORE_SKIP).isEqualTo(2)
  }

  @Test
  fun ilGiornoDopoLaSorgenteTorna() {
    val s = strikes()
    s.strike(key)
    s.strike(key)
    now.addAndGet(23.hours.inWholeMilliseconds)
    assertThat(s.isQuarantined(key)).isTrue()
    now.addAndGet(2.hours.inWholeMilliseconds)
    assertThat(s.isQuarantined(key)).isFalse()
    // Dopo la scadenza si ricomincia da uno.
    s.strike(key)
    assertThat(s.isQuarantined(key)).isFalse()
  }

  @Test
  fun unOrologioCheTornaIndietroAzzeraLaVoce() {
    val s = strikes()
    s.strike(key)
    s.strike(key)
    now.addAndGet(-3.hours.inWholeMilliseconds)
    assertThat(s.isQuarantined(key)).isFalse()
  }

  @Test
  fun chiHaDatoUnFileBuonoDimenticaGliErrori() {
    val s = strikes()
    s.strike(key)
    s.clear(key)
    s.strike(key)
    assertThat(s.isQuarantined(key)).isFalse()
  }

  @Test
  fun leSorgentiSonoIndipendenti() {
    val s = strikes()
    s.strike(key)
    s.strike(key)
    assertThat(s.isQuarantined("nas:altro:embeddinggemma2-270m-text")).isFalse()
    assertThat(s.isQuarantined("https:huggingface.co:embeddinggemma2-270m-text")).isFalse()
  }

  @Test
  fun siRicordaDopoLaMorteDelProcesso() {
    strikes().apply {
      strike(key)
      strike(key)
    }
    assertThat(strikes().isQuarantined(key)).isTrue()
    now.addAndGet(25.hours.inWholeMilliseconds)
    assertThat(strikes().isQuarantined(key)).isFalse()
  }

  @Test
  fun unFileRovinatoOEnormeNonFaMale() {
    file.parentFile.mkdirs()
    file.writeText("riga senza forma\n$key x y\n$key 2\n ${"k".repeat(400)} 2 5\n$key -3 100\n")
    assertThat(strikes().isQuarantined(key)).isFalse()
    file.writeBytes(ByteArray(200_000) { 'a'.code.toByte() })
    assertThat(strikes().isQuarantined(key)).isFalse()
    // E si scrive di nuovo senza problemi.
    val s = strikes()
    s.strike(key)
    s.strike(key)
    assertThat(strikes().isQuarantined(key)).isTrue()
  }

  // --- R10: senza l'ora del NAS ---------------------------------------------------------------------------------------------------

  /** R10 (revisione di sicurezza della Fase 6): `strike` e `isQuarantined` lanciavano senza l'ora del NAS e il lavoro cadeva. */
  @Test
  fun r10_senzaOraDelNasUnErroreNonLanciaESiRicordaInMemoria() {
    nasTime = false
    val s = strikes()
    s.strike(key)
    assertThat(s.isQuarantined(key)).isFalse()
    s.strike(key)
    assertThat(s.isQuarantined(key)).isTrue()
    // Solo in memoria: un processo nuovo (e un orologio monotono che non vale dopo il riavvio) non ricorda niente, e il disco non e' scritto.
    assertThat(file.exists()).isFalse()
    assertThat(strikes().isQuarantined(key)).isFalse()
  }

  @Test
  fun r10_laQuarantenaSenzaOraDelNasScadeConLOrologioMonotono() {
    nasTime = false
    val s = strikes()
    s.strike(key)
    s.strike(key)
    mono.addAndGet(23.hours.inWholeMilliseconds)
    assertThat(s.isQuarantined(key)).isTrue()
    mono.addAndGet(2.hours.inWholeMilliseconds)
    assertThat(s.isQuarantined(key)).isFalse()
  }

  @Test
  fun r10_conGliErroriSulDiscoESenzaOraDelNasLaSorgenteRestaSaltata() {
    strikes().apply {
      strike(key)
      strike(key)
    }
    // Il giorno dopo, ma senza l'ora del NAS non si puo' dire che e' scaduto: prudente (un modello costa 157-370 MB), e la voce non si perde.
    now.addAndGet(48.hours.inWholeMilliseconds)
    nasTime = false
    val offline = strikes()
    assertThat(offline.isQuarantined(key)).isTrue()
    assertThat(offline.isQuarantined(key)).isTrue()
    // Torna l'ora del NAS: la voce e' scaduta e la sorgente si riprova.
    nasTime = true
    assertThat(offline.isQuarantined(key)).isFalse()
  }

  @Test
  fun r10_unErroreSenzaOraDelNasEUnoConLOraSommanoSoloSeNelloStessoRegistro() {
    // Un errore con l'ora e uno senza non si sommano (due registri): nessuna quarantena per un errore solo in ciascuno.
    val s = strikes()
    s.strike(key)
    nasTime = false
    s.strike(key)
    assertThat(s.isQuarantined(key)).isFalse()
  }

  @Test
  fun senzaFileVaSoloInMemoria() {
    val s = strikes(file = null)
    s.strike(key)
    s.strike(key)
    assertThat(s.isQuarantined(key)).isTrue()
    assertThat(strikes(file = null).isQuarantined(key)).isFalse()
  }
}
