package dev.pampa.mycloudgallery.core.designsystem.sheet

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NetworkCell
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidRowValue
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidTextStyles
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.R
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------------------------
// Il guscio
// ---------------------------------------------------------------------------------------------

/**
 * Il foglio di conferma: un pop-up in vetro che sale dal basso (`FluidGlassModalPortal`, presentazione
 * `Sheet`), per le **quattro** decisioni che l'app non prende mai da sola (piano, §2): dove cancellare,
 * condividere con la famiglia, usare i dati mobili, approvare un dispositivo.
 *
 * Due regole del piano vivono qui e non nel chiamante:
 *  - un foglio di conferma e' **sempre** un'azione locale scelta dall'utente: nessuna azione
 *    distruttiva parte da dati letti dal NAS (7.4, §3 "Sicurezza dei dati");
 *  - mentre e' aperto la finestra **scarta i tocchi che passano attraverso una sovrapposizione**
 *    (`filterTouchesWhenObscured`): un'app che disegna sopra non puo' far premere "Elimina" al posto
 *    dell'utente (tapjacking, piano 1.5).
 *
 * Il vetro e il trascinamento per chiudere sono dell'engine. Il foglio sta alla radice dell'app, in un
 * `FluidGlassModalHost`: senza host nello scope non si apre, e non va in crash.
 *
 * @param paneTitle il titolo per il lettore di schermo: il foglio e' un pannello, e TalkBack lo annuncia.
 */
@Composable
fun GalleryConfirmSheet(
  visible: Boolean,
  onDismissRequest: () -> Unit,
  paneTitle: String,
  content: @Composable ColumnScope.() -> Unit,
) {
  val view = LocalView.current
  DisposableEffect(view, visible) {
    val previous = view.filterTouchesWhenObscured
    if (visible) view.filterTouchesWhenObscured = true
    onDispose { view.filterTouchesWhenObscured = previous }
  }
  FluidGlassModalPortal(
    visible = visible,
    onDismissRequest = onDismissRequest,
    presentation = FluidGlassModalPresentation.Sheet,
    paneTitle = paneTitle,
    content = content,
  )
}

/** Le misure di un foglio di conferma. */
object GallerySheetDefaults {

  /**
   * L'altezza massima di un foglio dell'engine (`FluidSheetMaxHeight`, privato nell'engine): oltre,
   * il foglio taglia. I fogli della galleria ci stanno dentro, e il loro corpo scorre se serve.
   */
  val MaxHeight: Dp = 640.dp
}

/** Il tono dell'icona in testa a un foglio: quale famiglia di ruoli la colora. */
enum class GallerySheetTone { Brand, Destructive, Caution }

/**
 * La struttura comune dei quattro fogli: la piastrella con l'icona, il titolo, la frase, un corpo
 * (le informazioni della decisione) e i bottoni in fondo.
 *
 * I bottoni sono `FluidButtonSize.Large`: 50 dp, sopra i 48 del bersaglio di tocco (il `Medium`
 * dell'engine e' 42). In fondo il foglio lascia il posto alla barra di navigazione di sistema.
 */
