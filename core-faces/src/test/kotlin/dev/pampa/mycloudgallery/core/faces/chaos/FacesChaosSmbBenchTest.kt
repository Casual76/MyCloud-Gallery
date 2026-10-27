package dev.pampa.mycloudgallery.core.faces.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * IL BANCO SUL SIMULATORE SMB (Fase 8, P12; docs/fase8-progetto.md §5.5, riga P12 del §4.4): 600 giri a ogni `check.ps1` con la **sessione di produzione**
 * (`SmbNasSessionManager`, una per dispositivo), le **primitive vere** del NAS e il **vero `NasFacesEraser`** (`FacesNasWiring`) sopra `InMemorySmbBackend`
 * (semantica Samba: creazione esclusiva, rinomina senza sostituire, handle che il NAS tiene finche' la connessione non cade). I colpi stanno nel trasporto
 * sotto la sessione, a ogni richiesta SMB di ogni dispositivo (prima o dopo che il server l'abbia applicata) e nei salvataggi durevoli come sul NAS finto;
 * la morte del processo chiude le sue connessioni, come sul telefono (lezione di F16). Variabili: `MCG_FACES_CHAOS_SMB_ROUNDS` (600),
 * `MCG_FACES_CHAOS_SEED`, `MCG_FACES_CHAOS_FIRST_ROUND`.
 */
class FacesChaosSmbBenchTest {

  @Test
  fun seicentoGiriSulSimulatoreSmb(): Unit = runBlocking {
    val rounds = ChaosEnv.number("MCG_FACES_CHAOS_SMB_ROUNDS") ?: DEFAULT_ROUNDS
    val seed = ChaosEnv.number("MCG_FACES_CHAOS_SEED") ?: DEFAULT_SEED
    val first = (ChaosEnv.number("MCG_FACES_CHAOS_FIRST_ROUND") ?: 0L).toInt()
    val result = ChaosBench.run(rounds.toInt(), seed, SmbTarget, firstRound = first)
    result.writeReport(File("build/chaos/faces-jvm-report-smb.txt"))
    println(result.summary())
    assertWithMessage("giri con violazioni:\n" + result.failureText()).that(result.failures).isEmpty()
    assertThat(result.stats.totalFired).isGreaterThan(rounds.toInt() / 2)
  }

  private companion object {
    const val DEFAULT_ROUNDS = 600L
    const val DEFAULT_SEED = 20261030L
  }
}
