package dev.pampa.mycloudgallery.core.designsystem.design

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidSkeleton
import dev.antigravity.fluidengine.ui.fluid.FluidSkeletonRow
import dev.antigravity.fluidengine.ui.fluid.FluidSlider
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.fluid.FluidTextStyles
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.designsystem.R
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryStatusMark
import dev.pampa.mycloudgallery.core.designsystem.icon.galleryStatusMarkSize
import dev.pampa.mycloudgallery.core.designsystem.icon.rememberGalleryIconColors
import kotlin.math.roundToInt

/**
 * La scelta del livello di batteria sotto cui il backup e l'analisi delle foto non partono (Pacchetto B, seconda beta). **Un componente solo**: sta nelle
 * impostazioni del Backup e, identico, in quelle dell'analisi; ognuna lo lega alle stesse impostazioni delle regole.
 *
 * Regola dell'utente: «se non sapessi leggere potrei usare l'app?». La figura dice **«sotto qui mi fermo»**, non «la tua batteria e' al 20%»: la parte della batteria fino al livello e' del colore
 * che l'app usa gia' per «fermo per la batteria» (l'arancione della famiglia `People`, come nelle attese) e sulla batteria c'e' il segno dell'attesa (la clessidra di [GalleryIconStatus.Waiting]).
 * Le due righe non dicono «Backup» e «Analisi» accesi/spenti (si leggerebbero come «salva / non salvare»): dicono **«Ferma il backup»** e **«Ferma l'analisi»**, con la clessidra sulla piastrella quando
 * sono accesi. Il numero sta una volta sola, grande; la frase dice chi si ferma «sotto questo livello». Niente colori, raggi o misure di testo scritti qui; con il movimento ridotto la batteria cambia di colpo.
 *
 * **Chi puo' lavorare a batteria** (BU-3): con una regola che fa lavorare un gruppo solo in carica (il preset di serie «Notte») l'interruttore di quel gruppo non avrebbe effetto: la riga dice
 * «Solo in carica» (segno della carica + due parole) al posto dell'interruttore, e la figura lo riflette.
 *
 * @param value il livello e i due interruttori **salvati**; null = le impostazioni non sono ancora lette: si vede lo scheletro con la forma del controllo (mai un 20% finto). Il livello e' quello **vero**
 *   (BS-4): un valore gia' salvato fuori griglia (33, o 0 e 100) si mostra com'e' finche' l'utente non sceglie.
 * @param onLevelChange il nuovo livello, **solo a dito alzato** (una scrittura sola per scelta, non una per passo).
 * @param saveResult l'esito dell'ultima scrittura (BS2-2), **per richiesta**: ogni esito ha il suo identificativo e il livello chiesto, quindi due fallimenti di fila riportano il controllo al valore salvato
 *   tutte e due le volte, e un esito vecchio non tocca la scelta piu' nuova. Un fallimento dice anche la frase d'avviso, come le altre impostazioni.
 */