@Composable
fun GallerySheetScaffold(
  icon: ImageVector,
  tone: GallerySheetTone,
  title: String,
  message: String?,
  modifier: Modifier = Modifier,
  body: @Composable ColumnScope.() -> Unit = {},
  actions: @Composable ColumnScope.() -> Unit,
) {
  val s = MaterialTheme.colorScheme
  val (container, glyph) = when (tone) {
    GallerySheetTone.Brand -> s.primaryContainer to s.onPrimaryContainer
    GallerySheetTone.Destructive -> s.errorContainer to s.error
    GallerySheetTone.Caution -> s.tertiaryContainer to s.onTertiaryContainer
  }
  Column(
    modifier = modifier
      .fillMaxWidth()
      .navigationBarsPadding()
      .padding(horizontal = GalleryDimens.SheetPadding)
      .padding(top = SheetTopPadding, bottom = SheetBottomPadding),
    verticalArrangement = Arrangement.spacedBy(SheetGap),
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(SheetHeaderGap),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier
          .size(GalleryDimens.SheetIconTile)
          .clip(ContinuousCornerShape(FluidRadius.Control))
          .background(container),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          modifier = Modifier.size(SheetGlyph),
          tint = glyph,
        )
      }
      Text(
        text = title,
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.titleLarge,
        color = s.onSurface,
      )
    }
    // Il corpo scorre, i bottoni no. Un foglio dell'engine e' alto al piu' [GallerySheetDefaults.MaxHeight]
    // e non scorre da se': con una lista lunga, o con il testo ingrandito, i bottoni in fondo sarebbero
    // tagliati e la decisione non si potrebbe prendere. Cosi' restano sempre in vista.
    Column(
      modifier = Modifier
        .weight(1f, fill = false)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(SheetGap),
    ) {
      if (!message.isNullOrBlank()) {
        // Il testo che sta direttamente sul vetro prende il colore pieno (`onSurface`), non il secondario:
        // sul vetro, sopra una foto qualunque, il grigio del secondario non regge il contrasto.
        Text(
          text = message,
          style = MaterialTheme.typography.bodyMedium,
          color = s.onSurface,
        )
      }
      body()
    }
    Column(verticalArrangement = Arrangement.spacedBy(ButtonGap), content = actions)
  }
}

// ---------------------------------------------------------------------------------------------
// (a) Elimina, con scelta del luogo
// ---------------------------------------------------------------------------------------------

/** Dove va a finire l'eliminazione: solo dal telefono, oppure da ogni posto in cui il media si trova. */
enum class GalleryDeleteScope(@StringRes val label: Int, @StringRes val detail: Int) {
  PhoneOnly(R.string.delete_scope_phone, R.string.delete_scope_phone_detail),
  Everywhere(R.string.delete_scope_everywhere, R.string.delete_scope_everywhere_detail),
}

/** Che tipo di posto e' una posizione del media. */
enum class GalleryDeleteLocationKind { Phone, NasPrivate, NasShared }

/**
 * Una posizione in cui il media si trova. Il testo viene dal chiamante (conosce l'account, la
 * cartella): qui si decide solo come si mostra. [deletable] e' falso se l'utente non ha il diritto di
 * eliminare li' (un'area comune in sola lettura, E-D11): "Ovunque" agisce solo dove si hanno i diritti, e
 * l'elenco dice dove no.
 */
@Immutable
data class GalleryDeleteLocation(
  val title: String,
  val kind: GalleryDeleteLocationKind,
  val detail: String? = null,
  val deletable: Boolean = true,
)

/** Se questa posizione verra' toccata dalla scelta [scope]. */
fun GalleryDeleteLocation.isAffectedBy(scope: GalleryDeleteScope): Boolean =
  deletable && (scope == GalleryDeleteScope.Everywhere || kind == GalleryDeleteLocationKind.Phone)

/**
 * Il contenuto del foglio "Elimina" (piano, E-D1 ed E-D11).
 *
 *  - Con piu' scelte possibili ([scopes] ne ha due) un controllo a segmenti: **Solo dal telefono** /
 *    **Ovunque**. Con una sola (il media sta in un posto solo, E-D2) non c'e' scelta da fare e il
 *    controllo non compare.
 *  - L'**elenco delle posizioni** dice, per ognuna, che cosa succede con la scelta di adesso: "Verra'
 *    eliminata", "Resta", o "Non hai i permessi" per un'area in sola lettura. Cosi' l'utente vede
 *    l'effetto *prima* di premere.
 *  - La nota sul **cestino di 30 giorni**: niente di quello che si elimina qui e' perso per sempre.
 *
 * Il bottone distruttivo e' uno solo, e dice cosa fa ("Elimina dal telefono", "Elimina ovunque").
 */
