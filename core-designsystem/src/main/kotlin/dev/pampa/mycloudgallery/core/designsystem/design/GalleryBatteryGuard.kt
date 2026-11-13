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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidSkeletonList
import dev.antigravity.fluidengine.ui.fluid.FluidSlider
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.designsystem.R
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
import dev.pampa.mycloudgallery.core.designsystem.media.galleryRingColor
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import kotlin.math.roundToInt

/**
 * La scelta del livello di batteria sotto cui il backup e l'analisi delle foto non partono (Pacchetto B, seconda beta). **Un componente solo**: sta nelle
 * impostazioni del Backup e, identico, in quelle dell'analisi; ognuna lo lega alle stesse impostazioni delle regole.
 *
 * Regola dell'utente: «se non sapessi leggere potrei usare l'app?». Quindi prima la figura: una batteria che si riempie fino al livello scelto con il numero accanto,
 * un cursore a passi di 5, e due interruttori con le icone che l'app usa gia' per il backup (la nuvola) e per l'analisi (la lente sulla foto). Lo stato si dice
 * **una volta**, sotto il numero, in poche parole. Niente colori, raggi o misure di testo scritti qui: tutto viene dal tema e dall'engine; con il movimento ridotto la
 * batteria cambia di colpo.
 *
 * Col cavo collegato questa soglia non vale, e non vale per «Fai ora» / «Analizza adesso»: lo dice la «i» ([GalleryBatteryGuardNote]), non questo componente.
 *
 * @param value il livello e i due interruttori **salvati**; null = le impostazioni non sono ancora lette: si vede lo scheletro (mai un 20% finto). Il livello e' quello **vero**
 *   (BS-4): un valore gia' salvato fuori griglia (33, o 0 e 100) si mostra com'e' finche' l'utente non sceglie; il cursore sta sulla tacca piu' vicina e i numeri dicono il vero.
 * @param onLevelChange il nuovo livello, **solo a dito alzato** (una scrittura sola per scelta, non una per passo).
 * @param saveFailed l'ultima scelta non si e' salvata (BS-5): il controllo torna al valore salvato e la frase lo dice, come le altre impostazioni.
 */
@Composable
fun GalleryBatteryGuard(
  value: BatteryGuardValue?,
  onLevelChange: (Int) -> Unit,
  onForBackupChange: (Boolean) -> Unit,
  onForAnalysisChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  saveFailed: Boolean = false,
) {
  if (value == null) {
    FluidListGroup(modifier = modifier) { FluidSkeletonList(rows = LOADING_ROWS, trailing = true, label = stringResource(R.string.battery_guard_loading)) }
    return
  }
  val saved = value.levelPercent
  // Il gesto vive da se': un riscontro di una scelta precedente (il livello salvato che cambia) non azzera il dito che e' di nuovo sul cursore (BS-5).
  val gesture = remember { BatteryLevelGesture(saved) }
  LaunchedEffect(saved) { gesture.onSaved(saved) }
  LaunchedEffect(saveFailed) { if (saveFailed) gesture.onFailed() }
  val shown = gesture.shown
  val state = BatteryGuard.stateOf(value.forBackup, value.forAnalysis)
  // Il numero sta una volta sola, grande, accanto alla batteria: la frase dice solo chi si ferma «sotto questo livello».
  val stateText = stringResource(
    when {
      state == BatteryGuardState.NONE -> R.string.battery_guard_state_none
      // 0%: nessuna batteria e' sotto zero, quindi niente si ferma (un livello salvato da prima, che il cursore non puo' scegliere).
      shown <= 0 -> R.string.battery_guard_state_zero
      state == BatteryGuardState.BOTH -> R.string.battery_guard_state_both
      state == BatteryGuardState.BACKUP_ONLY -> R.string.battery_guard_state_backup
      else -> R.string.battery_guard_state_analysis
    },
  )
  val title = stringResource(R.string.battery_guard_title)
  val levelWord = stringResource(R.string.battery_guard_level, shown)

  FluidListGroup(modifier = modifier) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(Dimens.HeaderPadding)
        .clearAndSetSemantics { contentDescription = "$title: $levelWord. $stateText" },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Dimens.HeaderGap),
    ) {
      // Con tutti e due gli interruttori spenti il livello non decide niente per backup e analisi: la figura sfuma, come una riga disattivata.
      val unused = Modifier.alpha(if (state == BatteryGuardState.NONE) Dimens.UnusedAlpha else 1f)
      BatteryGlyph(fraction = shown / PERCENT, modifier = unused)
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.LineGap)) {
        Text(
          text = levelWord,
          style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Bold),
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          modifier = unused,
        )
        Text(
          text = stateText,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.SliderInset)) {
      FluidSlider(
        // Il cursore sta sulla scala 5..80: un livello salvato fuori scala (0, 100) lo tiene alla punta, il numero accanto dice il vero.
        value = shown.coerceIn(BatteryGuard.MIN_PERCENT, BatteryGuard.MAX_PERCENT).toFloat(),
        onValueChange = { gesture.onDrag(BatteryGuard.fromSlider(it)) },
        valueRange = BatteryGuard.MIN_PERCENT.toFloat()..BatteryGuard.MAX_PERCENT.toFloat(),
        enabled = enabled,
        onValueChangeFinished = { gesture.onRelease()?.let(onLevelChange) },
        // Il cursore va a gradini di 5: ogni passo ha la sua tacca e il suo tocco, non un valore qualunque.
        snapValues = BatteryGuard.levels.map { it.toFloat() },
        snapThreshold = 1f,
        contentDescription = stringResource(R.string.battery_guard_slider),
        stateDescription = levelWord,
      )
    }
    FluidListDivider()
    GuardSwitch(
      label = stringResource(R.string.battery_guard_backup),
      icon = { GalleryIconTile(icon = Icons.Rounded.CloudUpload, family = GalleryIconFamily.Backup) },
      checked = value.forBackup,
      enabled = enabled,
      onChange = onForBackupChange,
    )
    FluidListDivider()
    GuardSwitch(
      label = stringResource(R.string.battery_guard_analysis),
      icon = { GalleryIconTile(icon = Icons.Rounded.ImageSearch, family = GalleryIconFamily.Search) },
      checked = value.forAnalysis,
      enabled = enabled,
      onChange = onForAnalysisChange,
    )
    if (saveFailed) {
      FluidListDivider()
      Text(
        text = stringResource(R.string.battery_guard_save_failed),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().padding(Dimens.HeaderPadding),
      )
    }
  }
}

