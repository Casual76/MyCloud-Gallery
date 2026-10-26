package dev.pampa.mycloudgallery.core.albums.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * UN COLPO A OGNI CHIAMATA (docs/prova-500.md Parte E, §E2). I giri casuali dei banchi grandi campionano le chiamate; qui, invece, e' esaustivo: un mondo
 * scelto dal seme, UN SOLO passo (condividere un album nuovo: spostamenti, caricamento dal telefono, pubblicazione, completamento), e per **ogni punto**
 * (porte, porta di completamento, diario della condivisione, e sul simulatore SMB anche il diario di `core-nasops` e ogni chiamata SMB) e per **ogni
 * chiamata** di quel passo (le misura un giro senza colpi) un giro con UN colpo: la morte del processo prima e dopo, la connessione caduta prima e dopo,
 * la sessione scaduta, il socket fermo, lo spazio finito dove ha senso. Dopo ogni giro tutti gli invarianti del §5.5, come negli altri banchi (dopo
 * la morte, a fine passo, nella rilettura finale).
 *
 * I mondi si scelgono guardando le chiamate del giro senza colpi: uno con un caricamento dal telefono, uno con il piu' lavoro sul NAS. Con
 * `MCG_SHARE_SWEEP_WORLDS` se ne sceglie un altro numero (di norma 2 sul NAS finto, 2 sul simulatore). Il rapporto va in `build/chaos/share-sweep-report-*.txt`.
 */
class ShareChaosSweepTest {

  /** Un mondo: l'indice e il seme del giro, le chiamate che il suo passo fa a ogni punto (senza colpi) e come si chiama il passo. */
  private class World(val index: Int, val seed: Long, val calls: Map<Surface, Int>, val step: String) {
    override fun toString(): String = "mondo $index (seme $seed): $step"
  }

  private fun config(caps: Capabilities, blow: Blow?): ShareChaosConfig = ShareChaosConfig(
    caps,
    minSteps = 1,
    maxSteps = 1,
    hazardPercent = 0,
    scriptedBlows = { _ -> Blows(listOfNotNull(blow), emptyList()) },
  )

  private suspend fun probe(factory: ChaosTargetFactory, index: Int): World? {
    val stats = ShareChaosStats()
    val seed = ShareChaosBench.seedOf(BASE_SEED, index)
    val report = ShareChaosRound(index, seed, factory, config(factory.capabilities, null), stats).run()
    assertWithMessage("il giro senza colpi deve essere verde\n${report.describe()}").that(report.ok).isTrue()
    val step = report.steps.firstOrNull() ?: return null
    if (!step.startsWith("condividi")) return null
    return World(index, seed, Surface.entries.associateWith { stats.callsOn(it) }, step)
  }

  /** Quali colpi si provano a ogni chiamata di un punto. */
  private fun variants(surface: Surface): List<Pair<ShareFaultKind, Moment>> = when {
    surface.deathOnly -> listOf(ShareFaultKind.KILL to Moment.BEFORE, ShareFaultKind.KILL to Moment.AFTER)
    surface == Surface.COMPLETION -> listOf(
      ShareFaultKind.KILL to Moment.BEFORE, ShareFaultKind.KILL to Moment.AFTER, ShareFaultKind.DROP to Moment.BEFORE, ShareFaultKind.DROP to Moment.AFTER,
    )
    surface == Surface.NAS -> listOf(
      ShareFaultKind.KILL to Moment.BEFORE, ShareFaultKind.KILL to Moment.AFTER, ShareFaultKind.DROP to Moment.BEFORE, ShareFaultKind.DROP to Moment.AFTER,
      ShareFaultKind.EXPIRE_SESSION to Moment.BEFORE, ShareFaultKind.STALL to Moment.BEFORE,
    )
    surface == Surface.MOVER || surface == Surface.UPLOADER -> listOf(
      ShareFaultKind.KILL to Moment.BEFORE, ShareFaultKind.KILL to Moment.AFTER, ShareFaultKind.DROP to Moment.BEFORE, ShareFaultKind.DROP to Moment.AFTER,
      ShareFaultKind.EXPIRE_SESSION to Moment.BEFORE, ShareFaultKind.STALL to Moment.BEFORE, ShareFaultKind.NO_SPACE to Moment.BEFORE,
    )
    else -> listOf(
      ShareFaultKind.KILL to Moment.BEFORE, ShareFaultKind.KILL to Moment.AFTER, ShareFaultKind.DROP to Moment.BEFORE, ShareFaultKind.DROP to Moment.AFTER,
      ShareFaultKind.EXPIRE_SESSION to Moment.BEFORE, ShareFaultKind.STALL to Moment.BEFORE,
    )
  }

