package dev.pampa.mycloudgallery.core.rules.settings

import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.PowerPolicy

/**
 * **Chi puo' lavorare a batteria con le regole di adesso** (pacchetto B, BU-3). Con il preset di serie «Notte» le attivita' lavorano solo in carica: la soglia della batteria
 * (che vale solo a batteria) per quel gruppo non ha effetto, e il controllo non deve mostrare un interruttore finto-attivo.
 *
 * @property backupOnBattery il backup puo' lavorare a batteria: il backup normale con un'alimentazione `BATTERY_ALLOWED`, oppure «Proteggi subito le foto nuove» (che lavora a batteria con
 *   qualunque preset, [quickPhotosRuleSet]).
 * @property analysisOnBattery l'analisi (Calcolo AI, con vettori, testo e volti) puo' lavorare a batteria.
 * @property backupNeedsPower il backup normale (le foto) lavora solo in carica: la pagina del Backup lo dice con il segno della carica fra «Il backup parte con».
 * @property backupEnabled almeno una delle attivita' del backup (foto, video) e' accesa nelle regole (BS3-1): spenta dall'utente non e' «solo in carica», e' spenta.
 * @property analysisEnabled il Calcolo AI e' acceso nelle regole (BS3-1).
 */
data class BatteryReach(
  val backupOnBattery: Boolean,
  val analysisOnBattery: Boolean,
  val backupNeedsPower: Boolean,
  val backupEnabled: Boolean,
  val analysisEnabled: Boolean,
)

fun RuleSettings.batteryReach(): BatteryReach {
  val rules = toRuleSet()
  fun onBattery(activity: BackgroundActivity) = rules.ruleFor(activity).let { it.enabled && it.power == PowerPolicy.BATTERY_ALLOWED }
  val normalBackup = onBattery(BackgroundActivity.PHOTO_BACKUP) || onBattery(BackgroundActivity.VIDEO_BACKUP)
  return BatteryReach(
    backupOnBattery = normalBackup || quickPhotosRuleSet() != null,
    analysisOnBattery = onBattery(BackgroundActivity.AI_COMPUTE),
    backupNeedsPower = !onBattery(BackgroundActivity.PHOTO_BACKUP),
    backupEnabled = rules.ruleFor(BackgroundActivity.PHOTO_BACKUP).enabled || rules.ruleFor(BackgroundActivity.VIDEO_BACKUP).enabled,
    analysisEnabled = rules.ruleFor(BackgroundActivity.AI_COMPUTE).enabled,
  )
}