/**
 * Il gesto sul cursore (BS-4, BS-5), senza grafica e senza coroutine: **il dito e il valore salvato sono due cose**. Mentre il dito e' sul cursore il livello mostrato e' quello del dito, e un
 * riscontro del salvataggio di una scelta **precedente** non lo azzera; a dito alzato il livello mostrato e' quello scelto finche' il salvataggio non lo conferma ([onSaved] con lo stesso valore)
 * o non fallisce ([onFailed]: si torna al valore salvato). Prima il livello mostrato era una funzione del solo valore salvato e un riscontro in ritardo cambiava la chiave dello stato del dito.
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

  /** L'ultimo salvataggio non e' riuscito: via la scelta in volo, si torna al valore salvato (non si tocca il dito se e' sul cursore). */
  fun onFailed() {
    pending = null
    if (!dragging) shownState.intValue = savedLevel
  }
}

/**
 * La spiegazione che sta dietro la «i» sotto il controllo, la stessa in ogni pagina che lo ospita: col cavo e per le richieste dell'utente questa soglia non vale, i lavori che servono
 * a tutti e due (indice condiviso, miniature e anteprime) si fermano solo con tutti e due gli interruttori accesi, e Android puo' rinviare i lavori a batteria quasi scarica.
 */
@Composable
fun GalleryBatteryGuardNote(modifier: Modifier = Modifier) {
  GalleryInfoRow(
    label = stringResource(R.string.battery_guard_info_label),
    detail = stringResource(R.string.battery_guard_info_detail),
    modifier = modifier,
  )
}

/** Un'attivita' e il suo interruttore: la riga intera si tocca, per TalkBack e' un interruttore con il suo stato. */
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
 * La batteria: una cella con il suo polo, piena fino a [fraction]. Si disegna con i pezzi del tema (superficie, accento della famiglia «Posti» come la piastrella della
 * batteria nel resto dell'app), si riempie con la molla dell'engine e, con il movimento ridotto, cambia di colpo. Per TalkBack e' muta: la descrizione la da' la riga.
 */
@Composable
private fun BatteryGlyph(fraction: Float, modifier: Modifier = Modifier) {
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  // Un livello basso ma non nullo (5%) deve vedersi: la cella non e' mai piu' sottile di un filo.
  val target = if (fraction > 0f) fraction.coerceAtLeast(Dimens.MinFill) else 0f
  val filled = remember { Animatable(target) }
  LaunchedEffect(target, reduced) {
    if (reduced) filled.snapTo(target) else filled.animateTo(target, FluidMotion.smooth())
  }
  val outline = MaterialTheme.colorScheme.onSurfaceVariant
  val body = ContinuousCornerShape(FluidRadius.Small)
  val pole = ContinuousCornerShape(FluidRadius.Small)
  Row(modifier = modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
    Box(
      modifier = Modifier
        .size(width = Dimens.BatteryWidth, height = Dimens.BatteryHeight)
        .clip(body)
        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        .border(BorderStroke(Dimens.BatteryStroke, outline), body),
    ) {
      Box(
        modifier = Modifier
          .fillMaxHeight()
          .fillMaxWidth(filled.value.coerceIn(0f, 1f))
          .background(galleryRingColor(GalleryIconFamily.Places)),
      )
    }
    Box(modifier = Modifier.size(width = Dimens.PoleWidth, height = Dimens.PoleHeight).clip(pole).background(outline))
  }
}

private const val PERCENT = 100f
private const val LOADING_ROWS = 3

private object Dimens {
  val HeaderPadding = 16.dp
  val HeaderGap = 16.dp
  val LineGap = 4.dp
  val SliderInset = 16.dp
  const val UnusedAlpha = 0.4f
  const val MinFill = 0.07f
  val BatteryWidth = 64.dp
  val BatteryHeight = 32.dp
  val BatteryStroke = 2.dp
  val PoleWidth = 4.dp
  val PoleHeight = 12.dp
}

/** I tre valori scelti dall'utente, come li mostra il controllo. */
@androidx.compose.runtime.Immutable
data class BatteryGuardValue(
  val levelPercent: Int = BatteryGuard.DEFAULT_PERCENT,
  val forBackup: Boolean = true,
  val forAnalysis: Boolean = true,
)

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

/** Chi si ferma sotto il livello: dai due interruttori. */
enum class BatteryGuardState { BOTH, BACKUP_ONLY, ANALYSIS_ONLY, NONE }

/** La logica del controllo, senza grafica: i passi, i limiti, lo stato. Pura, provata su JVM. */
object BatteryGuard {
  const val MIN_PERCENT = 5
  const val MAX_PERCENT = 80
  const val STEP = 5

  /** Il livello di prima, quando l'utente non ha mai scelto. */
  const val DEFAULT_PERCENT = 20

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