  private suspend fun chooseWorlds(factory: ChaosTargetFactory, wanted: Int): List<World> {
    val caps = factory.capabilities
    val candidates = (0 until CANDIDATES).mapNotNull { probe(factory, it) }.filter { (it.calls[Surface.MOVER] ?: 0) > 0 }
    val chosen = ArrayList<World>()
    // Il primo con un caricamento dal telefono (E-SH1) e uno spostamento; poi quello con piu' lavoro sul NAS; poi il primo senza caricamento.
    candidates.firstOrNull { (it.calls[Surface.UPLOADER] ?: 0) > 0 }?.let { chosen += it }
    fun work(w: World): Int = if (caps.nasCalls) w.calls[Surface.NAS] ?: 0 else (w.calls[Surface.FACTS] ?: 0) + (w.calls[Surface.PUBLISHER] ?: 0)
    candidates.filter { it !in chosen }.maxByOrNull { work(it) }?.let { chosen += it }
    candidates.firstOrNull { it !in chosen && (it.calls[Surface.UPLOADER] ?: 0) == 0 }?.let { chosen += it }
    return chosen.take(wanted)
  }

  private suspend fun sweep(label: String, factory: ChaosTargetFactory, wantedWorlds: Int, reportFile: String) {
    val worlds = chooseWorlds(factory, wantedWorlds)
    assertThat(worlds).isNotEmpty()
    val stats = ShareChaosStats()
    val failures = ArrayList<String>()
    val started = System.nanoTime()
    var rounds = 0
    var neverFired = 0
    val perSurface = LinkedHashMap<Surface, Int>()
    val text = StringBuilder()
    text.appendLine("=== Un colpo a ogni chiamata: $label ===")
    for (world in worlds) {
      text.appendLine(world.toString())
      text.appendLine("  chiamate del passo senza colpi: " + Surface.entries.filter { (world.calls[it] ?: 0) > 0 }.joinToString { "${it.label} ${world.calls[it]}" })
      for (surface in Surface.entries) {
        val calls = world.calls[surface] ?: 0
        if (calls == 0) continue
        for (n in 1..calls) {
          for ((kind, moment) in variants(surface)) {
            val blow = Blow(surface, n, moment, kind)
            val firedBefore = stats.totalFired
            val report = ShareChaosRound(world.index, world.seed, factory, config(factory.capabilities, blow), stats).run()
            rounds++
            perSurface.merge(surface, 1, Int::plus)
            if (stats.totalFired == firedBefore) neverFired++
            if (!report.ok) failures += "$world, colpo: $blow\n${report.describe()}"
          }
        }
      }
    }
    text.appendLine("giri: $rounds; colpi scattati: ${stats.totalFired} (morti del processo ${stats.totalDeaths}); giri in cui il colpo non e' scattato: $neverFired; giri con violazioni: ${failures.size}")
    text.appendLine("giri per punto: " + perSurface.entries.joinToString { "${it.key.label} ${it.value}" })
    text.appendLine("durata: ${(System.nanoTime() - started) / 1_000_000_000} s")
    println(text)
    File("build/chaos").mkdirs()
    File("build/chaos/$reportFile").writeText(text.toString() + if (failures.isNotEmpty()) "\n" + failures.take(20).joinToString("\n") else "")
    assertWithMessage("giri con violazioni:\n" + failures.take(5).joinToString("\n")).that(failures).isEmpty()
    // Un banco che non colpisce non prova niente: almeno nove colpi su dieci devono scattare (un colpo "dopo" su una chiamata che il colpo precedente ha fatto sparire non conta).
    assertThat(neverFired).isAtMost(rounds / 10)
    assertThat(stats.totalDeaths).isAtLeast(rounds / 3)
  }

  private fun worlds(): Int = System.getenv("MCG_SHARE_SWEEP_WORLDS")?.toIntOrNull()?.coerceIn(1, 3) ?: 2

  @Test
  fun ilNasFintoRegge_unColpoAOgniChiamataDelPasso() = runBlocking {
    sweep("NAS finto in memoria", FakeChaosTargets, worlds(), "share-sweep-report-fake.txt")
  }

  @Test
  fun ilSimulatoreSmbRegge_unColpoAOgniChiamataDelPasso() = runBlocking {
    sweep("simulatore SMB", SimulatedChaosTargets, worlds(), "share-sweep-report-smb.txt")
  }

  private companion object {
    const val BASE_SEED = 20261028L
    const val CANDIDATES = 60
  }
}
