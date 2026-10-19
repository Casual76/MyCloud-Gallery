package dev.pampa.mycloudgallery.core.backup.versions

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionRequest
import dev.pampa.mycloudgallery.core.backup.contract.PhoneWriteGrant
import dev.pampa.mycloudgallery.core.backup.corrections.DefaultCorrections
import dev.pampa.mycloudgallery.core.backup.testing.BackupDeath
import dev.pampa.mycloudgallery.core.backup.testing.ProcessDeath
import dev.pampa.mycloudgallery.core.backup.testing.TestConfirmations
import dev.pampa.mycloudgallery.core.metadata.testing.ChangeSamples
import dev.pampa.mycloudgallery.core.metadata.testing.FakeMetadataWriter
import dev.pampa.mycloudgallery.core.metadata.write.WrittenCheck
import dev.pampa.mycloudgallery.core.metadata.write.WrittenFileCheck
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fase 5B, P8 (§4.3 "ogni passo in piu' tappe e' idempotente e ha il test guasto a ogni chiamata"; §5.4 invarianti 1, 2, 3, 6, 8, 10, 12,
 * 13): ogni scenario del §2.4 con
 * - un **guasto prima e dopo ogni chiamata** al NAS (primitive e letture: la caduta della connessione prima che il NAS applichi
 *   l'operazione, o dopo, con la risposta persa), poi la ripresa con un processo nuovo fino in fondo;
 * - la **morte del processo prima e dopo ogni scrittura** del diario delle versioni e del catalogo, poi la ripresa dal diario.
 * Dopo ogni giro: gli invarianti a lavoro finito (nessuna versione persa ne' doppia, l'attuale al solito percorso, niente sostituito, nessun
 * temporaneo, catalogo d'accordo con il NAS) e l'esito dello scenario.
 */
class VersionEveryCallTest {

  @get:Rule val folder = TemporaryFolder()

  /** L'azione colpita di uno scenario, con il motore, il diario e il catalogo del "processo" che puo' guastarsi o morire. */
  private fun interface Act {
    suspend fun run(w: VersionWorld, engine: NasVersionEngine, journal: VersionJournal, catalog: VersionCatalog)
  }

  /** Uno scenario: [prepare] (senza guasti) restituisce l'azione; [check] l'esito a lavoro finito (oltre agli invarianti). */
  private class Scenario(val name: String, val prepare: suspend (VersionWorld) -> Act, val check: suspend (VersionWorld) -> List<String>)

  private val share = VersionWorld.PRIVATE_SHARE
  private val versions = "Versioni/Backup/S25 di prova/2026/10"
  private val original = "$versions/IMG_1234 (originale).jpg"

  private fun engineRun() = Act { _, engine, _, _ -> engine.run(1.hours) }

  /** La prima modifica fatta e salvata (E-E1), senza guasti: la base degli scenari dello scambio. */
  private suspend fun VersionWorld.edited(): Pair<VersionWorld.Seeded, Sha256> {
    val seed = seedSafe()
    editOnPhone(seed)
    val v1 = phone.sha256(seed.phoneId!!)!!
    engine.planPhoneVersions(listOf(candidate(seed.mediaId, seed.phoneId)))
    runUntilQuiet()
    return seed to v1
  }

  private val scenarios: List<Scenario> = listOf(
    Scenario(
      "E-E1 modifica sul telefono",
      prepare = { w ->
        val seed = w.seedSafe()
        w.editOnPhone(seed)
        w.engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId!!)))
        engineRun()
      },
      check = { w ->
        val seed = w.catalog.allMedia().single()
        val expected = w.catalog.media(seed)!!.current.fullHash
        listOfNotNull(
          "attuale al solito percorso".takeIf { w.sha(share, "Backup/S25 di prova/2026/10/IMG_1234.jpg") != expected },
          "originale in Versioni/".takeIf { w.sha(share, original) == null },
        )
      },
    ),
    Scenario(
      "E-E6 originale dal telefono direttamente in Versioni/",
      prepare = { w ->
        val seed = w.seedSafe()
        val fromPc = w.bytes(71_000)
        w.knownContents += Sha256.of(fromPc)
        w.nas.modify(share, seed.usualPath.value, NasContent.of(fromPc))
        w.catalog.nasChanged(seed.mediaId, share, seed.usualPath, w.statOf(share, seed.usualPath))
        w.engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId!!, seed.original)))
        engineRun()
      },
      check = { w ->
        listOfNotNull("originale in Versioni/".takeIf { w.sha(share, original) == null })
      },
    ),
    Scenario(
      "E-E3 file preparato di un solo-NAS",
      prepare = { w ->
        val seed = w.seedSafe(onPhone = false)
        val edited = w.bytes(81_000)
        val sha = Sha256.of(edited)
        w.staged.keep(w.staged.newWorkFile().apply { writeBytes(edited) }, sha, seed.mediaId)
        val v = w.catalog.addVersion(seed.mediaId, VersionKind.EDITED, edited.size.toLong(), sha)
        w.engine.planStaged(seed.mediaId, v, sha, edited.size.toLong())
        engineRun()
      },
      check = { w ->
        val media = w.catalog.allMedia().single()
        listOfNotNull(
          "versione al solito percorso".takeIf { w.sha(share, "Backup/S25 di prova/2026/10/IMG_1234.jpg") != w.catalog.media(media)!!.current.fullHash },
          "file preparato tolto".takeIf { w.staged.pending().isNotEmpty() },
        )
      },
    ),
    Scenario(
      "E-E2 ritorno all'originale sul telefono",
      prepare = { w ->
        val (seed, _) = w.edited()
        w.revertOnPhone(seed)
        w.engine.planRealign(seed.mediaId)
        engineRun()
      },
      check = { w ->
        val media = w.catalog.allMedia().single()
        val o = w.catalog.media(media)!!.original!!
        listOfNotNull("originale al solito percorso".takeIf { w.sha(share, "Backup/S25 di prova/2026/10/IMG_1234.jpg") != o.fullHash })
      },
    ),
    Scenario(
      "E-E10 Ripristina originale dall'app con il telefono",
      prepare = { w ->
        val (seed, _) = w.edited()
        Act { world, engine, journal, catalog ->
          val rewriter = PhoneRowRewriter(world.phone).apply { granted += seed.phoneId!! }
          val keeper = DefaultVersionKeeper(world.share, catalog, journal, engine, world.gate, world.clock, world.nas.reader, rewriter, world.staged)
          val plan = keeper.plan(seed.mediaId)
          keeper.restoreOriginal(seed.mediaId, TestConfirmations.confirm(plan, world.clock.monotonic), PhoneWriteGrant(setOf(world.phone.ref(seed.phoneId!!)!!)))
        }
      },
      check = { emptyList() },
    ),
    Scenario(
      "E-L8 correzione di una foto del telefono",
      prepare = { w ->
        val seed = w.seedSafe()
        Act { world, engine, journal, catalog -> correct(world, engine, journal, catalog, seed, phone = true) }
      },
      check = { emptyList() },
    ),
    Scenario(
      "E-L8 correzione di un solo-NAS",
      prepare = { w ->
        val seed = w.seedSafe(onPhone = false)
        Act { world, engine, journal, catalog -> correct(world, engine, journal, catalog, seed, phone = false) }
      },
      check = { emptyList() },
    ),
  )

  private suspend fun correct(w: VersionWorld, engine: NasVersionEngine, journal: VersionJournal, catalog: VersionCatalog, seed: VersionWorld.Seeded, phone: Boolean) {
    val rewriter = PhoneRowRewriter(w.phone)
    seed.phoneId?.let { rewriter.granted += it }
    val corrections = DefaultCorrections(
      share = w.share, catalog = catalog, engine = engine, journal = journal, gate = w.gate, clock = w.clock, phone = w.phone.port,
      reader = w.nas.reader, writer = FakeMetadataWriter(mimeOf = { "image/jpeg" }), check = WrittenFileCheck { _, _, _ -> WrittenCheck.VALID },
      rewriter = rewriter, staged = w.staged, io = EmptyCoroutineContext,
    )
    val request = CorrectionRequest(ChangeSamples.newDate)
    val plan = corrections.plan(seed.mediaId, request)
    val grant = if (phone) PhoneWriteGrant(setOf(w.phone.ref(seed.phoneId!!)!!)) else null
    corrections.apply(plan, TestConfirmations.confirm(plan, w.clock.monotonic), grant)
  }

  /** Ripresa con un processo nuovo fino in fondo, poi gli invarianti e l'esito. */
  private suspend fun settle(w: VersionWorld, before: VersionWorld.Snapshot, scenario: Scenario, label: String) {
    w.restart()
    w.runUntilQuiet(maxRounds = 20)
    assertWithMessage("$label: lavori rimasti aperti").that(w.journal.all().filter { !it.state.terminal }).isEmpty()
    assertWithMessage("$label: invarianti").that(w.violations(before, final = true)).isEmpty()
    assertWithMessage("$label: esito").that(scenario.check(w)).isEmpty()
    // Nessun file preparato resta: o la sua versione e' arrivata sul NAS, o la correzione non e' mai arrivata al catalogo e si e' tolto.
    assertWithMessage("$label: file preparati rimasti").that(w.staged.pending()).isEmpty()
  }

  @Test
  fun unGuastoPrimaEDopoOgniChiamataAlNas() = runTest {
    for (scenario in scenarios) {
      // Il giro pulito conta le chiamate dell'azione.
      val clean = VersionWorld(folder.newFolder())
      val cleanAct = scenario.prepare(clean)
      val counter = FaultPlan()
      clean.nas.opsFaults = counter
      clean.nas.readFaults = counter
      cleanAct.run(clean, clean.engine, clean.journal, clean.catalog)
      clean.nas.opsFaults = null
      clean.nas.readFaults = null
      val total = counter.operations
      assertWithMessage("${scenario.name}: chiamate contate").that(total).isAtLeast(1)
      for (n in 1..total) {
        for (phase in FaultPhase.entries) {
          val w = VersionWorld(folder.newFolder())
          val act = scenario.prepare(w)
          val before = w.snapshot()
          val plan = FaultPlan().at(n, phase, SimFault.DropConnection)
          w.nas.opsFaults = plan
          w.nas.readFaults = plan
          act.run(w, w.engine, w.journal, w.catalog)
          w.nas.opsFaults = null
          w.nas.readFaults = null
          settle(w, before, scenario, "${scenario.name}: guasto alla chiamata $n/$total $phase")
        }
      }
    }
  }

  @Test
  fun laMorteDelProcessoPrimaEDopoOgniScritturaDelDiarioEDelCatalogo() = runTest {
    for (scenario in scenarios) {
      val clean = VersionWorld(folder.newFolder())
      val cleanAct = scenario.prepare(clean)
      val counting = BackupDeath()
      cleanAct.run(clean, clean.dyingEngine(counting), DyingVersionJournal(clean.journal, counting), DyingVersionCatalog(clean.catalog, counting))
      val total = counting.steps
      assertWithMessage("${scenario.name}: passi contati").that(total).isAtLeast(1)
      for (n in 1..total) {
        for (after in listOf(false, true)) {
          val w = VersionWorld(folder.newFolder())
          val act = scenario.prepare(w)
          val before = w.snapshot()
          val death = BackupDeath(dieAt = n, afterEffect = after)
          try {
            act.run(w, w.dyingEngine(death), DyingVersionJournal(w.journal, death), DyingVersionCatalog(w.catalog, death))
          } catch (_: ProcessDeath) {
            // Il processo e' morto: restano NAS, telefono, diario, catalogo e file preparati.
          }
          settle(w, before, scenario, "${scenario.name}: morte al passo $n/$total ${if (after) "dopo" else "prima"}")
        }
      }
    }
  }
}
