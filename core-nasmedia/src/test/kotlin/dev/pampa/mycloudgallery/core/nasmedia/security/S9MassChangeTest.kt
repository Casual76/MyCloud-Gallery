package dev.pampa.mycloudgallery.core.nasmedia.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.nasmedia.ScanHarness
import dev.pampa.mycloudgallery.core.nasmedia.contract.MassChangeRule
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanStop
import dev.pampa.mycloudgallery.core.nasmedia.contract.UserGesture
import dev.pampa.mycloudgallery.core.nasmedia.contract.headlineCount
import dev.pampa.mycloudgallery.core.nasmedia.testing.SyntheticNas
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 10 P11, voce **S9** della revisione finale (docs/fase10-progetto.md §2.11; C11, D10 della Fase 4, E-S11, F179): "una modifica di massa
 * simulata fa scattare il congelamento". Lo scanner vero sopra il NAS finto, come `tools/security/mass-change.ps1` sul NAS di prova:
 * **2.000 file**, poi una parte rinominata, una cambiata sul posto e una "cifrata" (`.locked`, byte nuovi), ognuna oltre la soglia
 * max(50, 5%) = 100. Il primo giro dopo il cambiamento **congela**: nessuna assenza segnata, nessuna scrittura automatica; il conteggio da
 * riscrivere e' quello mostrato e solo quello scongela ("Ho controllato, continua", doppia conferma).
 *
 * La seconda prova **rigioca sul NAS finto cio' che il NAS vero ha fatto** (`MCG_S9_LISTING`: l'elenco prima e dopo scritto dalla prova sul
 * NAS di prova di `core-nas`, `SecurityNasIntegrationTest`): nomi, dimensioni, date e `fileId` del NAS vero decidono quali file sono
 * rinominati, cambiati o nuovi. Senza il file la prova si salta.
 */
@RunWith(AndroidJUnit4::class)
class S9MassChangeTest {

  private lateinit var h: ScanHarness

  @After
  fun close() {
    if (::h.isInitialized) h.close()
  }

  @Test
  fun duemilaFileRinominatiCambiatiECifratiCongelanoAlPrimoGiro() = runTest {
    h = ScanHarness(writeMetadata = false)
    val paths = (0 until 2_000).map { "Massa/f%04d.jpg".format(it) }
    paths.forEachIndexed { i, p -> h.photo(p, seed = i.toLong(), size = 40_000) }
    assertThat(h.scan(ScanMode.Full).detected).isEqualTo(2_000)
    val threshold = MassChangeRule.effectiveThreshold(2_000)
    assertThat(threshold).isEqualTo(100)

    // 150 rinominati (spariti sotto il vecchio nome), 150 cambiati sul posto, 150 cifrati in `.locked`.
    paths.subList(0, 150).forEach { h.synthetic.renameFolder("test", it, it.replace("Massa/", "Massa/rinominato-")) }
    paths.subList(150, 300).forEach { h.synthetic.modifyInPlace("test", it) }
    h.synthetic.ransomware("test", paths.subList(300, 450), count = 150)
    val vanishBefore = h.store.vanishCalls

    val report = h.scan()
    val freeze = checkNotNull(report.freeze) { "il primo giro dopo il cambiamento di massa non ha congelato" }
    assertThat(report.stoppedBy).isEqualTo(ScanStop.FROZEN)
    assertWithMessage("spariti").that(freeze.diff.vanished).isAtLeast(threshold)
    assertWithMessage("non piu' media").that(freeze.diff.notMedia).isAtLeast(threshold)
    assertThat(freeze.reason).isAnyOf(FreezeReason.VANISHED, FreezeReason.NOT_MEDIA, FreezeReason.CHANGED)
    // Nessuna scrittura automatica: nessuna assenza segnata, i 2.000 restano presenti, il congelamento e' salvato.
    assertThat(h.store.vanishCalls).isEqualTo(vanishBefore)
    assertThat(h.store.vanishedTotal).isEqualTo(0)
    assertThat(h.store.countPresent(h.me, "test")).isEqualTo(2_000)
    assertThat(h.store.cursor(h.me, "test")!!.freeze).isEqualTo(freeze)

    // Un conteggio sbagliato non scongela; quello mostrato si'.
    assertThat(h.scanner.freezeState.release(UserGesture(freeze.headlineCount - 1))).isFalse()
    assertThat(h.scanner.freezeState.release(UserGesture(0))).isFalse()
    assertThat(h.scan().freeze).isNotNull()
    assertThat(h.scanner.freezeState.release(UserGesture(freeze.headlineCount))).isTrue()
    assertThat(h.scan().freeze).isNull()
    println(
      "MCG-SEC S9 file=2000 soglia=$threshold rinominati=150 cambiati=150 cifrati=150 congelato_al_primo_giro=1 " +
        "spariti=${freeze.diff.vanished} cambiati_visti=${freeze.diff.changed} non_media=${freeze.diff.notMedia} assenze_segnate=0 " +
        "scongelato_solo_con_il_conteggio_giusto=1",
    )
  }

