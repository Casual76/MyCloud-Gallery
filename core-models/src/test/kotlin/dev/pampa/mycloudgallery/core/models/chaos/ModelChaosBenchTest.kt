package dev.pampa.mycloudgallery.core.models.chaos

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * I giri del banco dello scaricamento (§5.5). I numeri sono quelli del brief di P10: 2.000 giri sulla JVM (NAS finto con `FaultPlan`,
 * server HTTPS di prova, morte del processo a ogni salvataggio, `200` al posto di `206`, file cambiato sul NAS a meta') e 600 sul simulatore
 * SMB con la sessione e la lettura vere. `MCG_MODELS_CHAOS_ROUNDS` e `MCG_MODELS_CHAOS_SMB_ROUNDS` li cambiano per una prova piu' corta o piu' lunga;
 * il seme di base (default 6101 sulla JVM, 6102 sull'SMB) si cambia con `MCG_MODELS_CHAOS_SEED` (stessi nomi dei banchi delle fasi 4 e 5: `MCG_BACKUP_CHAOS_ROUNDS`, ...). Il rapporto va in `build/chaos/`.
 */
class ModelChaosBenchTest {
  private fun env(name: String, default: Long): Long = System.getenv(name)?.toLongOrNull() ?: default

  private fun runBench(backend: ModelBackend, rounds: Int, seed: Long, reportName: String) {
    // Con MCG_MODELS_CHAOS_FIRST_ROUND=N e un solo giro si rifa' il giro N di un rapporto rosso (stesso seme di base).
    val result = ModelChaosBench.run(rounds, seed, backend, firstRound = env("MCG_MODELS_CHAOS_FIRST_ROUND", 0).toInt())
    result.writeReport(File("build/chaos/$reportName"))
    println(result.summary())
    assertWithMessage("giri con violazioni\n${result.summary()}\n${result.failureText()}").that(result.ok).isTrue()
  }

  @Test
  fun duemilaGiriSullaJvm() {
    runBench(ModelBackend.MEMORY, env("MCG_MODELS_CHAOS_ROUNDS", 2_000).toInt(), env("MCG_MODELS_CHAOS_SEED", 6101), "modelli-jvm.txt")
  }

  @Test
  fun seicentoGiriSulSimulatoreSmb() {
    runBench(ModelBackend.SMB, env("MCG_MODELS_CHAOS_SMB_ROUNDS", 600).toInt(), env("MCG_MODELS_CHAOS_SEED", 6102), "modelli-smb.txt")
  }

  /** Lo stesso seme, lo stesso piano: un giro che fallisce si rilancia dal suo seme. */
  @Test
  fun ilPianoDelGiroEDeterminatoDalSeme() {
    for (seed in listOf(1L, 77L, 6101L, -5L)) {
      val a = ModelRoundPlan.generate(seed, ModelBackend.MEMORY)
      val b = ModelRoundPlan.generate(seed, ModelBackend.MEMORY)
      assertWithMessage("seme $seed").that(a.toString()).isEqualTo(b.toString())
      assertWithMessage("seme $seed").that(a.deathSaves).isEqualTo(b.deathSaves)
      assertWithMessage("seme $seed").that(a.cutAt).isEqualTo(b.cutAt)
    }
  }
}
