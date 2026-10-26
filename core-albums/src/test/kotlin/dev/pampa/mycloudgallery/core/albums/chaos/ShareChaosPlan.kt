package dev.pampa.mycloudgallery.core.albums.chaos

import kotlin.random.Random

// IL PIANO DI UN GIRO: che cosa puo' fare l'ambiente (capacita'), come e' fatto il giro (passi, guasti dell'ambiente) e i colpi di un passo. Tutto
// dal seme: lo stesso seme, la stessa prova.

/** Che cosa c'e' sotto il banco: il NAS finto in memoria non ha chiamate SMB ne' un diario di core-nasops da colpire, il NAS vero non cambia lo spazio. */
class Capabilities(
  /** Il diario di core-nasops si puo' colpire (il motore e' sopra la sessione SMB e un diario che il banco avvolge). */
  val opsStore: Boolean,
  /** Le chiamate SMB del motore si possono colpire (trasporto con i colpi). */
  val nasCalls: Boolean,
  /** Un file si puo' tenere aperto "dal PC" (E-N13). */
  val lockedFiles: Boolean,
  /** Lo spazio libero dell'account si puo' cambiare (E-SH8). */
  val freeSpace: Boolean,
) {
  companion object {
    /** Il NAS finto in memoria (`FakeNasTarget`): colpi alle porte, alla porta di completamento e al diario della condivisione. */
    val FAKE = Capabilities(opsStore = false, nasCalls = false, lockedFiles = true, freeSpace = true)

    /** Il simulatore SMB con le primitive vere: in piu', ogni chiamata SMB e ogni salvataggio del diario del motore. */
    val SIMULATED = Capabilities(opsStore = true, nasCalls = true, lockedFiles = true, freeSpace = true)

    /** Il NAS vero: come il simulatore, ma nessun file "aperto dal PC" (gli handle restano sul NAS) e lo spazio non si cambia. */
    val REAL = Capabilities(opsStore = true, nasCalls = true, lockedFiles = false, freeSpace = false)
  }
}

/** I passi che l'utente puo' fare (§2.5, §2.6). */
enum class StepKind(val label: String) {
  SHARE_NEW("condividi un album nuovo"),
  ADD("aggiungi a un album condiviso"),
  BRING_BACK("riporta nelle mie cartelle"),
  ADOPT("adotta i file aggiunti dal PC"),
}

/** Cio' che l'ambiente fa durante un passo. */
enum class HazardKind(val label: String) {
  NONE("niente"),
  LOCK("un file aperto dal PC"),
  LOCK_AND_MODIFY("un file cambiato dal PC a meta'"),
  FREEZE("area congelata"),
  LOW_SPACE("spazio finito"),
  ALBUM_GATE("scritture degli album ferme"),
}

