package dev.pampa.mycloudgallery.core.common.testing

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertThrows
import org.junit.Test

/** La regola dell'ora (Fase 4, §4.3): il controllo vede l'orologio da muro e lascia passare quello monotono. */
class NoWallClockSourcesTest {

  private fun withSource(content: String, block: (File) -> Unit) {
    val root = Files.createTempDirectory("noclock").toFile()
    try {
      File(root, "A.kt").writeText(content)
      block(root)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun trovaOgniFormaDellOrologioDaMuro() {
    val source = """
      package x
      val a = System.currentTimeMillis()
      val b = java.time.Instant.now()
      val c = LocalDate.now()
      val d = Clock.systemUTC()
      val e = java.util.Date()
      val f = Calendar.getInstance()
      val g = Instant::now
      val h = SystemClock.currentNetworkTimeClock()
      val i = System::currentTimeMillis
    """.trimIndent()
    withSource(source) { root ->
      val lines = NoWallClockSources.violations(root).map { it.substringAfter(":").substringBefore(":").toInt() }
      assertThat(lines).containsExactly(2, 3, 4, 5, 6, 7, 8, 9, 10)
    }
  }

  @Test
  fun lOrologioMonotonoELOraDelNasVannoBene() {
    val source = """
      package x
      // System.currentTimeMillis() e' vietato: qui lo si dice solo in un commento.
      val a = System.nanoTime()
      val b = SystemClock.elapsedRealtime()
      val c = TimeSource.Monotonic.markNow()
      val d = nasClock.nowMillis()
      val e = Instant.ofEpochMilli(d)
      val f = LocalDate.of(2024, 3, 1)
      val g = updateDate()
    """.trimIndent()
    withSource(source) { root -> assertThat(NoWallClockSources.violations(root)).isEmpty() }
  }

  @Test
  fun unaCartellaCheNonEsisteEUnErrore() {
    assertThrows(IllegalArgumentException::class.java) { NoWallClockSources.violations(File("non/esiste")) }
  }
}