@Composable
fun GalleryBatteryGuard(
  value: BatteryGuardValue?,
  onLevelChange: (Int) -> Unit,
  onForBackupChange: (Boolean) -> Unit,
  onForAnalysisChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  saveResult: BatterySaveResult? = null,
) {
  if (value == null) {
    GuardSkeleton(modifier)
    return
  }
  val saved = value.levelPercent
  // Il gesto vive da se': un riscontro di una scelta precedente (il livello salvato che cambia) non azzera il dito che e' di nuovo sul cursore.
  val gesture = remember { BatteryLevelGesture(saved) }
  LaunchedEffect(saved) { gesture.onSaved(saved) }
  // L'esito e' per richiesta: la chiave e' il suo identificativo (mai una transizione di un booleano, che due fallimenti di fila non producono).
  LaunchedEffect(saveResult?.id) {
    val result = saveResult
    if (result != null && !result.ok) gesture.onFailed(result.level)
  }
  val shown = gesture.shown
  val stopsBackup = value.forBackup && value.backupOnBattery
  val stopsAnalysis = value.forAnalysis && value.analysisOnBattery
  val state = BatteryGuard.stateOf(stopsBackup, stopsAnalysis)
  val chargingOnly = !value.backupOnBattery && !value.analysisOnBattery
  // Il livello non decide niente (niente da fermare, o lavorano solo in carica): figura e cursore sfumano, come una riga disattivata.
  val idle = state == BatteryGuardState.NONE
  // Il numero sta una volta sola, grande, accanto alla batteria: la frase dice solo chi si ferma «sotto questo livello».
  val stateText = stringResource(
    when {
      chargingOnly -> R.string.battery_guard_state_charging
      idle -> R.string.battery_guard_state_none
      shown <= 0 -> R.string.battery_guard_state_zero
      shown >= BatteryGuard.FULL_PERCENT -> R.string.battery_guard_state_full
      state == BatteryGuardState.BOTH -> R.string.battery_guard_state_both
      state == BatteryGuardState.BACKUP_ONLY -> R.string.battery_guard_state_backup
      else -> R.string.battery_guard_state_analysis
    },
  )
  val title = stringResource(R.string.battery_guard_title)
  val levelWord = stringResource(R.string.battery_guard_level, shown)
  val faded = Modifier.alpha(if (idle) Dimens.UnusedAlpha else 1f)
  // A testo molto grande la batteria sta sopra il testo: la frase va a capo quanto serve e non si taglia mai.
  val stacked = LocalDensity.current.fontScale >= Dimens.StackedFontScale
  val glyph: @Composable () -> Unit = { BatteryGlyph(fraction = shown / PERCENT, stopping = !idle, modifier = faded) }
  val number: @Composable () -> Unit = {
    Text(text = levelWord, style = FluidTextStyles.largeNumeric, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, modifier = faded)
  }
  val sentence: @Composable () -> Unit = {
    Text(text = stateText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }

  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Dimens.NoteGap)) {
    FluidListGroup {
      val header = Modifier
        .fillMaxWidth()
        .padding(Dimens.HeaderPadding)
        .clearAndSetSemantics { contentDescription = "$title: $levelWord. $stateText" }
      if (stacked) {
        Column(modifier = header, verticalArrangement = Arrangement.spacedBy(Dimens.HeaderGap)) {
          glyph()
          Column(verticalArrangement = Arrangement.spacedBy(Dimens.LineGap)) {
            number()
            sentence()
          }
        }
      } else {
        Row(modifier = header, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.HeaderGap)) {
          glyph()
          Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.LineGap)) {
            number()
            sentence()
          }
        }
      }
      Box(modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.SliderInset).then(faded)) {
        FluidSlider(
          // La pista va da 0 a 85 con le tacche solo da 5 a 80: i passi sono tutti uguali e il pomello non si schiaccia contro gli estremi (BU-4). Un livello salvato fuori scala (0, 100) lo tiene alla
          // tacca piu' vicina; il numero accanto dice il vero.
          value = shown.coerceIn(BatteryGuard.MIN_PERCENT, BatteryGuard.MAX_PERCENT).toFloat(),
          onValueChange = { gesture.onDrag(BatteryGuard.fromSlider(it)) },
          valueRange = BatteryGuard.TRACK_START.toFloat()..BatteryGuard.TRACK_END.toFloat(),
          enabled = enabled,
          onValueChangeFinished = { gesture.onRelease()?.let(onLevelChange) },
          snapValues = BatteryGuard.levels.map { it.toFloat() },
          snapThreshold = 1f,
          contentDescription = stringResource(R.string.battery_guard_slider),
          stateDescription = levelWord,
        )
      }
      FluidListDivider()
      if (value.backupOnBattery) {
        GuardSwitch(
          label = stringResource(R.string.battery_guard_backup),
          icon = { GalleryIconTile(icon = Icons.Rounded.CloudUpload, family = GalleryIconFamily.Backup, status = if (value.forBackup) GalleryIconStatus.Waiting else null, statusDescription = null) },
          checked = value.forBackup,
          enabled = enabled,
          onChange = onForBackupChange,
        )
      } else {
        GuardChargingOnly(
          label = stringResource(R.string.battery_guard_backup_name),
          icon = { GalleryIconTile(icon = Icons.Rounded.CloudUpload, family = GalleryIconFamily.Backup) },
        )
      }
      FluidListDivider()
      if (value.analysisOnBattery) {
        GuardSwitch(
          label = stringResource(R.string.battery_guard_analysis),
          icon = { GalleryIconTile(icon = Icons.Rounded.ImageSearch, family = GalleryIconFamily.Search, status = if (value.forAnalysis) GalleryIconStatus.Waiting else null, statusDescription = null) },
          checked = value.forAnalysis,
          enabled = enabled,
          onChange = onForAnalysisChange,
        )
      } else {
        GuardChargingOnly(
          label = stringResource(R.string.battery_guard_analysis_name),
          icon = { GalleryIconTile(icon = Icons.Rounded.ImageSearch, family = GalleryIconFamily.Search) },
        )
      }
    }
    if (saveResult?.ok == false) {
      GalleryWarningNote(text = stringResource(R.string.battery_guard_save_failed), modifier = Modifier.padding(horizontal = Dimens.NoteInset))
    }
  }
}