  @Test
  fun ilCambiamentoFattoSulNasDiProvaRigiocatoCongela() = runTest {
    val file = System.getenv("MCG_S9_LISTING")?.let(::File)
    assumeTrue("MCG_S9_LISTING assente: elenco del NAS di prova non disponibile", file != null && file.isFile)
    val rows = checkNotNull(file).readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
    // fase, percorso, dimensione, data (ms, ora del NAS), fileId, tipo (jpeg|cifrato)
    val before = rows.filter { it[0] == "prima" }
    val after = rows.filter { it[0] == "dopo" }
    check(before.size >= 100 && after.isNotEmpty()) { "elenco incompleto" }
    h = ScanHarness(writeMetadata = false)
    fun content(row: List<String>) = SyntheticNas.content(row[2].toLong(), row[1].hashCode().toLong() xor row[3].toLong(), if (row[5] == "jpeg") SyntheticNas.JPEG_HEAD else ByteArray(0))
    val oldest = before.minOf { it[3].toLong() }
    // Le date del NAS vero si spostano indietro di 30 giorni rispetto all'ora del banco: tutti i file sono "stabili" (piu' di due minuti).
    val shift = h.nasNow - 30L * 86_400_000L - oldest
    for (row in before) h.nas.putFile("test", row[1], content(row), row[3].toLong() + shift)
    assertThat(h.scan(ScanMode.Full).detected).isEqualTo(before.count { it[5] == "jpeg" })

    // Il cambiamento visto dal NAS vero: stesso fileId con un altro nome = rinomina; stesso nome con dimensione o data nuove = modifica.
    val byId = before.associateBy { it[4] }
    val afterIds = after.map { it[4] }.toSet()
    for (row in after) {
      val old = byId[row[4]]
      when {
        old != null && old[1] != row[1] -> {
          h.nas.rename("test", old[1], row[1])
          if (old[2] != row[2] || old[3] != row[3]) h.nas.modify("test", row[1], content(row), row[3].toLong() + shift)
        }
        old != null && (old[2] != row[2] || old[3] != row[3]) -> h.nas.modify("test", row[1], content(row), row[3].toLong() + shift)
        old == null -> h.nas.putFile("test", row[1], content(row), row[3].toLong() + shift)
      }
    }
    for (row in before) if (row[4] !in afterIds) h.nas.delete("test", row[1])
    val report = h.scan()
    val freeze = checkNotNull(report.freeze) { "il cambiamento fatto sul NAS di prova, rigiocato, non congela" }
    assertThat(h.store.vanishedTotal).isEqualTo(0)
    println(
      "MCG-SEC S9 rigioco_nas_vero file_prima=${before.size} file_dopo=${after.size} congelato_al_primo_giro=1 " +
        "spariti=${freeze.diff.vanished} cambiati=${freeze.diff.changed} non_media=${freeze.diff.notMedia}",
    )
  }
}
