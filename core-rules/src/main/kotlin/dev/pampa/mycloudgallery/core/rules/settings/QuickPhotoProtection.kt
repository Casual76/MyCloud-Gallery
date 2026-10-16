package dev.pampa.mycloudgallery.core.rules.settings

import dev.pampa.mycloudgallery.core.rules.ActivityRule
import dev.pampa.mycloudgallery.core.rules.AllowedNetwork
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.PowerPolicy
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.RuleEvaluator
import dev.pampa.mycloudgallery.core.rules.RuleSet
import dev.pampa.mycloudgallery.core.rules.SystemState
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.rules.traffic.CycleUsage

// "PROTEGGI SUBITO LE FOTO NUOVE" (Fase 5, D34, approvata il 15/10 con Q7 (a); docs/regole-attivita.md §8 punto 14).
//
// Samsung "Salva" sovrascrive il file: una foto modificata prima del backup notturno non arriverebbe mai originale sul NAS (E-B15, L38
// di docs/sicurezza.md). Per questo, con QUALUNQUE preset, una foto appena scattata si carica appena c'e' il Wi-Fi di casa e la batteria
// e' sopra la soglia, anche fuori dalla fascia di Notte e anche a batteria. Costo: pochi secondi per foto.
//
// La regola e' una richiesta `PHOTO_BACKUP` (`BackupTrigger.QUICK_NEW_PHOTOS` in core-backup) valutata dallo stesso valutatore di tutti
// i lavori, con regole sue: nessuna fascia, anche a batteria (la soglia vale ancora), **solo il Wi-Fi di casa** (mai dati mobili, mai
// roaming, mai un Wi-Fi esterno via VPN). Restano tutti gli altri ostacoli: NAS raggiungibile e non congelato, risparmio energetico,
// temperatura, limiti di Android, tetto Wi-Fi. Solo foto della fotocamera e mai video: lo garantisce il piano (`BackupPlanner.next` con
// il trigger), perche' qui si decide soltanto il *quando*.

/** Le regole di una corsa "foto nuove" del backup delle foto ([BackgroundActivity.PHOTO_BACKUP]). */
object QuickPhotoProtection {

  /** La regola della corsa: nessuna fascia, anche a batteria (sopra la soglia), solo il Wi-Fi di casa. */
  val RULE: ActivityRule =
    ActivityRule(window = null, power = PowerPolicy.BATTERY_ALLOWED, networks = setOf(AllowedNetwork.HOME_WIFI), enabled = true)

  /** La richiesta al valutatore: un lavoro in background che scrive sul NAS (lo ferma il congelamento, D10). */
  val REQUEST: WorkRequest = WorkRequest.Background(BackgroundActivity.PHOTO_BACKUP, writesToNas = true)

  /**
   * Il verdetto della corsa "foto nuove" con le impostazioni di [settings]: [WaitReason.ActivityDisabled] se la regola e' spenta
   * ("Proteggi subito le foto nuove" spento o backup delle foto spento); altrimenti quello del valutatore con [quickPhotosRuleSet].
   * [running] vero a ogni lotto (un'attesa diventa uno `Stop`).
   */
  fun evaluate(
    evaluator: RuleEvaluator,
    settings: RuleSettings,
    state: SystemState,
    usage: CycleUsage,
    running: Boolean = false,
  ): RuleDecision {
    val rules = settings.quickPhotosRuleSet()
    if (rules == null) {
      return if (running) RuleDecision.Stop(WaitReason.ActivityDisabled) else RuleDecision.Wait(WaitReason.ActivityDisabled)
    }
    return evaluator.evaluate(REQUEST, rules, state, usage, running)
  }
}

/**
 * Le regole per una corsa "foto nuove" (D34), o null se non c'e': "Proteggi subito le foto nuove" spento, oppure il backup delle foto
 * spento dall'utente (con "Personalizzato" o fra le attivita' disattivate: la regola non lo riaccende). Sono le regole delle
 * impostazioni (soglia della batteria, tetti, risparmio energetico) con la sola `PHOTO_BACKUP` sostituita da [QuickPhotoProtection.RULE]:
 * qualunque preset, anche Massimo, non apre alla corsa i dati mobili.
 */
fun RuleSettings.quickPhotosRuleSet(): RuleSet? {
  if (!protectNewPhotos) return null
  val base = toRuleSet()
  if (!base.ruleFor(BackgroundActivity.PHOTO_BACKUP).enabled) return null
  return base.copy(rules = base.rules + (BackgroundActivity.PHOTO_BACKUP to QuickPhotoProtection.RULE))
}