/** Lo scheletro del controllo (BU-11): la sagoma della batteria, il numero e due righe di frase, la pista del cursore e le due righe. Alto come il controllo vero, cosi' la scheda non salta quando arriva il valore. */
@Composable
private fun GuardSkeleton(modifier: Modifier) {
  val label = stringResource(R.string.battery_guard_loading)
  FluidListGroup(modifier = modifier.clearAndSetSemantics { contentDescription = label }) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(Dimens.HeaderPadding),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Dimens.HeaderGap),
    ) {
      FluidSkeleton(modifier = Modifier.width(Dimens.BatteryWidth), height = Dimens.BatteryHeight)
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.LineGap)) {
        FluidSkeleton(modifier = Modifier.width(Dimens.SkeletonNumberWidth), height = Dimens.SkeletonNumberHeight)
        FluidSkeleton(modifier = Modifier.fillMaxWidth())
        FluidSkeleton(modifier = Modifier.fillMaxWidth(Dimens.SkeletonShortLine))
      }
    }
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.SliderInset, vertical = Dimens.SkeletonTrackPadding)) {
      FluidSkeleton(modifier = Modifier.fillMaxWidth(), height = Dimens.SkeletonTrackHeight)
    }
    FluidListDivider()
    FluidSkeletonRow(trailing = true, lines = 1)
    FluidListDivider()
    FluidSkeletonRow(trailing = true, lines = 1)
  }
}

/**
 * Il gesto sul cursore (BS-4, BS-5, BS2-2), senza grafica e senza coroutine: **il dito e il valore salvato sono due cose**. Mentre il dito e' sul cursore il livello mostrato e' quello del dito, e un
 * riscontro del salvataggio di una scelta **precedente** non lo azzera; a dito alzato il livello mostrato e' quello scelto finche' il salvataggio non lo conferma ([onSaved] con lo stesso valore)
 * o non fallisce ([onFailed] **con il livello di quella richiesta**: si torna al valore salvato solo se e' la scelta ancora in volo; l'esito di una richiesta piu' vecchia non tocca la piu' nuova).
 */
class BatteryLevelGesture(saved: Int) {
  private var savedLevel = saved
  private var dragging = false
  private var pending: Int? = null
  private val shownState = mutableIntStateOf(saved)

  /** Il livello da mostrare adesso (il vero, anche fuori griglia, finche' non si sceglie). */
  val shown: Int get() = shownState.intValue

  fun onDrag(level: Int) {
    dragging = true
    shownState.intValue = level
  }

