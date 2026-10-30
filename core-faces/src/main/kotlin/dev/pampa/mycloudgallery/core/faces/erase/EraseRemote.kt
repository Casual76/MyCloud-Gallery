package dev.pampa.mycloudgallery.core.faces.erase

import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseReason
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseRound
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * I passi 7-11 di "Disattiva e cancella" (§2.8): annuncio, NAS, verifica, conferme, fine. Una ripresa ne fa quanto il budget e il NAS permettono e
 * lascia nel diario solo cio' che e' davvero fatto; un passo che aspetta lo dice con il motivo ([EraseWait], invariante 6).
 *
 * - **Annuncio** (7): un giro dei dati personali ([EraseAnnouncer]); fatto solo quando la **propria** testa personale dice `off` (o una generazione
 *   che non e' fra quelle cancellate): la stessa prova che si chiede agli altri.
 * - **NAS** (8): l'intera `faces/<G>/` di ogni generazione cancellata (`USER_CONFIRMED`), poi le altre generazioni non correnti trovate (`NOT_CURRENT`):
 *   sempre per generazione e motivo, mai per percorso; la regola la applica core-nasops (D31).
 * - **Verifica** (9): ripetuta a ogni ripresa finche' manca qualcuno; se una cartella cancellata ricompare (un dispositivo che pubblicava prima di
 *   saperlo) si toglie di nuovo; il cestino di rete (E-F34) si dice.
 * - **Conferme** (10): ogni dispositivo dell'account approvato o dismesso, dalla sua testa personale ([EraseRules.confirms]); revocati e persi no.
 * - **Fine** (11): tutto fatto e tutti confermati.
 *
 * Una voce `on|G` di una generazione gia' cancellata che ricompare (un dispositivo che la riscrive senza sapere della cancellazione) non riaccende
 * niente: la scelta confermata dall'utente si riafferma con una voce `off` piu' nuova.
 */
