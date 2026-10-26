package dev.pampa.mycloudgallery.core.ai.runtime

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * R9 e C22: la cache dei programmi compilati la scrive il motore e non e' sotto l'impronta del modello. Era valida "a priori"; ora lo e' solo se porta
 * il segno `.complete`, scritto dopo un'inizializzazione riuscita.
 */
class ProgramCacheTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private val cache: File by lazy { File(tmp.newFolder("cache"), "0123abcd-gpu") }

  @Test
  fun unaCacheSenzaIlSegnoDiCompletaSiToglieEIlMotoreLaRifa() {
    cache.mkdirs()
    File(cache, "programma.bin").writeBytes(ByteArray(100) { 7 })
    File(cache, "peso.bin").writeBytes(ByteArray(10))

    ProgramCache.prepare(cache)

    // Quella di un'inizializzazione interrotta potrebbe essere troncata: non resta niente, la cartella c'e' e il motore la riempie da zero.
    assertThat(cache.isDirectory).isTrue()
    assertThat(cache.listFiles().orEmpty()).isEmpty()
  }

  @Test
  fun unaCacheCompletaSiTiene() {
    cache.mkdirs()
    File(cache, "programma.bin").writeBytes(ByteArray(100) { 7 })
    ProgramCache.markComplete(cache)

    ProgramCache.prepare(cache)

    assertThat(File(cache, "programma.bin").length()).isEqualTo(100)
    assertThat(File(cache, ProgramCache.COMPLETE).exists()).isTrue()
  }

  @Test
  fun alleAperturaSenzaCacheLaCartellaSiCrea() {
    ProgramCache.prepare(cache)
    assertThat(cache.isDirectory).isTrue()
    assertThat(File(cache, ProgramCache.COMPLETE).exists()).isFalse()
  }

  @Test
  fun unaInizializzazioneFallitaTogliELaCacheEUnaRiuscitaLaMarca() {
    cache.mkdirs()
    File(cache, "programma.bin").writeBytes(ByteArray(5))
    ProgramCache.markComplete(cache)
    // Il motore ha fallito con questa cache: se era lei a rovinarlo, non deve restare.
    ProgramCache.discard(cache)
    assertThat(cache.exists()).isFalse()
    // Togliere una cache che non c'e' non fa male.
    ProgramCache.discard(cache)
    ProgramCache.markComplete(File(cache, "non/esiste/ancora"))
  }
}