  /** Il dito si alza: il livello da salvare, o null se e' quello che c'e' gia' (niente da scrivere). */
  fun onRelease(): Int? {
    dragging = false
    val chosen = shownState.intValue
    return if (chosen == savedLevel) {
      pending = null
      null
    } else {
      pending = chosen
      chosen
    }
  }

  /** Il livello salvato e' cambiato (un salvataggio riuscito, o l'altra pagina). */
  fun onSaved(level: Int) {
    savedLevel = level
    if (dragging) return
    if (pending == level) pending = null
    // Con una scelta ancora in volo (salvata un'altra, piu' vecchia) si resta su quella scelta: il riscontro vecchio non la cancella.
    if (pending == null) shownState.intValue = level
  }

  /**
   * Un salvataggio non e' riuscito: [level] e' il livello **di quella richiesta** (null = una scelta che non e' il livello, un interruttore: niente da riportare). Si torna al valore salvato solo se
   * e' la scelta ancora in volo; non si tocca il dito se e' sul cursore. Un fallimento vecchio, arrivato dopo una scelta piu' nuova, non la cancella.
   */
  fun onFailed(level: Int?) {
    if (level == null || level != pending) return
    pending = null
    if (!dragging) shownState.intValue = savedLevel
  }
}

/**
 * La spiegazione che sta dietro la «i» sotto il controllo, la stessa in ogni pagina che lo ospita: col cavo e per «Fai ora» e «Analizza ora» questa soglia non vale, le anteprime di preparazione si
 * fermano solo se backup e analisi si fermano tutti e due (quelle in alta qualita' se ne ferma almeno uno), e Android puo' rinviare i lavori a batteria quasi scarica.
 */
@Composable
fun GalleryBatteryGuardNote(modifier: Modifier = Modifier) {
  GalleryInfoRow(
    label = stringResource(R.string.battery_guard_info_label),
    detail = stringResource(R.string.battery_guard_info_detail),
    modifier = modifier,
  )
}

/** Un'attivita' e il suo interruttore «Ferma…»: la riga intera si tocca, per TalkBack e' un interruttore con il suo stato. */
@Composable
private fun GuardSwitch(label: String, icon: @Composable () -> Unit, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
  val stateText = stringResource(if (checked) R.string.battery_guard_on else R.string.battery_guard_off)
  FluidListRow(
    title = label,
    subtitle = "",
    leading = icon,
    leadingTile = false,
    badge = { FluidSwitch(checked = checked, onCheckedChange = null, enabled = enabled) },
    disclosure = false,
    onClick = if (enabled) ({ onChange(!checked) }) else null,
    modifier = Modifier.semantics(mergeDescendants = true) {
      role = Role.Switch
      stateDescription = stateText
    },
  )
}

/**
 * Un'attivita' che con le regole di adesso lavora solo in carica (BU-3): il segno della carica e due parole al posto di un interruttore che non farebbe niente. Con il testo grande ([Dimens.RowStackedFontScale])
 * le due parole vanno **sotto il nome** e il segno resta a destra, cosi' il nome non si spezza su tre righe.
 */
@Composable
private fun GuardChargingOnly(label: String, icon: @Composable () -> Unit) {
  val only = stringResource(R.string.battery_guard_only_charging)
  val stacked = LocalDensity.current.fontScale >= Dimens.RowStackedFontScale
  val sign: @Composable () -> Unit = {
    Icon(imageVector = Icons.Rounded.BatteryChargingFull, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(Dimens.ChargingGlyph))
  }
  FluidListRow(
    title = label,
    subtitle = if (stacked) only else "",
    leading = icon,
    leadingTile = false,
    badge = {
      if (stacked) {
        sign()
      } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.LineGap)) {
          sign()
          Text(text = only, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    },
    disclosure = false,
    modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$label: $only" },
  )
}

