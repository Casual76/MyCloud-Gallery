package dev.pampa.mycloudgallery.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.pampa.mycloudgallery.MainActivity
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.memories.contract.LockScreenMode
import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.NotifyRules
import dev.pampa.mycloudgallery.core.memories.contract.NotifyTexts
import dev.pampa.mycloudgallery.core.memories.contract.TextRef
import dev.pampa.mycloudgallery.core.memories.notify.MemoryNotifyAccess
import dev.pampa.mycloudgallery.core.memories.notify.MemoryNotifyRevocation
import dev.pampa.mycloudgallery.core.memories.notify.NoNotifyEvents
import dev.pampa.mycloudgallery.core.memories.notify.NotifyAccessState
import dev.pampa.mycloudgallery.core.memories.notify.NotifyEvents
import dev.pampa.mycloudgallery.core.memories.notify.NotifyNotice
import dev.pampa.mycloudgallery.core.memories.notify.NotifyPublisher
import dev.pampa.mycloudgallery.core.memories.notify.PostedNotice
import dev.pampa.mycloudgallery.core.memories.shelf.MemoryVisibility
import dev.pampa.mycloudgallery.core.model.MediaId
import java.io.File
import java.io.IOException
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** La miniatura **locale** della copertina (replica, cache di `core-imaging`): mai il NAS e mai la rete per una notifica (D16). Null = non c'e': la notifica parte senza foto. */
fun interface NotifyThumbnails {
  suspend fun localThumbnail(id: MediaId, sizePx: Int): Bitmap?
}

object NoNotifyThumbnails : NotifyThumbnails {
  override suspend fun localThumbnail(id: MediaId, sizePx: Int): Bitmap? = null
}

/**
 * **La notifica dei ricordi** (Fase 9, P10; docs/fase9-progetto.md §2.6, D15, D16, D17, D18, D19; Q4, Q5 (a), Q9). Il lato Android di `core-memories/notify`:
 *
 * - **Pubblica** ([publish]): canale `memories` (id [AppNotifications.ID_MEMORY] 1009: una sola notifica, la nuova prende il posto della vecchia), `VISIBILITY_PRIVATE`
 *   con la **versione pubblica sempre generica** ("Un ricordo per te · Tocca per vederlo", `setPublicVersion`), senza suono ne' vibrazione, `setAutoCancel(true)`; il
 *   `PendingIntent` e' **immutabile**, esplicito verso la `MainActivity`, con la sola azione [NotifyRules.OPEN_ACTION]: **niente extra, niente dati, niente id** (D17).
 *   La modalita' generica non ha nemmeno la foto: lo garantisce `MemoryNotifyPolicy.content`; qui si disegna soltanto quello che la policy ha deciso.
 * - **Ricorda** che ricordo e' in tendina (`notified-memory`, in `stateDir` = `noBackupFilesDir/memories`, solo id e modalita': mai un titolo) per toglierla ([revalidate], D19).
 * - **Dice come sta il sistema** ([state], [refresh]): permesso, notifiche dell'app, canale (D18, E-NT1, E-NT2). **Non chiede mai niente**: il permesso lo chiede solo l'utente,
 *   dalla pagina delle impostazioni (P9, `PermissionAsks`), con [appNotificationSettingsIntent] e [channelSettingsIntent] come ripiego.
 * - Il testo viene dalle risorse ([NotifyTextResolver]); un luogo si legge da [places] (nome di un luogo, mai di una persona) e la miniatura da [thumbnails].
 *
 * Nessun allarme, nessun permesso nuovo. Nel log solo codici (D33).
 */
