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
 * Con il cavo collegato il livello non conta mai, e «Fai ora» / «Analizza adesso» partono comunque: lo dice la «i» nella pagina che lo ospita, non questo componente.
 *
 * @param levelPercent il livello salvato (5..80 a passi di 5; un valore fuori griglia, ad esempio uno scritto da una versione piu' vecchia, si mostra sul passo piu' vicino
 *   e si salva solo se l'utente muove il cursore).
 * @param onLevelChange il nuovo livello, **solo a dito alzato** (una scrittura sola per scelta, non una per passo).
 */
@Composable
fun GalleryBatteryGuard(
  levelPercent: Int,
  forBackup: Boolean,
  forAnalysis: Boolean,
  onLevelChange: (Int) -> Unit,
  onForBackupChange: (Boolean) -> Unit,
  onForAnalysisChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  val saved = BatteryGuard.snap(levelPercent)
  // Mentre il dito e' sul cursore il livello e' quello del dito; a dito alzato torna a essere quello salvato (che nel frattempo ha seguito la scelta).
  var dragged by remember(saved) { mutableIntStateOf(saved) }
  val shown = dragged
  val state = BatteryGuard.stateOf(forBackup, forAnalysis)
  // Il numero sta una volta sola, grande, accanto alla batteria: la frase dice solo chi si ferma «sotto questo livello».
  val stateText = stringResource(
    when (state) {
      BatteryGuardState.BOTH -> R.string.battery_guard_state_both
      BatteryGuardState.BACKUP_ONLY -> R.string.battery_guard_state_backup
      BatteryGuardState.ANALYSIS_ONLY -> R.string.battery_guard_state_analysis
      BatteryGuardState.NONE -> R.string.battery_guard_state_none
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
        value = shown.toFloat(),
        onValueChange = { dragged = BatteryGuard.fromSlider(it) },
        valueRange = BatteryGuard.MIN_PERCENT.toFloat()..BatteryGuard.MAX_PERCENT.toFloat(),
        enabled = enabled,
        onValueChangeFinished = { if (dragged != saved) onLevelChange(dragged) },
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
      checked = forBackup,
      enabled = enabled,
      onChange = onForBackupChange,
    )
    FluidListDivider()
    GuardSwitch(
      label = stringResource(R.string.battery_guard_analysis),
      icon = { GalleryIconTile(icon = Icons.Rounded.ImageSearch, family = GalleryIconFamily.Search) },
      checked = forAnalysis,
      enabled = enabled,
      onChange = onForAnalysisChange,
    )
  }
}

/**
 * La spiegazione che sta dietro la «i» sotto il controllo, la stessa in ogni pagina che lo ospita: col cavo il livello non conta, le richieste dell'utente partono sempre,
 * e le attivita' che servono a tutti e due i gruppi (indice condiviso, miniature e anteprime) seguono sempre il livello.
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
  val value: kotlinx.coroutines.flow.StateFlow<BatteryGuardValue>

  suspend fun setLevel(percent: Int)

  suspend fun setForBackup(on: Boolean)

  suspend fun setForAnalysis(on: Boolean)
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