/**
 * La batteria: una cella con il suo polo, **piena fino a [fraction] del colore del «fermo per la batteria»** (la zona in cui si aspetta) e, se [stopping], con la clessidra dell'attesa sull'angolo.
 * Si disegna con i pezzi del tema, si riempie con la molla dell'engine e, con il movimento ridotto, cambia di colpo. Per TalkBack e' muta: la descrizione la da' la riga.
 */
@Composable
private fun BatteryGlyph(fraction: Float, stopping: Boolean, modifier: Modifier = Modifier) {
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  // Un livello basso ma non nullo (5%) deve vedersi: la cella non e' mai piu' sottile di un filo.
  val target = if (fraction > 0f) fraction.coerceAtLeast(Dimens.MinFill) else 0f
  val filled = remember { Animatable(target) }
  LaunchedEffect(target, reduced) {
    if (reduced) filled.snapTo(target) else filled.animateTo(target, FluidMotion.smooth())
  }
  val outline = MaterialTheme.colorScheme.onSurfaceVariant
  val stopColor = rememberGalleryIconColors(GalleryIconFamily.People).top
  val body = ContinuousCornerShape(FluidRadius.Small)
  val pole = ContinuousCornerShape(FluidRadius.Small)
  Box(modifier = modifier.clearAndSetSemantics { }) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        modifier = Modifier
          .size(width = Dimens.BatteryWidth, height = Dimens.BatteryHeight)
          .clip(body)
          .background(MaterialTheme.colorScheme.surfaceContainerHighest)
          .border(BorderStroke(Dimens.BatteryStroke, outline), body),
      ) {
        Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(filled.value.coerceIn(0f, 1f)).background(stopColor))
      }
      Box(modifier = Modifier.size(width = Dimens.PoleWidth, height = Dimens.PoleHeight).clip(pole).background(outline))
    }
    if (stopping) {
      GalleryStatusMark(
        status = GalleryIconStatus.Waiting,
        size = galleryStatusMarkSize(Dimens.BatteryHeight),
        contentDescription = null,
        modifier = Modifier.align(Alignment.BottomEnd).offset(x = Dimens.BadgeShiftX, y = Dimens.BadgeShiftY),
      )
    }
  }
}

private const val PERCENT = 100f

private object Dimens {
  val HeaderPadding = 16.dp
  val HeaderGap = 16.dp
  val LineGap = 4.dp
  val SliderInset = 16.dp
  val BatteryWidth = 64.dp
  val BatteryHeight = 32.dp
  val BatteryStroke = 2.dp
  val PoleWidth = 4.dp
  val PoleHeight = 12.dp
  val BadgeShiftX = 8.dp
  val BadgeShiftY = 10.dp
  val ChargingGlyph = 18.dp
  val NoteGap = 8.dp
  val NoteInset = 12.dp
  val SkeletonNumberWidth = 72.dp
  val SkeletonNumberHeight = 34.dp
  val SkeletonTrackHeight = 6.dp
  val SkeletonTrackPadding = 20.dp
  const val SkeletonShortLine = 0.6f
  const val UnusedAlpha = 0.4f
  const val MinFill = 0.07f
  const val StackedFontScale = 1.5f

  /** Da questo ingrandimento del testo «Solo in carica» va sotto il nome dell'attivita'. */
  const val RowStackedFontScale = 1.15f
}

/** Chi si ferma sotto il livello: dai due interruttori (gia' filtrati da chi puo' lavorare a batteria). */
enum class BatteryGuardState { BOTH, BACKUP_ONLY, ANALYSIS_ONLY, NONE }

/**
 * I valori scelti dall'utente e cio' che serve a mostrarli, come li vede il controllo.
 *
 * @property backupOnBattery il backup puo' lavorare a batteria con le regole di adesso (BU-3): se no, la riga dice «Solo in carica» e non c'e' un interruttore che non farebbe niente.
 * @property analysisOnBattery lo stesso per l'analisi (con il preset di serie «Notte» no).
 * @property backupNeedsPower il backup normale lavora solo in carica: la pagina del Backup mostra il segno della carica fra «Il backup parte con».
 */