class MemoryNotifier(
  context: Context,
  memories: Memories,
  visibility: MemoryVisibility,
  private val stateDir: File,
  private val places: (Int) -> String? = { null },
  private val thumbnails: NotifyThumbnails = NoNotifyThumbnails,
  events: NotifyEvents = NoNotifyEvents,
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : NotifyPublisher, MemoryNotifyAccess {

  private val app: Context = context.applicationContext ?: context
  private val revocation = MemoryNotifyRevocation(this, memories, visibility, events)
  private val flow = MutableStateFlow(readState())

  override val state: StateFlow<NotifyAccessState> = flow

  override fun refresh() {
    flow.value = readState()
  }

  /** Rivaluta la notifica in tendina (D19): da chiamare a ogni cambio di nascosti e di scelte; toglie la notifica se il ricordo non si mostra piu'. Vero se l'ha tolta. */
  suspend fun revalidate(): Boolean = revocation.revalidate()

  /** Rivaluta anche a ogni cambio dello scaffale (un "Non mostrare", un "Mai", una pulizia dei nascosti). */
  fun watch(scope: CoroutineScope): Job = revocation.watch(scope)

  override suspend fun publish(notice: NotifyNotice): Boolean = withContext(dispatcher) {
    // Il canale deve esserci (idempotente), e il sistema puo' aver cambiato idea da quando la corsa ha controllato.
    AppNotifications(app).createChannels()
    refresh()
    if (!state.value.canNotify) return@withContext false
    val photo = if (notice.mode == LockScreenMode.TITLE_AND_PHOTO) notice.content.image?.let { thumbnails.localThumbnail(it, THUMB_PX) } else null
    val notification = build(notice, photo)
    // Prima si scrive chi e' in tendina, poi si pubblica: se il processo muore fra le due cose non c'e' una notifica senza ricordo registrato.
    writePosted(PostedNotice(notice.memory, notice.day, notice.mode, if (photo != null) notice.content.image else null))
    try {
      NotificationManagerCompat.from(app).notify(AppNotifications.ID_MEMORY, notification)
      true
    } catch (e: SecurityException) {
      deletePosted()
      GalleryLog.w(TAG) { "notifica dei ricordi non consentita" }
      false
    }
  }

  override fun cancel() {
    NotificationManagerCompat.from(app).cancel(AppNotifications.ID_MEMORY)
    deletePosted()
  }

  override suspend fun posted(): PostedNotice? = withContext(dispatcher) {
    val record = readPosted() ?: return@withContext null
    // Se l'utente l'ha gia' tolta (o toccata: `setAutoCancel`) non c'e' piu' niente da revocare.
    val active = app.getSystemService(NotificationManager::class.java)?.activeNotifications?.any { it.id == AppNotifications.ID_MEMORY } == true
    if (!active) {
      deletePosted()
      return@withContext null
    }
    record
  }

  /** Il ricordo che si apre dal tocco: quello della notifica in tendina (o, se non c'e' piu', null: si apre la pagina Ricordi, D17, E-NT8). */
  suspend fun openTarget(): MemoryId? = withContext(dispatcher) { readPosted()?.memory }

  // --- La notifica ---------------------------------------------------------------------------------------------------------------------

  private fun build(notice: NotifyNotice, photo: Bitmap?): Notification {
    val texts = NotifyTextResolver(app.resources, places)
    val content = notice.content
    // La versione pubblica (schermata di blocco): sempre le due frasi generiche, qualunque cosa dica quella privata.
    val publicVersion = NotificationCompat.Builder(app, AppNotifications.CHANNEL_MEMORIES)
      .setSmallIcon(R.drawable.ic_stat_gallery)
      .setContentTitle(texts.resolve(content.publicTitle))
      .setContentText(texts.resolve(content.publicText))
      .build()
    val builder = NotificationCompat.Builder(app, AppNotifications.CHANNEL_MEMORIES)
      .setSmallIcon(R.drawable.ic_stat_gallery)
      .setContentTitle(texts.resolve(content.title))
      .setContentText(texts.resolve(content.text))
      .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setPublicVersion(publicVersion)
      .setContentIntent(openMemoriesIntent())
      .setOnlyAlertOnce(true)
      .setSilent(true)
      .setAutoCancel(true)
    if (photo != null) builder.setLargeIcon(photo).setStyle(NotificationCompat.BigPictureStyle().bigPicture(photo).bigLargeIcon(null as Bitmap?))
    return builder.build()
  }

  /**
   * L'unica strada della notifica verso l'app: la `MainActivity` con **la sola azione** [NotifyRules.OPEN_ACTION]. **Immutabile**, esplicita, senza extra, dati,
   * categorie o id (D17, C151): chi la tocca apre i Ricordi dopo il blocco app, e basta. L'id del ricordo lo sa gia' l'app (lo scaffale).
   */
  internal fun openMemoriesIntent(): PendingIntent {
    val intent = Intent(app, MainActivity::class.java).setAction(NotifyRules.OPEN_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return PendingIntent.getActivity(app, AppNotifications.ID_MEMORY, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  }

  // --- Lo stato del sistema (D18) ------------------------------------------------------------------------------------------------------------

  private fun readState(): NotifyAccessState {
    val permission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
      ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    if (!permission) return NotifyAccessState.PERMISSION_MISSING
    if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return NotifyAccessState.APP_OFF
    val channel = app.getSystemService(NotificationManager::class.java)?.getNotificationChannel(AppNotifications.CHANNEL_MEMORIES)
    return if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) NotifyAccessState.CHANNEL_OFF else NotifyAccessState.GRANTED
  }

  /** Le impostazioni di sistema delle notifiche dell'app: per "Apri le impostazioni di Android" (D18). Le apre la pagina (P9) **a un tocco dell'utente**. */
  fun appNotificationSettingsIntent(): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

  /** Le impostazioni del solo canale "Ricordi": per "Spente dalle impostazioni di Android" (E-NT2). Le apre la pagina (P9) a un tocco dell'utente. */
  fun channelSettingsIntent(): Intent = appNotificationSettingsIntent().setAction(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
    .putExtra(Settings.EXTRA_CHANNEL_ID, AppNotifications.CHANNEL_MEMORIES)

  // --- Chi e' in tendina (solo id e modalita', mai un titolo) -----------------------------------------------------------------------------

  private val recordFile: File get() = File(stateDir, RECORD_FILE)

  private fun writePosted(posted: PostedNotice) {
    val text = listOf(MAGIC, posted.day.toString(), posted.memory.value, posted.mode.code, posted.cover?.value ?: "-").joinToString("\n", postfix = "\n")
    try {
      StateFiles.writeText(recordFile, text, Charsets.US_ASCII, RECORD_LABEL)
    } catch (e: IOException) {
      // Al peggio la notifica non si potra' revocare da sola (resta la tendina dell'utente); l'errore e' nel log di `StateFiles`.
    }
  }

  private fun readPosted(): PostedNotice? = try {
    val lines = StateFiles.readLines(recordFile, Charsets.US_ASCII, RECORD_LABEL, maxBytes = MAX_RECORD_BYTES)
    parsePosted(lines)
  } catch (e: IOException) {
    null
  }

  private fun deletePosted() {
    StateFiles.delete(recordFile, RECORD_LABEL)
  }

  companion object {
    private const val TAG = "MemoryNotifier"

    /** La grandezza della miniatura della notifica (px): basta per l'icona grande e per l'immagine espansa di un telefono. */
    const val THUMB_PX: Int = 512

    /** Il file di chi e' in tendina, dentro `memories/` (lo toglie l'azzeramento insieme allo scaffale, D37). */
    const val RECORD_FILE: String = "notified-memory"
    private const val RECORD_LABEL = "notified-memory"
    private const val MAGIC = "MCGP1"
    private const val MAX_RECORD_BYTES = 512L

    /** Il file di [lines] come [PostedNotice], o null se non torna (qualunque campo che non e' valido vale "niente in tendina"). */
    internal fun parsePosted(lines: List<String>?): PostedNotice? {
      if (lines == null || lines.size != 5 || lines[0] != MAGIC) return null
      return try {
        val day = lines[1].toIntOrNull() ?: return null
        val mode = LockScreenMode.ofCode(lines[3]) ?: return null
        val cover = if (lines[4] == "-") null else MediaId(lines[4])
        PostedNotice(MemoryId(lines[2]), day, mode, cover)
      } catch (_: IllegalArgumentException) {
        null
      }
    }
  }
}

/**
 * Dal contenuto deciso dalla policy (`TextRef`: un id di stringa con numeri e, solo per un viaggio, il suo titolo) al **testo** della notifica, dalle risorse
 * italiane. Una chiave che non si conosce vale "Un ricordo per te": mai un testo a caso, mai un nome. [places] da' il nome di un luogo (id GeoNames); senza,
 * "anni fa" senza il luogo. La usa anche lo screenshot della notifica: le parole che si vedono sono quelle vere.
 */
internal class NotifyTextResolver(private val resources: Resources, private val places: (Int) -> String?) {

  fun resolve(ref: TextRef): String = when (ref.key) {
    NotifyTexts.GENERIC_TEXT -> resources.getString(R.string.memories_notify_generic_text)
    NotifyTexts.TITLED_TEXT -> resources.getString(R.string.memories_notify_titled_text)
    NotifyTexts.TITLE_YEARS -> years(ref.numbers.firstOrNull())
    NotifyTexts.TITLE_YEARS_PLACE -> {
      val name = ref.numbers.getOrNull(1)?.let(places)?.takeIf { it.isNotBlank() }
      val years = ref.numbers.firstOrNull()
      if (name == null || years == null) years(years) else withPlace(R.string.memories_notify_title_years_place_one, R.string.memories_notify_title_years_place_many, years, name)
    }
    NotifyTexts.TITLE_TRIP -> {
      val label = ref.label?.trim()?.take(MAX_LABEL)?.takeIf { it.isNotEmpty() }
      val years = ref.numbers.firstOrNull()
      when {
        label == null -> generic()
        years == null -> resources.getString(R.string.memories_notify_title_trip_plain, label)
        else -> withPlace(R.string.memories_notify_title_trip_one, R.string.memories_notify_title_trip_many, years, label)
      }
    }
    NotifyTexts.TITLE_MONTH -> month(ref.numbers.getOrNull(0), ref.numbers.getOrNull(1))
    else -> generic()
  }

  private fun generic(): String = resources.getString(R.string.memories_notify_generic_title)

  private fun years(years: Int?): String = when {
    years == null || years < 1 -> generic()
    years == 1 -> resources.getString(R.string.memories_notify_title_years_one)
    else -> resources.getString(R.string.memories_notify_title_years_many, years)
  }

  private fun withPlace(one: Int, many: Int, years: Int, text: String): String =
    if (years == 1) resources.getString(one, text) else resources.getString(many, years, text)

  private fun month(year: Int?, month: Int?): String {
    if (year == null || month == null || month !in 1..12) return generic()
    // L'app ha i testi solo in italiano (risorse di serie): anche il mese e' in italiano, qualunque sia la lingua del telefono.
    return resources.getString(R.string.memories_notify_title_month, Month.of(month).getDisplayName(TextStyle.FULL_STANDALONE, Locale.ITALIAN), year)
  }

  private companion object {
    /** Il titolo di un viaggio nella notifica: al piu' cosi' lungo (la tendina lo accorcia comunque). */
    const val MAX_LABEL = 80
  }
}

/**
 * **Il tocco sulla notifica** (D17, E-NT8, E-NT9): la `MainActivity` riconosce l'azione [NotifyRules.OPEN_ACTION] mentre ripulisce l'intent e **la trasforma in questo evento
 * interno**, senza portare niente: nessun id, nessun dato, nessun extra. Resta **in attesa** finche' l'app non e' sbloccata: la serve solo chi sta **sotto** il blocco app
 * ([ServeOpenMemoriesRequests], dentro `AppLockGate`), che apre la pagina del ricordo di oggi ([MemoryNotifier.openTarget]) o, se non c'e' piu', la pagina Ricordi.
 * Un'altra app che manda la stessa azione puo' solo ottenere questo: al piu' la pagina Ricordi, dietro il blocco. Uno per processo.
 */
@Singleton
class OpenMemoriesRequests @Inject constructor() {
  private val pendingFlow = MutableStateFlow(false)

  /** C'e' una richiesta che nessuno ha ancora servito. */
  val pending: StateFlow<Boolean> = pendingFlow

  /** Una richiesta di aprire i Ricordi (dall'azione fissa della notifica). Piu' richieste prima di essere servite sono una sola. */
  fun request() {
    pendingFlow.value = true
  }

  /** La serve chi e' sotto il blocco app: vero **una volta sola** per richiesta (`false` se non c'era o se l'ha gia' presa un altro). */
  fun take(): Boolean = pendingFlow.compareAndSet(true, false)
}

/**
 * Serve la richiesta di aprire i Ricordi **dopo il blocco app** (D17): va messo dentro il contenuto di `AppLockGate`, che non e' in composizione finche' l'app e'
 * bloccata; [onOpen] parte quando l'app e' sbloccata e c'e' una richiesta, una volta per richiesta. Lo mette in `AppRoot` P13 (nessun altro file lo usa).
 */
@Composable
fun ServeOpenMemoriesRequests(requests: OpenMemoriesRequests, onOpen: () -> Unit) {
  val pending by requests.pending.collectAsState()
  LaunchedEffect(pending) {
    if (pending && requests.take()) onOpen()
  }
}
