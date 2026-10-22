package dev.pampa.mycloudgallery.core.search.index

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.Float16
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Il file dei vettori a finestre (revisione A, R1 e R21): oltre 2 GiB si legge ancora (prima ogni lettura lanciava e la ricerca si
 * fermava su ogni telefono della famiglia), e chi legge non vede mai una mappa vecchia con una dimensione nuova.
 */
class VectorFileFinestreTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun record(i: Int): VectorRecord {
    val v = normalized(FloatArray(768).also { FastNoise.fill(i.toLong(), it) })
    return VectorRecord(key(i), 1, 0, null, Float16.encode(v))
  }

  /**
   * R1: un file **sparso** oltre 2 GiB (un dispositivo compromesso che ricambia i vettori a ogni giro): il record in fondo si legge e si
   * verifica. Prima `slice` mappava il file intero e lanciava `IllegalArgumentException` oltre `Int.MAX_VALUE`.
   */
  @Test
  fun r1_unFileOltre2GiBSiLeggeAncora() {
    val dir = tmp.newFolder("vettori")
    assumeTrue("Serve spazio per un file sparso di prova", dir.usableSpace > 4L * 1024 * 1024 * 1024)
    val file = File(dir, "vectors-prova-0.bin")
    val recordSize = 44L + 768 * 2 + 4
    val records = (Int.MAX_VALUE.toLong() / recordSize) + 2
    // Un file sparso lungo `records` record interi (solo l'ultimo byte scritto): niente spazio occupato sul disco.
    FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE).use { ch ->
      ch.write(ByteBuffer.wrap(byteArrayOf(0)), records * recordSize - 1)
    }
    VectorFile(file, 768).use { vf ->
      assertThat(vf.length).isGreaterThan(Int.MAX_VALUE.toLong())
      val at = vf.append(listOf(record(7))).single()
      assertThat(at).isGreaterThan(Int.MAX_VALUE.toLong())
      assertThat(vf.verify(at)).isTrue()
      assertThat(vf.read(at)!!.key).isEqualTo(key(7))
      // Un record a zero prima della fine non e' valido (CRC), ma non lancia; oltre la fine risponde null.
      assertThat(vf.verify(at - recordSize)).isFalse()
      assertThat(vf.read(at + recordSize)).isNull()
    }
  }

  /** Le finestre piccole: record letti da finestre diverse, che crescono con il file. */
  @Test
  fun leFinestreCresconoConIlFile() {
    VectorFile(File(tmp.newFolder("f"), "v.bin"), 768, windowRecords = 3).use { vf ->
      val positions = (1..10).map { vf.append(listOf(record(it))).single() }
      positions.forEachIndexed { i, at -> assertThat(vf.read(at)!!.key).isEqualTo(key(i + 1)) }
      val more = vf.append((11..20).map { record(it) })
      more.forEachIndexed { i, at -> assertThat(vf.read(at)!!.key).isEqualTo(key(i + 11)) }
      assertThat(vf.read(positions[2])!!.key).isEqualTo(key(3))
    }
  }

  /**
   * R21: chi scrive aggiunge un record alla volta e tre lettori leggono sempre l'ultimo (rimappando la finestra). Prima una lettura poteva
   * prendere la mappa vecchia e la dimensione nuova e lanciare `IllegalArgumentException` da `position`.
   */
  @Test
  fun r21_mappaEDimensioneNonSiMescolano() {
    VectorFile(File(tmp.newFolder("corsa"), "v.bin"), 768).use { vf ->
      val latest = AtomicLong(-1)
      val stop = AtomicBoolean(false)
      val failures = AtomicInteger()
      val reads = AtomicInteger()
      val started = CountDownLatch(3)
      val readers = (1..3).map {
        thread {
          started.countDown()
          while (!stop.get()) {
            val at = latest.get()
            if (at < 0) continue
            try {
              if (!vf.verify(at)) failures.incrementAndGet()
              reads.incrementAndGet()
            } catch (e: RuntimeException) {
              failures.incrementAndGet()
            }
          }
        }
      }
      started.await()
      val one = listOf(record(1))
      repeat(400) { latest.set(vf.append(one).single()) }
      stop.set(true)
      readers.forEach { it.join() }
      assertThat(reads.get()).isGreaterThan(0)
      assertThat(failures.get()).isEqualTo(0)
    }
  }
}
