package dev.pampa.mycloudgallery.graph

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.ai.TextReadingMemory
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.nasmedia.contract.FreezeAcceptance
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanDiff
import dev.pampa.mycloudgallery.core.personal.contract.TextReadingChoice
import dev.pampa.mycloudgallery.nas.EmergencyStop
import dev.pampa.mycloudgallery.nas.FileFreezeAcceptances
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * R11 della revisione di Fase 7: i punti in cui un file di stato restava aperto dopo un errore. Ogni prova fa fallire la scrittura o la cancellazione
 * (un nome occupato da una cartella non vuota, o un file che "non va via") e guarda che lo stato non torni al riavvio.
 */
class StateOpenPointsTest {

  private val dir: File = Files.createTempDirectory("stato-aperto").toFile()

  @After
  fun cleanUp() {
    dir.deleteRecursively()
  }

  private val me = AccountId.of("test")

  // --- La franchigia consumata non torna ------------------------------------------------------------------------------------

  @Test
  fun unaFranchigiaConsumataCheIlFileNonLasciaAndareNonTornaNeIlProcessoNeIlRiavvio() = runTest {
    val file = File(dir, "freeze")
    val granted = FreezeAcceptance(ScanDiff(10, 1, 2, 0), atNasMillis = 100L)
    // Il file "non va via": come un altro handle aperto, o un errore del disco.
    val stuck = FileFreezeAcceptances(file, removeFile = { false })
    stuck.save(me, "test", granted)
    assertThat(stuck.load(me, "test")).isEqualTo(granted)

    stuck.save(me, "test", null)

    // In questo processo vale come tolta...
    assertThat(stuck.load(me, "test")).isNull()
    // ...e il riavvio (un'istanza nuova sullo stesso file) non la rivede: il file e' stato svuotato.
    assertThat(FileFreezeAcceptances(file).load(me, "test")).isNull()
  }

  @Test
  fun unaFranchigiaConsumataNonFaSparireLeAltre() = runTest {
    val file = File(dir, "freeze")
    val one = FreezeAcceptance(ScanDiff(10, 1, 2, 0), atNasMillis = 100L)
    val two = FreezeAcceptance(ScanDiff(20, 0, 3, 1), atNasMillis = 200L)
    val acceptances = FileFreezeAcceptances(file, removeFile = { false })
    acceptances.save(me, "uno", one)
    acceptances.save(me, "due", two)

    acceptances.save(me, "uno", null)

    assertThat(FileFreezeAcceptances(file).load(me, "uno")).isNull()
    assertThat(FileFreezeAcceptances(file).load(me, "due")).isEqualTo(two)
  }

  // --- La lettura del testo ------------------------------------------------------------------------------------------------

  /** Il temporaneo occupato da una cartella non vuota: nessuna scrittura di [file] puo' riuscire, il file di prima c'e' ancora. */
  private fun blockWrites(file: File) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.mkdirs()
    File(tmp, "dentro").writeText("1")
  }

  @Test
  fun lettura_spegnereChePoiNonSiSalva_nonLasciaIlFileCheDiceAccesa() {
    val file = File(dir, "text-reading")
    val memory = TextReadingMemory(file, current = 1)
    memory.chooseLocally(TextReadingChoice.OnForAccount(1))
    memory.acceptNotice(1)
    assertThat(TextReadingMemory(file, current = 1).choice).isEqualTo(TextReadingChoice.OnForAccount(1))

    blockWrites(file)
    memory.chooseLocally(TextReadingChoice.Off)

    // R11: la scrittura di "spenta" e' fallita; il file di prima diceva "accesa" e al riavvio la lettura sarebbe ripartita.
    assertThat(TextReadingMemory(file, current = 1).choice).isEqualTo(TextReadingChoice.Off)
  }

  @Test
  fun lettura_azzerareChePoiNonToglieIlFile_lodice() {
    val file = File(dir, "text-reading")
    val memory = TextReadingMemory(file, current = 1)
    memory.chooseLocally(TextReadingChoice.OnForAccount(1))
    // Un nome occupato da una cartella non vuota: il file di stato non si toglie.
    file.delete()
    file.mkdirs()
    File(file, "dentro").writeText("1")

    assertThat(memory.reset()).isFalse()
    // Il file e' ancora li' e l'azzeramento lo sa; con il file tolto l'esito e' vero.
    file.deleteRecursively()
    assertThat(memory.reset()).isTrue()
  }

  // --- L'emergenza ---------------------------------------------------------------------------------------------------------

  @Test
  fun emergenza_nonSalvata_valeInMemoriaEGuariscePoiDaSola() {
    // Il "file" dell'emergenza sta sotto un nome occupato da un file: la cartella non si crea e il segno non si scrive.
    val blocker = File(dir, "blocco").also { it.writeText("x") }
    val file = File(blocker, "emergency")
    val stop = EmergencyStop(file)

    val saved = stop.set(true)

    assertThat(saved).isFalse()
    assertThat(stop.stopped).isTrue()
    assertThat(file.exists()).isFalse()

    // Il disco torna a rispondere: l'emergenza si salva da sola (le letture del cancello la riprovano ogni tanto).
    blocker.delete()
    repeat(1_100) { stop.stopped }
    assertThat(file.exists()).isTrue()
  }

  @Test
  fun emergenza_salvata_dicevero() {
    val file = File(dir, "emergency")
    val stop = EmergencyStop(file)

    assertThat(stop.set(true)).isTrue()
    assertThat(EmergencyStop(file).stopped).isTrue()
    assertThat(stop.set(false)).isTrue()
    assertThat(EmergencyStop(file).stopped).isFalse()
  }
}