/** Le misure di un giro. */
class ShareChaosConfig(
  val capabilities: Capabilities,
  val minSteps: Int = 1,
  val maxSteps: Int = 3,
  /** Quanti colpi al massimo in un passo. */
  val maxBlows: Int = 4,
  /** Quanti giri del lavoro con i colpi armati, per passo. */
  val workRounds: Int = 8,
  /** Quanti giri al massimo, senza guasti, perche' il lavoro arrivi in fondo. */
  val settleRounds: Int = 40,
  /** Fra un giro e l'altro dell'assestamento, quanto si aspetta davvero (0 sulla JVM, dove il tempo e' finto): sul NAS vero le attese del motore sono di orologio. */
  val settlePauseMs: Long = 0,
  /** Su cento passi finiti, quante volte una morte senza colpo e una ripresa a vuoto (non deve cambiare niente). */
  val idleRestartPercent: Int = 25,
  /** Su cento passi, quante volte l'ambiente fa qualcosa (un file aperto, cambiato, area congelata, spazio finito). */
  val hazardPercent: Int = 35,
  /** Pesi delle superfici dei colpi (0 = mai). */
  val weights: Map<Surface, Int> = DEFAULT_WEIGHTS,
  /** Su cento colpi, quanti sono morti del processo (in piu' di quelli obbligati dalla superficie). */
  val killBias: Int = 0,
  /** La dimensione dei media: su cento, quanti sono "medi" (decine di KB) e quanti grandi (centinaia di KB, piu' blocchi di copia). */
  val mediumPercent: Int = 12,
  val bigPercent: Int = 3,
  /**
   * I colpi di un passo scritti a mano invece che sorteggiati: `ShareChaosSweepTest` prova UN colpo a ogni chiamata del passo. Riceve il numero del passo
   * (da 1); null = sorteggia come sempre. Non consuma numeri casuali: il mondo e i passi restano quelli del seme.
   */
  val scriptedBlows: ((stepNo: Int) -> Blows?)? = null,
) {
  companion object {
    val DEFAULT_WEIGHTS: Map<Surface, Int> = mapOf(
      Surface.MOVER to 6,
      Surface.UPLOADER to 3,
      Surface.TICKETS to 8,
      Surface.PUBLISHER to 8,
      Surface.SAFE_COPIES to 3,
      Surface.ORIGINS to 3,
      Surface.FACTS to 3,
      Surface.COMPLETION to 8,
      Surface.SHARE_DIARY to 16,
      Surface.OPS_STORE to 12,
      Surface.NAS to 28,
    )
  }
}

/** I colpi di un passo: quelli armati prima che l'utente tocchi e quelli armati appena l'avvio e' tornato (il lavoro in corso). */
class Blows(val early: List<Blow>, val late: List<Blow>) {
  val all: List<Blow> get() = early + late
}

object ShareChaosBlows {

  /** Fin dove puo' cadere un colpo, per superficie: l'n-esima chiamata. Pochi punti per le porte che si chiamano poco, molti per il NAS. */
  private fun reach(surface: Surface, step: StepKind): Int {
    // Il doppio circa delle chiamate che un passo fa davvero (misurate sul banco: le porte di un passo vanno da 1 a poche decine), cosi' quasi ogni colpo scatta.
    val base = when (surface) {
      Surface.MOVER -> 3
      Surface.UPLOADER -> 2
      Surface.TICKETS -> 8
      Surface.PUBLISHER -> 10
      Surface.SAFE_COPIES -> 3
      Surface.ORIGINS -> 5
      Surface.FACTS -> 24
      Surface.COMPLETION -> 4
      Surface.SHARE_DIARY -> 20
      Surface.OPS_STORE -> 24
      Surface.NAS -> 70
    }
    val scaled = when (step) {
      StepKind.SHARE_NEW -> base
      StepKind.ADD -> (base * 3 / 4)
      StepKind.BRING_BACK -> (base * 3 / 5)
      StepKind.ADOPT -> (base / 3)
    }
    return scaled.coerceAtLeast(1)
  }

  /**
   * Dove ha senso colpire in questo passo: il NAS e il diario del motore solo se c'e' qualcosa da spostare ([movers] media dell'area privata, o un "Riporta");
   * il caricamento solo se c'e' un media solo sul telefono ([uploads]); l'adozione dei file del PC non sposta niente (pubblica e basta).
   */
  private fun available(surface: Surface, caps: Capabilities, step: StepKind, movers: Int, uploads: Int): Boolean {
    val moves = movers > 0 || step == StepKind.BRING_BACK
    return when (surface) {
      Surface.OPS_STORE -> caps.opsStore && moves
      Surface.NAS -> caps.nasCalls && moves
      Surface.MOVER, Surface.COMPLETION -> moves
      Surface.UPLOADER -> uploads > 0
      Surface.SAFE_COPIES -> movers > 0 && step != StepKind.ADOPT
      Surface.ORIGINS -> moves
      Surface.TICKETS -> moves || uploads > 0
      else -> true
    }
  }