internal class EraseRemote(
  private val eraser: DefaultFacesEraser,
  private val parts: FacesEraseParts,
  private val deadline: Long,
  private val removed: DefaultFacesEraser.Counter,
) {
  private var waitNow: EraseWait? = null

  private fun remaining(): Duration = (deadline - parts.clock.monotonicNanos()).coerceAtLeast(0).nanoseconds

  private fun expired(): Boolean = parts.clock.monotonicNanos() >= deadline

  private fun waitFor(reason: EraseWait) {
    if (waitNow == null) waitNow = reason
  }

  suspend fun run(start: EraseJournal): EraseJournal {
    var j = start
    // La voce `off` riaffermata se una voce `on` di una generazione cancellata e' tornata (D30: la scelta dell'utente vince).
    val current = eraser.attempt { parts.settings.current() }
    if (current is FacesSetting.On && current.generation in j.erased) {
      j = eraser.saved(j.copy(setting = false, announced = false, offAtNas = null)) ?: return j
      j = eraser.writeSetting(j)
    }
    if (!j.setting) {
      // La voce non si e' potuta scrivere (forma sconosciuta, guasto): niente si annuncia ne' si cancella sul NAS per lei.
      waitFor(EraseWait.NAS)
      return finish(j)
    }
    // 7. Annuncio.
    if (!j.announced) {
      val result = eraser.attempt { parts.announcer.announce(remaining()) } ?: AnnounceResult.Waiting(EraseWait.NAS)
      val offAt = ownAnnouncement(j.erased)
      if (offAt != null) {
        j = eraser.saved(j.copy(announced = true, offAtNas = offAt)) ?: return finish(j)
      } else {
        waitFor((result as? AnnounceResult.Waiting)?.reason ?: EraseWait.NAS)
      }
    }
    // 8. NAS.
    if (!j.nasDone && eraseOnNas(j)) j = eraser.saved(j.copy(nasDone = true)) ?: return finish(j)
    // 9. Verifica.
    var clean = false
    if (j.nasDone) {
      val checks = j.erased.map { eraser.attempt { parts.nas.verify(it) } }
      if (checks.any { it == null }) {
        waitFor(EraseWait.NAS)
      } else {
        val present = checks.any { it!!.generationPresent }
        val bin = checks.any { it!!.recycleBinCopy }
        if (present) {
          // Ricomparsa: si toglie di nuovo (al giro dopo, o subito se resta tempo).
          j = eraser.saved(j.copy(nasDone = false, verified = false)) ?: return finish(j)
          if (!expired() && eraseOnNas(j)) j = eraser.saved(j.copy(nasDone = true)) ?: return finish(j)
        } else {
          clean = true
          if (!j.verified || j.recycleBin != bin) j = eraser.saved(j.copy(verified = true, recycleBin = bin)) ?: return finish(j)
        }
      }
    }
    // 10. Conferme.
    val acks = eraser.acksFor(j.erased, j.offAtNas)
    eraser.setAcks(acks)
    val all = DefaultFacesEraser.allConfirmed(acks)
    if (j.nasDone && clean && !all) waitFor(EraseWait.DEVICES)
    // 11. Fine.
    if (j.announced && j.nasDone && clean && all) {
      j = eraser.saved(j.copy(confirmed = true, done = true, doneAtNas = parts.clock.nasNowMillis())) ?: return finish(j)
    }
    return finish(j)
  }

  private fun finish(j: EraseJournal): EraseJournal {
    eraser.setWait(if (j.done) null else waitNow)
    eraser.publishStatus()
    return j
  }

  private suspend fun ownAnnouncement(erased: List<FaceGeneration>): Long? {
    val heads = eraser.attempt { parts.heads.latestValueOf(FacesSettingCodec.KEY) } ?: return null
    return EraseRules.announcedAt(heads[parts.identity.deviceId], erased, eraser.attempt { parts.settings.current() })
  }

  /** Il passo 8: vero quando non resta niente sul NAS delle generazioni cancellate e delle altre non correnti trovate. */
  private suspend fun eraseOnNas(j: EraseJournal): Boolean {
    for (generation in j.erased) {
      if (!rounds(generation, NasEraseReason.USER_CONFIRMED, mandatory = true)) return false
    }
    val listed = eraser.attempt { parts.nas.generations() }
    if (listed == null) {
      waitFor(EraseWait.NAS)
      return false
    }
    val keep = (eraser.attempt { parts.settings.current() } as? FacesSetting.On)?.generation
    val claims = eraser.attempt { parts.heads.latestValueOf(FacesSettingCodec.KEY) }.orEmpty()
    val nasNow = parts.clock.nasNowMillis()
    for (generation in listed) {
      if (generation in j.erased || generation == keep) continue
      // E-F9: una generazione che una testa recente dice accesa non si tocca adesso (la togliera' la pulizia di ogni dispositivo, dopo).
      if (claims.isEmpty() || EraseRules.recentlyClaimed(claims, generation, nasNow)) continue
      if (!rounds(generation, NasEraseReason.NOT_CURRENT, mandatory = false)) return false
    }
    return true
  }

  /** Giri su [generation] finche' e' finita (vero), il tempo scade o si aspetta (falso). Una generazione non piu' ammessa e non obbligatoria si salta. */
  private suspend fun rounds(generation: FaceGeneration, reason: NasEraseReason, mandatory: Boolean): Boolean {
    repeat(MAX_ROUNDS) {
      val round = eraser.attempt { parts.nas.eraseGeneration(generation, reason, remaining()) } ?: NasEraseRound.Waiting(EraseWait.NAS)
      when (round) {
        is NasEraseRound.Done -> {
          removed.files += round.removedFiles
          return true
        }
        is NasEraseRound.More -> {
          removed.files += round.removedFiles
          if (expired()) return false
          // Un giro che non toglie niente senza che il tempo sia finito (un file aperto da un altro, cambiato dopo l'elenco) non si ripete subito e non e' un
          // avanzamento: e' un'attesa dichiarata (R13), con il suo motivo, e chi riprende (`FacesGraph.eraseRound`) allunga le attese.
          if (round.removedFiles == 0) {
            waitFor(EraseWait.FILE_OPEN)
            return false
          }
        }
        is NasEraseRound.Waiting -> {
          waitFor(round.reason)
          return false
        }
        // La regola di core-nasops non lo ammette (area non privata, generazione corrente): per una generazione cancellata e' un'attesa
        // dichiarata (la voce `off` non e' ancora la corrente per chi decide); per un'altra si lascia stare.
        NasEraseRound.NotAllowed, NasEraseRound.Unsure -> {
          if (!mandatory && round == NasEraseRound.NotAllowed) return true
          waitFor(EraseWait.NAS)
          return false
        }
      }
    }
    return false
  }

  private companion object {
    /** Giri per generazione in una ripresa (ognuno al piu' 20.000 file): oltre, alla ripresa dopo. */
    const val MAX_ROUNDS = 64
  }
}