@Composable
fun GalleryDeleteSheetContent(
  count: Int,
  scope: GalleryDeleteScope,
  onScopeChange: (GalleryDeleteScope) -> Unit,
  locations: List<GalleryDeleteLocation>,
  onConfirm: () -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
  scopes: List<GalleryDeleteScope> = GalleryDeleteScope.entries,
) {
  GallerySheetScaffold(
    icon = Icons.Rounded.DeleteForever,
    tone = GallerySheetTone.Destructive,
    title = pluralStringResource(R.plurals.delete_title, count, count),
    message = null,
    modifier = modifier,
    body = {
      // Tutto cio' che si legge e si tocca sta in una scheda piena: sul vetro restano solo il titolo e la
      // frase, che hanno il colore pieno. Un selettore o una nota sopra il vetro dipendono dalla foto
      // che c'e' dietro, e dietro un foglio c'e' sempre una foto.
      //
      // La scelta del luogo e' una **lista di opzioni**, non un controllo a segmenti: due righe da 48 dp
      // con la loro conseguenza scritta sotto, quella scelta evidenziata e la spunta a destra. Per una
      // decisione che cancella e' la forma piu' chiara, e la piu' facile da toccare.
      if (scopes.size > 1) {
        FluidListGroup {
          scopes.forEachIndexed { index, option ->
            if (index > 0) FluidListDivider()
            DeleteScopeRow(option = option, selected = option == scope, onSelect = { onScopeChange(option) })
          }
        }
      }
      FluidListGroup {
        locations.forEachIndexed { index, location ->
          if (index > 0) FluidListDivider()
          DeleteLocationRow(location, scope)
        }
        FluidListDivider()
        SheetCaption(stringResource(R.string.delete_trash_note))
      }
    },
    actions = {
      FluidButton(
        text = stringResource(
          if (scope == GalleryDeleteScope.PhoneOnly) R.string.delete_confirm_phone else R.string.delete_confirm_everywhere,
        ),
        onClick = onConfirm,
        style = FluidButtonStyle.Destructive,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
      FluidButton(
        text = stringResource(R.string.action_cancel),
        onClick = onCancel,
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
    },
  )
}

@Composable
private fun DeleteScopeRow(option: GalleryDeleteScope, selected: Boolean, onSelect: () -> Unit) {
  val label = stringResource(option.label)
  FluidListRow(
    title = label,
    subtitle = stringResource(option.detail),
    disclosure = false,
    selected = selected,
    onClick = onSelect,
    badge = { SheetRadio(selected = selected) },
    modifier = Modifier.semantics(mergeDescendants = true) {
      role = Role.RadioButton
      this.selected = selected
    },
  )
}

/** Il segno di un'opzione scelta: un cerchio dell'accento con la spunta, o un anello vuoto. */
@Composable
private fun SheetRadio(selected: Boolean) {
  val s = MaterialTheme.colorScheme
  Box(
    modifier = Modifier
      .size(SheetRadioSize)
      .clip(FluidCapsuleShape)
      .then(
        if (selected) {
          Modifier.background(s.primary)
        } else {
          Modifier.border(width = SheetRadioRing, color = s.outline, shape = FluidCapsuleShape)
        },
      ),
    contentAlignment = Alignment.Center,
  ) {
    if (selected) {
      Icon(
        imageVector = Icons.Rounded.Check,
        contentDescription = null,
        modifier = Modifier.size(SheetRadioCheck),
        tint = s.onPrimary,
      )
    }
  }
}

@Composable
private fun DeleteLocationRow(location: GalleryDeleteLocation, scope: GalleryDeleteScope) {
  val affected = location.isAffectedBy(scope)
  val icon = when {
    !location.deletable -> Icons.Rounded.Lock
    location.kind == GalleryDeleteLocationKind.Phone -> Icons.Rounded.PhoneAndroid
    location.kind == GalleryDeleteLocationKind.NasPrivate -> Icons.Rounded.Storage
    else -> Icons.Rounded.Group
  }
  FluidListRow(
    title = location.title,
    subtitle = location.detail.orEmpty(),
    tone = if (affected) FluidTone.Danger else FluidTone.Neutral,
    disclosure = false,
    leading = { Icon(imageVector = icon, contentDescription = null) },
    labels = {
      FluidStatusBadge(
        label = stringResource(
          when {
            affected -> R.string.delete_location_will_delete
            !location.deletable -> R.string.delete_location_no_permission
            else -> R.string.delete_location_stays
          },
        ),
        tone = if (affected) FluidTone.Danger else FluidTone.Neutral,
      )
    },
  )
}

/** Il foglio "Elimina" completo, nel suo pop-up. Vedi [GalleryDeleteSheetContent]. */
@Composable
fun GalleryDeleteSheet(
  visible: Boolean,
  count: Int,
  scope: GalleryDeleteScope,
  onScopeChange: (GalleryDeleteScope) -> Unit,
  locations: List<GalleryDeleteLocation>,
  onConfirm: () -> Unit,
  onDismissRequest: () -> Unit,
  scopes: List<GalleryDeleteScope> = GalleryDeleteScope.entries,
) {
  GalleryConfirmSheet(
    visible = visible,
    onDismissRequest = onDismissRequest,
    paneTitle = pluralStringResource(R.plurals.delete_title, count, count),
  ) {
    GalleryDeleteSheetContent(
      count = count,
      scope = scope,
      onScopeChange = onScopeChange,
      locations = locations,
      onConfirm = onConfirm,
      onCancel = onDismissRequest,
      scopes = scopes,
    )
  }
}

// ---------------------------------------------------------------------------------------------
// (b) Condividi con la famiglia
// ---------------------------------------------------------------------------------------------

/**
 * Il contenuto del foglio "Condividi con la famiglia" (piano, 7.4).
 *
 * La frase e' quella del piano — "N foto verranno spostate nell'area comune e le vedranno tutti i
 * familiari" — perche' e' la cosa che l'utente deve aver letto prima di premere. Sotto, il riepilogo
 * (album, permessi) e la dichiarazione che i permessi li fa rispettare l'app, non il NAS (proposta 7).
 */
@Composable
fun GalleryShareSheetContent(
  count: Int,
  onConfirm: () -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
  albumName: String? = null,
  permissionLabel: String? = null,
) {
  GallerySheetScaffold(
    icon = Icons.Rounded.Group,
    tone = GallerySheetTone.Brand,
    title = stringResource(R.string.share_title),
    message = pluralStringResource(R.plurals.share_message, count, count),
    modifier = modifier,
    body = {
      FluidListGroup {
        albumName?.let {
          FluidListRow(
            title = stringResource(R.string.share_row_album),
            subtitle = "",
            disclosure = false,
            badge = { FluidRowValue(text = it) },
          )
          FluidListDivider()
        }
        permissionLabel?.let {
          FluidListRow(
            title = stringResource(R.string.share_row_permission),
            subtitle = "",
            disclosure = false,
            badge = { FluidRowValue(text = it) },
          )
          FluidListDivider()
        }
        SheetCaption(stringResource(R.string.share_footnote))
      }
    },
    actions = {
      FluidButton(
        text = stringResource(R.string.share_confirm),
        onClick = onConfirm,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
      FluidButton(
        text = stringResource(R.string.action_cancel),
        onClick = onCancel,
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
    },
  )
}

/** Il foglio "Condividi con la famiglia" completo, nel suo pop-up. */
@Composable
fun GalleryShareSheet(
  visible: Boolean,
  count: Int,
  onConfirm: () -> Unit,
  onDismissRequest: () -> Unit,
  albumName: String? = null,
  permissionLabel: String? = null,
) {
  GalleryConfirmSheet(
    visible = visible,
    onDismissRequest = onDismissRequest,
    paneTitle = stringResource(R.string.share_title),
  ) {
    GalleryShareSheetContent(
      count = count,
      onConfirm = onConfirm,
      onCancel = onDismissRequest,
      albumName = albumName,
      permissionLabel = permissionLabel,
    )
  }
}

// ---------------------------------------------------------------------------------------------
// (c) Dati mobili
// ---------------------------------------------------------------------------------------------

/**
 * Il contenuto del foglio "Usare i dati mobili?" con la stima (piano, proposta 10: "Fai ora" scavalca
 * le regole, ma sui dati mobili chiede conferma mostrando la stima).
 *
 * La stima e' il numero grande, in cifre tabulari. Se si conosce il tetto mensile ([budgetLabel],
 * [budgetUsedFraction]) una barra mostra quanto ne resta: l'utente decide sapendo quanto costa e quanto
 * gli rimane. Le stringhe formattate (quantita' di dati) le fa il chiamante, che ha le regole del
 * locale e delle unita'.
 */
@Composable
fun GalleryMobileDataSheetContent(
  estimate: String,
  onConfirm: () -> Unit,
  onWaitForWifi: () -> Unit,
  modifier: Modifier = Modifier,
  budgetLabel: String? = null,
  budgetUsedFraction: Float? = null,
) {
  GallerySheetScaffold(
    icon = Icons.Rounded.NetworkCell,
    tone = GallerySheetTone.Caution,
    title = stringResource(R.string.mobile_data_title),
    message = stringResource(R.string.mobile_data_message),
    modifier = modifier,
    body = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(ContinuousCornerShape(FluidRadius.Group))
          .background(MaterialTheme.colorScheme.surfaceContainerLow)
          .padding(MobileDataPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MobileDataGap),
      ) {
        Text(
          text = estimate,
          style = FluidTextStyles.largeNumeric,
          color = MaterialTheme.colorScheme.onSurface,
          textAlign = TextAlign.Center,
        )
        Text(
          text = stringResource(R.string.mobile_data_estimate_caption),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
        if (budgetUsedFraction != null) {
          FluidProgressBar(progress = { budgetUsedFraction }, modifier = Modifier.padding(top = MobileDataGap))
        }
        if (budgetLabel != null) {
          Text(
            text = budgetLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      }
    },
    actions = {
      FluidButton(
        text = stringResource(R.string.mobile_data_confirm),
        onClick = onConfirm,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
      FluidButton(
        text = stringResource(R.string.mobile_data_wait),
        onClick = onWaitForWifi,
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
    },
  )
}

/** Il foglio "Usare i dati mobili?" completo, nel suo pop-up. */
@Composable
fun GalleryMobileDataSheet(
  visible: Boolean,
  estimate: String,
  onConfirm: () -> Unit,
  onWaitForWifi: () -> Unit,
  onDismissRequest: () -> Unit,
  budgetLabel: String? = null,
  budgetUsedFraction: Float? = null,
) {
  GalleryConfirmSheet(
    visible = visible,
    onDismissRequest = onDismissRequest,
    paneTitle = stringResource(R.string.mobile_data_title),
  ) {
    GalleryMobileDataSheetContent(
      estimate = estimate,
      onConfirm = onConfirm,
      onWaitForWifi = onWaitForWifi,
      budgetLabel = budgetLabel,
      budgetUsedFraction = budgetUsedFraction,
    )
  }
}

// ---------------------------------------------------------------------------------------------
// (d) Approvazione di un nuovo dispositivo
// ---------------------------------------------------------------------------------------------

/**
 * La richiesta di un nuovo dispositivo (piano, 2.3): chi e', da dove, con quale impronta. Tutto cio' che
 * serve all'admin per riconoscerlo prima di fidarsene; i testi li compone chi li conosce.
 */
@Immutable
data class GalleryDeviceRequest(
  val deviceName: String,
  val profileName: String?,
  val model: String,
  val requestedAgo: String,
  val fingerprint: String,
)

/**
 * Il contenuto del foglio "Nuovo dispositivo" (piano, 2.3: "Nuovo dispositivo: Tablet, profilo Mamma.
 * Approvare?"). Nome, profilo, modello, quando ha chiesto e l'**impronta**: e' la cosa che l'admin
 * confronta con quella mostrata sul dispositivo nuovo, ed e' per questo che sta in cifre tabulari.
 *
 * Approvare e rifiutare sono i due bottoni; "Rifiuta" e' quello distruttivo (e' una revoca). Senza
 * una scelta esplicita il dispositivo resta in sola lettura: chiudere il foglio non approva niente.
 */
@Composable
fun GalleryDeviceApprovalSheetContent(
  request: GalleryDeviceRequest,
  onApprove: () -> Unit,
  onReject: () -> Unit,
  modifier: Modifier = Modifier,
) {
  GallerySheetScaffold(
    icon = Icons.Rounded.Devices,
    tone = GallerySheetTone.Brand,
    title = stringResource(R.string.device_title),
    message = stringResource(R.string.device_message),
    modifier = modifier,
    body = {
      FluidListGroup {
        DeviceRow(R.string.device_row_name, request.deviceName)
        request.profileName?.let {
          FluidListDivider()
          DeviceRow(R.string.device_row_profile, it)
        }
        FluidListDivider()
        DeviceRow(R.string.device_row_model, request.model)
        FluidListDivider()
        DeviceRow(R.string.device_row_requested, request.requestedAgo)
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.device_row_fingerprint),
          subtitle = request.fingerprint,
          disclosure = false,
        )
        FluidListDivider()
        SheetCaption(stringResource(R.string.device_footnote))
      }
    },
    actions = {
      FluidButton(
        text = stringResource(R.string.device_approve),
        onClick = onApprove,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
      FluidButton(
        text = stringResource(R.string.device_reject),
        onClick = onReject,
        style = FluidButtonStyle.Destructive,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
    },
  )
}

@Composable
private fun DeviceRow(@StringRes label: Int, value: String) {
  FluidListRow(
    title = stringResource(label),
    subtitle = "",
    disclosure = false,
    badge = { FluidRowValue(text = value) },
  )
}

/** Il foglio "Nuovo dispositivo" completo, nel suo pop-up. */
@Composable
fun GalleryDeviceApprovalSheet(
  visible: Boolean,
  request: GalleryDeviceRequest,
  onApprove: () -> Unit,
  onReject: () -> Unit,
  onDismissRequest: () -> Unit,
) {
  GalleryConfirmSheet(
    visible = visible,
    onDismissRequest = onDismissRequest,
    paneTitle = stringResource(R.string.device_title),
  ) {
    GalleryDeviceApprovalSheetContent(request = request, onApprove = onApprove, onReject = onReject)
  }
}

/** Una nota in fondo a una scheda piena: piccola, nel secondario, con l'aria di una riga. */
@Composable
private fun SheetCaption(text: String) {
  Text(
    text = text,
    modifier = Modifier.padding(horizontal = SheetCaptionInset, vertical = SheetCaptionInset),
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

private val SheetCaptionInset = 16.dp
private val SheetRadioSize = 24.dp
private val SheetRadioRing = 1.5.dp
private val SheetRadioCheck = 16.dp
private val SheetCardInset = 12.dp
private val SheetCardGap = 8.dp
private val SheetTopPadding = 4.dp
private val SheetBottomPadding = 16.dp
private val SheetGap = 12.dp
private val SheetHeaderGap = 14.dp
private val SheetGlyph = 24.dp
private val ButtonGap = 8.dp
private val MobileDataPadding = 20.dp
private val MobileDataGap = 4.dp