@androidx.compose.runtime.Immutable
data class BatteryGuardValue(
  val levelPercent: Int = BatteryGuard.DEFAULT_PERCENT,
  val forBackup: Boolean = true,
  val forAnalysis: Boolean = true,
  val backupOnBattery: Boolean = true,
  val analysisOnBattery: Boolean = true,
  val backupNeedsPower: Boolean = false,
)

/**
 * L'esito di **una** scrittura (BS2-2): [id] e' unico per richiesta (cresce a ogni scelta), [level] e' il livello chiesto (null per un interruttore), [ok] dice se il valore e' stato davvero salvato.
 * Un fallimento di una richiesta non e' mai uguale a quello della richiesta dopo, anche se il livello e' lo stesso: la pagina lo applica al gesto di quella richiesta.
 */
@androidx.compose.runtime.Immutable
data class BatterySaveResult(val id: Long, val level: Int?, val ok: Boolean)

/**
 * La porta del controllo: `:app` la lega alle impostazioni delle regole (del dispositivo, non personali). Opzionale per chi la usa: senza, la sezione non si disegna
 * (una riga che non fa niente e' peggio di nessuna riga). Una sola per tutte le pagine che ospitano il controllo, cosi' mostrano sempre lo stesso stato.
 */
interface BatteryGuardPort {
  /** Il valore salvato; null finche' le impostazioni non sono lette («sto caricando», BS-5: mai un 20% e tutto acceso inventati). */
  val value: kotlinx.coroutines.flow.StateFlow<BatteryGuardValue?>

  /** Salva il livello (normalizzato al confine del dominio); vero se il valore salvato e' quello chiesto, falso se la scrittura non e' riuscita (BS-5). */
  suspend fun setLevel(percent: Int): Boolean

  suspend fun setForBackup(on: Boolean): Boolean

  suspend fun setForAnalysis(on: Boolean): Boolean
}

/** La logica del controllo, senza grafica: i passi, i limiti, lo stato. Pura, provata su JVM. */
object BatteryGuard {
  const val MIN_PERCENT = 5
  const val MAX_PERCENT = 80
  const val STEP = 5

  /** Il livello di prima, quando l'utente non ha mai scelto. */
  const val DEFAULT_PERCENT = 20

  /** Il livello al quale «a batteria non parte niente»: ogni batteria non piena e' sotto. */
  const val FULL_PERCENT = 100

  /** La pista va da 0 a 85 con le tacche solo da 5 a 80 (BU-4): i passi sono tutti uguali e le due punte non si schiacciano. Lo 0 e l'85 non si possono scegliere. */
  const val TRACK_START = MIN_PERCENT - STEP
  const val TRACK_END = MAX_PERCENT + STEP

  /** I passi del cursore: 5, 10, ... 80. */
  val levels: List<Int> = (MIN_PERCENT..MAX_PERCENT step STEP).toList()

  /** Il passo piu' vicino a [percent], dentro i limiti: 33 -> 35, 32 -> 30, 2 -> 5, 100 -> 80. */
  fun snap(percent: Int): Int {
    val stepped = ((percent.toDouble() / STEP).roundToInt()) * STEP
    return stepped.coerceIn(MIN_PERCENT, MAX_PERCENT)
  }

  /** Il livello che il cursore indica con il valore [slider] (anche fra due tacche): il passo piu' vicino. */
  fun fromSlider(slider: Float): Int = snap(slider.roundToInt())

  fun stateOf(forBackup: Boolean, forAnalysis: Boolean): BatteryGuardState = when {
    forBackup && forAnalysis -> BatteryGuardState.BOTH
    forBackup -> BatteryGuardState.BACKUP_ONLY
    forAnalysis -> BatteryGuardState.ANALYSIS_ONLY
    else -> BatteryGuardState.NONE
  }
}
