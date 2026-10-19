package dev.pampa.mycloudgallery.core.backup.chaos

import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.free.BootCounter
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.PublishOutcome
import dev.pampa.mycloudgallery.core.nas.ops.RenameOutcome
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOps
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// Gli ERRORI INTRODOTTI APPOSTA della 5B (BackupChaosMutationTest, docs/fase5-progetto.md riga P13 del §4.4): quattro difetti che le versioni, gli
// scambi o "Libera spazio" potrebbero avere, ciascuno scritto come uno stack rotto che avvolge le porte dei servizi sani (le primitive del NAS,
// gli orologi) e ne riproduce l'EFFETTO. I servizi stanno in `core-backup/src/main`, che il banco non tocca: come per la 5A e per la 4B, ogni
// difetto avvolge un pezzo dello stack e rompe una promessa. Ogni mutante sta SOPRA i decoratori dei colpi (il servizio vede: mutante → colpi →
// NAS), cosi' i guasti del banco colpiscono anche lui.
//
//  1. ARCHIVIARE SENZA TIMBRO: la rinomina in `Versioni/` non porta il timbro che il catalogo conosce, ma quello che il NAS dice adesso: un file
//     cambiato dal PC dopo il piano viene archiviato come se fosse la versione attuale;
//  2. SOSTITUIRE AL POSTO DI RINOMINARE: la pubblicazione, lo scambio e l'archiviazione, davanti a un nome occupato, tolgono il file che c'e' e
//     mettono il loro invece di fermarsi (`Collision`);
//  3. LIBERARE CON UNA VERIFICA SCADUTA: l'orologio monotono con cui "Libera spazio" giudica la validita' della verifica (6 ore) e il conteggio
//     dei riavvii non avanzano mai: una verifica vecchia di mezza giornata vale come nuova;
//  4. UNO SCAMBIO CHE LASCIA VUOTO IL SOLITO PERCORSO: "Ripristina originale" archivia l'attuale, poi dice di aver riportato l'originale senza
//     riportarlo: a lavoro finito il solito percorso e' vuoto.

/** Uno stack della 5B rotto: lo stack sano con le porte avvolte da [build]. */
class FiveBMutant(
  private val base: FiveBStackFactory,
  private val label: String,
  private val build: (FiveBEnv) -> FiveBEnv,
) : FiveBStackFactory {
  override val name: String = "${base.name} + difetto iniettato: $label"

  override fun create(env: FiveBEnv): FiveBServices = base.create(build(env))
}

object FiveBMutants {

  /** 1. Archiviare senza timbro. */
  fun archiveWithoutStamp(base: FiveBStackFactory): FiveBStackFactory = FiveBMutant(base, "archivia senza il timbro che il catalogo conosce") { env ->
    env.with(ops = StamplessOps(env.ops, env.world.nas.reader))
  }

  private class StamplessOps(private val inner: NasBackupOps, private val reader: NasMediaReader) : NasBackupOps by inner {
    // Il timbro "di adesso": cio' che il NAS dice, non cio' che il piano aveva visto. Se il file non c'e' si passa quello atteso (la rinomina fallira').
    override suspend fun archiveVersion(share: String, from: SharePath, to: SharePath, expect: NasStamp, purpose: Purpose): NasResult<RenameOutcome> {
      val now = (reader.stat(share, from, purpose) as? NasResult.Ok)?.value?.takeIf { !it.isDirectory }?.stamp ?: expect
      return inner.archiveVersion(share, from, to, now, purpose)
    }
  }

  /** 2. Sostituire al posto di rinominare. */
  fun replaceInsteadOfRename(base: FiveBStackFactory): FiveBStackFactory = FiveBMutant(base, "sostituisce un file occupato invece di fermarsi") { env ->
    env.with(ops = ReplacingOps(env.ops, env.world.nas.reader, env.world))
  }

  private class ReplacingOps(
    private val inner: NasBackupOps,
    private val reader: NasMediaReader,
    private val world: BackupChaosWorld,
  ) : NasBackupOps by inner {
    private suspend fun clear(share: String, path: SharePath, purpose: Purpose) {
      val there = (reader.stat(share, path, purpose) as? NasResult.Ok)?.value
      if (there != null && !there.isDirectory) world.nas.remove(share, path.value)
    }

    override suspend fun publishTemp(share: String, temp: SharePath, final: SharePath, purpose: Purpose): NasResult<PublishOutcome> {
      clear(share, final, purpose)
      return inner.publishTemp(share, temp, final, purpose)
    }

    override suspend fun archiveVersion(share: String, from: SharePath, to: SharePath, expect: NasStamp, purpose: Purpose): NasResult<RenameOutcome> {
      clear(share, to, purpose)
      return inner.archiveVersion(share, from, to, expect, purpose)
    }

    override suspend fun restoreVersion(share: String, from: SharePath, to: SharePath, expect: NasStamp, purpose: Purpose): NasResult<RenameOutcome> {
      clear(share, to, purpose)
      return inner.restoreVersion(share, from, to, expect, purpose)
    }
  }

  /** 3. Liberare con una verifica scaduta. */
  fun releaseWithExpiredVerification(base: FiveBStackFactory): FiveBStackFactory =
    FiveBMutant(base, "la validita' della verifica non scade mai (orologio e riavvii fermi)") { env ->
      env.with(clock = FrozenClock(env.clock), boot = FrozenBoot(env.boot.current()))
    }

  /** Un orologio che non va avanti: il monotono resta quello del primo sguardo, l'ora del NAS e' quella vera. */
  private class FrozenClock(private val inner: BackupClock) : BackupClock {
    private var frozen: Long? = null

    override fun monotonicMs(): Long = frozen ?: inner.monotonicMs().also { frozen = it }

    override fun nasNowMs(): Long? = inner.nasNowMs()
  }

  private class FrozenBoot(private val boot: Long?) : BootCounter {
    override fun current(): Long? = boot
  }

  /** 4. Uno scambio che lascia vuoto il solito percorso. */
  fun swapLeavesUsualPathEmpty(base: FiveBStackFactory): FiveBStackFactory =
    FiveBMutant(base, "lo scambio non riporta l'originale al solito percorso") { env -> env.with(ops = ForgetfulRestoreOps(env.ops)) }

  private class ForgetfulRestoreOps(private val inner: NasBackupOps) : NasBackupOps by inner {
    // Dice di aver riportato l'originale; non lo fa. Il catalogo ci credera' (la posizione "segue" una rinomina che non c'e' stata).
    override suspend fun restoreVersion(share: String, from: SharePath, to: SharePath, expect: NasStamp, purpose: Purpose): NasResult<RenameOutcome> =
      NasResult.Ok(RenameOutcome.Renamed(expect))
  }
}