  private fun <T> pick(rnd: Random, options: List<Pair<Int, T>>): T {
    val total = options.sumOf { it.first }
    var roll = rnd.nextInt(total)
    for ((weight, value) in options) {
      if (roll < weight) return value
      roll -= weight
    }
    return options.last().second
  }

  private fun kindFor(rnd: Random, surface: Surface, killBias: Int): ShareFaultKind {
    if (surface.deathOnly) return ShareFaultKind.KILL
    if (killBias > 0 && rnd.nextInt(100) < killBias) return ShareFaultKind.KILL
    return when (surface) {
      Surface.COMPLETION -> if (rnd.nextInt(100) < 55) ShareFaultKind.KILL else ShareFaultKind.DROP
      Surface.MOVER, Surface.UPLOADER -> pick(
        rnd,
        listOf(
          38 to ShareFaultKind.KILL, 24 to ShareFaultKind.DROP, 10 to ShareFaultKind.EXPIRE_SESSION, 8 to ShareFaultKind.STALL, 20 to ShareFaultKind.NO_SPACE,
        ),
      )
      // Lo spazio finito ferma UN file di un trasferimento che ne porta altri: esiti diversi nello stesso trasferimento (SD-1, corretto).
      Surface.NAS -> pick(
        rnd,
        listOf(
          30 to ShareFaultKind.KILL, 28 to ShareFaultKind.DROP, 14 to ShareFaultKind.EXPIRE_SESSION, 12 to ShareFaultKind.STALL,
          16 to ShareFaultKind.NO_SPACE,
        ),
      )
      else -> pick(rnd, listOf(40 to ShareFaultKind.KILL, 30 to ShareFaultKind.DROP, 15 to ShareFaultKind.EXPIRE_SESSION, 15 to ShareFaultKind.STALL))
    }
  }

  /** I colpi di un passo di tipo [step]: da 1 a [ShareChaosConfig.maxBlows], sorteggiati (stesso seme, stessi colpi). */
  fun generate(rnd: Random, config: ShareChaosConfig, step: StepKind, movers: Int = 1, uploads: Int = 0): Blows {
    val surfaces = config.weights.filter { (surface, weight) -> weight > 0 && available(surface, config.capabilities, step, movers, uploads) }.map { it.value to it.key }
    val count = pick(rnd, listOf(30 to 1, 30 to 2, 20 to 3, 12 to 4, 8 to 5)).coerceAtMost(config.maxBlows)
    val early = ArrayList<Blow>()
    val late = ArrayList<Blow>()
    repeat(count) {
      val surface = pick(rnd, surfaces)
      val kind = kindFor(rnd, surface, config.killBias)
      // Lo spazio finito e' un rifiuto prima della chiamata (porte) o un `DISK_FULL`: mai "dopo" su una porta.
      val moment = if (kind == ShareFaultKind.NO_SPACE && surface != Surface.NAS) Moment.BEFORE else if (rnd.nextBoolean()) Moment.BEFORE else Moment.AFTER
      val blow = Blow(surface, 1 + rnd.nextInt(reach(surface, step)), moment, kind)
      // Il motore lavora dentro l'avvio: i colpi sul suo NAS, sul suo diario, sullo spostamento e sul completamento si armano prima. Quelli sulle porte che
      // lavorano dopo (biglietti, pubblicatore, catalogo, guardiano, diario della condivisione) anche dopo l'avvio.
      if (surface in AFTER_START && rnd.nextInt(100) < LATE_PERCENT) late += blow else early += blow
    }
    return Blows(early, late)
  }

  /** Su cento colpi, quanti si armano solo dopo l'avvio (il lavoro gia' in corso: le morti con i trasferimenti partiti). */
  private const val LATE_PERCENT = 55

  private val AFTER_START = setOf(Surface.TICKETS, Surface.PUBLISHER, Surface.FACTS, Surface.SAFE_COPIES, Surface.SHARE_DIARY)
}
