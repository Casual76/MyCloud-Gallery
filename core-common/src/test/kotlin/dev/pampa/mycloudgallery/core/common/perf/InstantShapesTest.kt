package dev.pampa.mycloudgallery.core.common.perf

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * Avvio istantaneo (28/10): le forme delle pagine (solo numeri, codici fissi) e il cancello "dopo il primo disegno".
 *
 * Le forme si ricordano in memoria, si scrivono con `StateFiles` e si rileggono; un codice o dei numeri fuori dai limiti si ignorano, una riga
 * rovinata non rompe le altre, e dopo un azzeramento (la cartella `replica/` non c'e') non si ricrea niente.
 */
class InstantShapesTest {

  private val dir: File = Files.createTempDirectory("shapes").toFile()
  private val replica = File(dir, "replica").apply { mkdirs() }
  private val file = File(replica, InstantShapes.FILE_NAME)

  @After
  fun cleanUp() {
    InstantShapes.clear()
    dir.deleteRecursively()
  }

  @Test
  fun leFormeSiRicordanoSiScrivonoESiRileggono() {
    InstantShapes.install(file)
    InstantShapes.put("raccolte", listOf(1, 0, 0, 2, 4, 0))
    InstantShapes.put("cerca", listOf(2, 3, 0))
    InstantShapes.flushNow()
    assertThat(file.readText()).isEqualTo("cerca=2,3,0\nraccolte=1,0,0,2,4,0")

    InstantShapes.clear()
    assertThat(InstantShapes["raccolte"]).isNull()
    InstantShapes.load()
    assertThat(InstantShapes.loaded).isTrue()
    assertThat(InstantShapes["raccolte"]).containsExactly(1, 0, 0, 2, 4, 0).inOrder()
    assertThat(InstantShapes["cerca"]).containsExactly(2, 3, 0).inOrder()
  }

  /** R11 (correzioni A della Fase 8): la forma di una pagina si toglie (con i dati a cui apparteneva) e il file non la porta piu'. */
  @Test
  fun r11_removeTogliLaFormaEIlFileNonLaPortaPiu() {
    InstantShapes.install(file)
    InstantShapes.put("persone", listOf(3, 6, 3))
    InstantShapes.put("cerca", listOf(2, 3, 0))
    InstantShapes.flushNow()
    assertThat(file.readText()).contains("persone=3,6,3")
    InstantShapes.remove("persone")
    assertThat(InstantShapes["persone"]).isNull()
    InstantShapes.flushNow()
    assertThat(file.readText()).isEqualTo("cerca=2,3,0")
    // Un codice che non c'e' non fa niente; l'altra forma resta.
    InstantShapes.remove("non-c-e")
    assertThat(InstantShapes["cerca"]).containsExactly(2, 3, 0).inOrder()
    InstantShapes.clear()
    InstantShapes.load()
    assertThat(InstantShapes["persone"]).isNull()
  }

  @Test
  fun codiciEFormeFuoriDaiLimitiSiIgnorano() {
    InstantShapes.install(file)
    InstantShapes.put("Album di Anna", listOf(1, 0, 0))
    InstantShapes.put("persone", List(500) { 1 })
    InstantShapes.put("../fuori", listOf(1, 0, 0))
    assertThat(InstantShapes["Album di Anna"]).isNull()
    assertThat(InstantShapes["persone"]).isNull()
    assertThat(InstantShapes["../fuori"]).isNull()
  }

  @Test
  fun unaRigaRovinataNonRompeLeAltre() {
    file.writeText("raccolte=1,0,0\nquesta riga non e' una forma\nCERCA=1\ncerca=2,x,0\npersone=3,6,3")
    InstantShapes.install(file)
    InstantShapes.load()
    assertThat(InstantShapes["raccolte"]).containsExactly(1, 0, 0).inOrder()
    assertThat(InstantShapes["persone"]).containsExactly(3, 6, 3).inOrder()
    assertThat(InstantShapes["CERCA"]).isNull()
    // I numeri che si leggono restano; "x" non e' un numero.
    assertThat(InstantShapes["cerca"]).containsExactly(2, 0).inOrder()
  }

  @Test
  fun dopoLAzzeramentoNonSiRicreaLaCartella() {
    InstantShapes.install(file)
    replica.deleteRecursively()
    InstantShapes.put("raccolte", listOf(1, 0, 0))
    InstantShapes.flushNow()
    assertThat(replica.exists()).isFalse()
  }

  @Test
  fun ilCancelloFaPartireIlLavoroSoloDopoLApertura() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val started = kotlinx.coroutines.CompletableDeferred<Unit>()
      val job = AfterFirstDraw.launchAfter(scope) { started.complete(Unit) }
      if (!AfterFirstDraw.isOpen) assertThat(started.isCompleted).isFalse()
      AfterFirstDraw.open()
      assertThat(AfterFirstDraw.isOpen).isTrue()
      job.join()
      assertThat(started.isCompleted).isTrue()
      // Aperto una volta, resta aperto: chi arriva dopo parte subito.
      AfterFirstDraw.launchAfter(scope) {}.join()
      AfterFirstDraw.await()
    } finally {
      scope.cancel()
    }
  }
}
