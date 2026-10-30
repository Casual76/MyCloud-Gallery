package dev.pampa.mycloudgallery.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.MainActivity
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.LockScreenMode
import dev.pampa.mycloudgallery.core.memories.contract.Memory
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTitle
import dev.pampa.mycloudgallery.core.memories.contract.NotifyRules
import dev.pampa.mycloudgallery.core.memories.contract.PersonWindow
import dev.pampa.mycloudgallery.core.memories.noop.NoMemories
import dev.pampa.mycloudgallery.core.memories.notify.MemoryNotifyPolicy
import dev.pampa.mycloudgallery.core.memories.notify.NotifyAccessState
import dev.pampa.mycloudgallery.core.memories.notify.NotifyNotice
import dev.pampa.mycloudgallery.core.memories.shelf.MemoryVisibility
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * La notifica dei ricordi vera, su Android (Robolectric; docs/fase9-progetto.md §2.6, D15, D16, D17, D18, D19; Q4, Q5 (a), Q9; F115-F117): il canale senza suono e senza
 * vibrazione, la notifica privata con la versione pubblica sempre generica, **nessun testo del ricordo** in modalita' generica (test sul contenuto), "Titolo e foto" mai con nomi, il
 * `PendingIntent` immutabile con la sola azione, la revoca, il permesso e il canale letti **senza mai chiedere** niente. Ricordi e luoghi sintetici, nessun dato personale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MemoryNotifierTest {

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val manager: NotificationManager get() = app.getSystemService(NotificationManager::class.java)
  private val today = LocalDate.of(2026, 11, 3)
  private val dirs = ArrayList<File>()
  private val thumbnailCalls = ArrayList<MediaId>()

  @Before
  fun setUp() {
    shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    AppNotifications(app).createChannels()
  }

  @After
  fun togliTutto() {
    dirs.forEach { it.deleteRecursively() }
    manager.cancelAll()
  }

  private val closedVisibility = object : MemoryVisibility {
    override val snapshot: StateFlow<TimelineSnapshot?> = MutableStateFlow(null)

    override suspend fun visibleOrThrow(ids: Collection<MediaId>): Set<MediaId> = ids.toSet()

    override suspend fun hiddenOrThrow(ids: Collection<MediaId>): Set<MediaId> = emptySet()
  }

  private fun notifier(places: (Int) -> String? = { if (it == PLACE) "Oslo" else null }, bitmap: Bitmap? = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)): MemoryNotifier {
    val dir = Files.createTempDirectory("memories").toFile().also { dirs += it }
    return MemoryNotifier(app, NoMemories, closedVisibility, dir, places, { id, _ -> bitmap.also { thumbnailCalls += id } })
  }

  private fun memory(key: MemoryKey, yearsAgo: Int? = 1, label: String? = null, person: PersonId? = null, month: YearMonth? = null): Memory {
    val ids = List(10) { MediaId(UUID(0x5000_0000L + key.hashCode(), it.toLong()).toString()) }
    val title = MemoryTitle(yearsAgo, DayRange.of(today.minusYears(1)), listOf(PLACE), label, person, month)
    return Memory(MemoryIds.of(key), key, title, ids, ids.first(), 20261103)
  }

  private fun notice(memory: Memory, mode: LockScreenMode) = NotifyNotice(memory.id, 20261103, mode, MemoryNotifyPolicy().content(memory, mode))

  private fun posted(): Notification? = shadowOf(manager).getNotification(AppNotifications.ID_MEMORY)

  private fun Notification.title(): String = extras.getCharSequence(Notification.EXTRA_TITLE).toString()

  private fun Notification.text(): String = extras.getCharSequence(Notification.EXTRA_TEXT).toString()

  // --- Il canale (D15) --------------------------------------------------------------------------------------------------------------

  @Test
  fun ilCanaleENotificaSenzaSuonoNeVibrazione() {
    assertThat(AppNotifications.CHANNEL_MEMORIES).isEqualTo(NotifyRules.CHANNEL_ID)
    assertThat(AppNotifications.ID_MEMORY).isEqualTo(NotifyRules.NOTIFICATION_ID)
    assertThat(AppNotifications.ID_MEMORY).isEqualTo(1009)
    AppNotifications(app).createChannels() // idempotente
    val channel: NotificationChannel = manager.getNotificationChannel("memories")
    assertThat(channel.name.toString()).isEqualTo("Ricordi")
    assertThat(channel.description).isEqualTo("Un ricordo al giorno al massimo, all'ora che scegli")
    assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_DEFAULT)
    assertThat(channel.sound).isNull()
    assertThat(channel.shouldVibrate()).isFalse()
    assertThat(channel.lockscreenVisibility).isEqualTo(Notification.VISIBILITY_PRIVATE)
  }

  // --- Il contenuto (D16, Q5 (a), Q9) -----------------------------------------------------------------------------------------------

  /** Di serie, a telefono bloccato: "Un ricordo per te", "Tocca per vederlo"; privata, con la versione pubblica uguale; senza foto; si toglie toccandola. */
  @Test
  fun lanotificaGenerica() = runBlocking<Unit> {
    val n = notifier()
    assertThat(n.publish(notice(memory(MemoryKey.OnThisDay(today.minusYears(1)), label = null), LockScreenMode.GENERIC))).isTrue()
    val notification = posted()!!
    assertThat(notification.channelId).isEqualTo("memories")
    assertThat(notification.title()).isEqualTo("Un ricordo per te")
    assertThat(notification.text()).isEqualTo("Tocca per vederlo")
    assertThat(notification.visibility).isEqualTo(Notification.VISIBILITY_PRIVATE)
    assertThat(notification.publicVersion.title()).isEqualTo("Un ricordo per te")
    assertThat(notification.publicVersion.text()).isEqualTo("Tocca per vederlo")
    assertThat(notification.flags and Notification.FLAG_AUTO_CANCEL).isNotEqualTo(0)
    assertThat(notification.getLargeIcon()).isNull()
    assertThat(notification.extras.getString(Notification.EXTRA_TEMPLATE)).isNull()
    // La modalita' generica non va nemmeno a cercare la miniatura.
    assertThat(thumbnailCalls).isEmpty()
  }

  /**
   * **Il test sul contenuto** (Q5 (a), D16): per ricordi con un titolo, un luogo, una persona e delle foto riconoscibili, la notifica generica (privata **e** pubblica, ogni campo e
   * ogni extra) non contiene **niente** di tutto questo.
   */
  @Test
  fun laModalitaGenericaNonHaNessunTestoDelRicordo() = runBlocking<Unit> {
    val person = PersonId.fromBytes(ByteArray(16).also { it.fill(7) })
    val memories = listOf(
      memory(MemoryKey.OnThisDay(today.minusYears(3)), yearsAgo = 3),
      memory(MemoryKey.Trip(DayRange(today.minusYears(1), today.minusYears(1).plusDays(3))), label = LABEL),
      memory(MemoryKey.Month(YearMonth.of(2026, 10)), yearsAgo = null, month = YearMonth.of(2026, 10)),
      memory(MemoryKey.Person(person, PersonWindow.Year(2025)), yearsAgo = null, person = person),
    )
    for (m in memories) {
      val n = notifier()
      assertThat(n.publish(notice(m, LockScreenMode.GENERIC))).isTrue()
      val notification = posted()!!
      val text = everyString(notification) + everyString(notification.publicVersion)
      for (marker in listOf(LABEL, "Oslo", PLACE.toString(), m.cover.value, m.id.value, person.base32(), "2025", "2026", "anni fa", "Il meglio", "ottobre")) {
        assertWithMessage("notifica generica con '$marker'").that(text.any { marker in it }).isFalse()
      }
      assertThat(notification.title()).isEqualTo("Un ricordo per te")
      assertThat(notification.getLargeIcon()).isNull()
      manager.cancelAll()
    }
  }

  /** Ogni stringa di una notifica: titolo, testo, sottotitoli, tutti gli extra di testo. */
  private fun everyString(n: Notification): List<String> = n.extras.keySet().mapNotNull { key ->
    when (val v = n.extras.get(key)) {
      is CharSequence -> v.toString()
      is Array<*> -> v.joinToString(" ")
      else -> null
    }
  } + listOfNotNull(n.tickerText?.toString())

  /** "Titolo e foto": il titolo con gli anni e il luogo, "Tocca per rivederlo", la miniatura locale; la versione pubblica e' sempre generica. */
  @Test
  fun titoloEFoto() = runBlocking<Unit> {
    val m = memory(MemoryKey.OnThisDay(today.minusYears(3)), yearsAgo = 3)
    val n = notifier()
    assertThat(n.publish(notice(m, LockScreenMode.TITLE_AND_PHOTO))).isTrue()
    val notification = posted()!!
    assertThat(notification.title()).isEqualTo("3 anni fa · Oslo")
    assertThat(notification.text()).isEqualTo("Tocca per rivederlo")
    assertThat(notification.getLargeIcon()).isNotNull()
    assertThat(notification.extras.getString(Notification.EXTRA_TEMPLATE)).contains("BigPictureStyle")
    assertThat(notification.visibility).isEqualTo(Notification.VISIBILITY_PRIVATE)
    // A telefono bloccato (anche con "Mostra tutti i contenuti" tolto) la versione pubblica non dice niente del ricordo.
    assertThat(notification.publicVersion.title()).isEqualTo("Un ricordo per te")
    assertThat(notification.publicVersion.text()).isEqualTo("Tocca per vederlo")
    assertThat(notification.publicVersion.getLargeIcon()).isNull()
    assertThat(thumbnailCalls).containsExactly(m.cover)
  }

  @Test
  fun iTitoliDiTitoloEFoto_unAnnoUnViaggioUnMeseEUnaPersona() = runBlocking<Unit> {
    fun titleOf(m: Memory, places: (Int) -> String? = { if (it == PLACE) "Oslo" else null }): String {
      val n = notifier(places)
      runBlocking { n.publish(notice(m, LockScreenMode.TITLE_AND_PHOTO)) }
      return posted()!!.title().also { manager.cancelAll() }
    }
    assertThat(titleOf(memory(MemoryKey.OnThisDay(today.minusYears(1))))).isEqualTo("Un anno fa · Oslo")
    // Senza il nome del luogo (il dataset non lo conosce) restano gli anni.
    assertThat(titleOf(memory(MemoryKey.OnThisDay(today.minusYears(1))), places = { null })).isEqualTo("Un anno fa")
    assertThat(titleOf(memory(MemoryKey.OnThisDay(today.minusYears(5)), yearsAgo = 5), places = { null })).isEqualTo("5 anni fa")
    val trip = memory(MemoryKey.Trip(DayRange(today.minusYears(2), today.minusYears(2).plusDays(2))), yearsAgo = 2, label = LABEL)
    assertThat(titleOf(trip)).isEqualTo("2 anni fa · $LABEL")
    // La riscoperta di un viaggio vecchio: solo il suo titolo. Il mese: il mese per esteso.
    assertThat(titleOf(memory(MemoryKey.Trip(DayRange(today.minusYears(4), today.minusYears(4).plusDays(2))), yearsAgo = null, label = LABEL))).isEqualTo(LABEL)
    assertThat(titleOf(memory(MemoryKey.Month(YearMonth.of(2026, 10)), yearsAgo = null, month = YearMonth.of(2026, 10)))).isEqualTo("Il meglio di ottobre 2026")
    // Una persona: mai il nome, il titolo e' quello generico (Q9, E-NT13).
    val person = PersonId.fromBytes(ByteArray(16).also { it.fill(3) })
    val personal = memory(MemoryKey.Person(person, PersonWindow.Year(2025)), yearsAgo = null, person = person)
    assertThat(titleOf(personal)).isEqualTo("Un ricordo per te")
  }

  /** Senza la miniatura locale (la replica non ce l'ha) la notifica parte lo stesso, con il titolo e senza foto; mai un'attesa sul NAS. */
  @Test
  fun senzaMiniaturaLocale() = runBlocking<Unit> {
    val n = notifier(bitmap = null)
    assertThat(n.publish(notice(memory(MemoryKey.OnThisDay(today.minusYears(1))), LockScreenMode.TITLE_AND_PHOTO))).isTrue()
    val notification = posted()!!
    assertThat(notification.title()).isEqualTo("Un anno fa · Oslo")
    assertThat(notification.getLargeIcon()).isNull()
  }

  /** Una notifica al giorno ha un posto solo (id 1009): la nuova prende il posto della vecchia. */
  @Test
  fun unaSolaNotificaInTendina() = runBlocking<Unit> {
    val n = notifier()
    n.publish(notice(memory(MemoryKey.OnThisDay(today.minusYears(1))), LockScreenMode.GENERIC))
    n.publish(notice(memory(MemoryKey.OnThisDay(today.minusYears(2)), yearsAgo = 2), LockScreenMode.GENERIC))
    assertThat(shadowOf(manager).allNotifications.filter { it.channelId == "memories" }).hasSize(1)
  }

  // --- L'intent (D17, C151) ---------------------------------------------------------------------------------------------------------

  /** Il tocco: `PendingIntent` immutabile, esplicito verso la `MainActivity`, con la sola azione e **nient'altro**: nessun extra, dato, categoria. */
  @Test
  fun ilPendingIntentPortaSoloLAzioneNota() {
    val pending: PendingIntent = notifier().openMemoriesIntent()
    val shadow = shadowOf(pending)
    assertThat(shadow.isActivityIntent).isTrue()
    assertThat(shadow.flags and PendingIntent.FLAG_IMMUTABLE).isNotEqualTo(0)
    assertThat(shadow.flags and PendingIntent.FLAG_MUTABLE).isEqualTo(0)
    val intent = shadow.savedIntent
    assertThat(intent.action).isEqualTo("dev.pampa.mycloudgallery.action.OPEN_MEMORIES")
    assertThat(intent.action).isEqualTo(NotifyRules.OPEN_ACTION)
    assertThat(intent.component?.className).isEqualTo(MainActivity::class.java.name)
    assertThat(intent.extras).isNull()
    assertThat(intent.data).isNull()
    assertThat(intent.type).isNull()
    assertThat(intent.categories).isNull()
  }

  /** Dentro la notifica pubblicata: il tocco e' quello, anche per la versione pubblica non c'e' un altro modo di aprire l'app. */
  @Test
  fun laNotificaPubblicataHaQuelTocco() = runBlocking<Unit> {
    notifier().publish(notice(memory(MemoryKey.OnThisDay(today.minusYears(1))), LockScreenMode.TITLE_AND_PHOTO))
    val notification = posted()!!
    val intent = shadowOf(notification.contentIntent).savedIntent
    assertThat(intent.action).isEqualTo(NotifyRules.OPEN_ACTION)
    assertThat(intent.extras).isNull()
    assertThat(intent.data).isNull()
    assertThat(notification.actions).isNull()
    assertThat(shadowOf(notification.contentIntent).flags and PendingIntent.FLAG_IMMUTABLE).isNotEqualTo(0)
  }

  // --- Permesso e canale: mai una richiesta (D18, E-NT1, E-NT2) ---------------------------------------------------------------------

  @Test
  fun permessoNegatoNonPubblicaENonChiedeNiente() = runBlocking<Unit> {
    shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    val n = notifier()
    assertThat(n.state.value).isEqualTo(NotifyAccessState.PERMISSION_MISSING)
    assertThat(n.publish(notice(memory(MemoryKey.OnThisDay(today.minusYears(1))), LockScreenMode.GENERIC))).isFalse()
    assertThat(posted()).isNull()
    // Nessuna richiesta automatica: nessuna schermata partita, nessun permesso chiesto.
    assertThat(shadowOf(app).nextStartedActivity).isNull()
  }

  @Test
  fun notificheDellAppSpenteESonoUnaCosaDelSistema() = runBlocking<Unit> {
    shadowOf(manager).setNotificationsEnabled(false)
    val n = notifier()
    assertThat(n.state.value).isEqualTo(NotifyAccessState.APP_OFF)
    assertThat(n.publish(notice(memory(MemoryKey.OnThisDay(today.minusYears(1))), LockScreenMode.GENERIC))).isFalse()
    assertThat(shadowOf(app).nextStartedActivity).isNull()
    shadowOf(manager).setNotificationsEnabled(true)
    n.refresh()
    assertThat(n.state.value).isEqualTo(NotifyAccessState.GRANTED)
  }

  @Test
  fun ilCanaleSpentoDallUtente() = runBlocking<Unit> {
    manager.deleteNotificationChannel("memories")
    manager.createNotificationChannel(NotificationChannel("memories", "Ricordi", NotificationManager.IMPORTANCE_NONE))
    val n = notifier()
    assertThat(n.state.value).isEqualTo(NotifyAccessState.CHANNEL_OFF)
    assertThat(n.publish(notice(memory(MemoryKey.OnThisDay(today.minusYears(1))), LockScreenMode.GENERIC))).isFalse()
    assertThat(shadowOf(app).nextStartedActivity).isNull()
  }

  /** Le impostazioni di sistema che apre la pagina (a un tocco): quelle dell'app e quelle del solo canale. */
  @Test
  fun leImpostazioniDiSistema() {
    val n = notifier()
    val appSettings = n.appNotificationSettingsIntent()
    assertThat(appSettings.action).isEqualTo(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    assertThat(appSettings.getStringExtra(Settings.EXTRA_APP_PACKAGE)).isEqualTo(app.packageName)
    val channel = n.channelSettingsIntent()
    assertThat(channel.action).isEqualTo(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
    assertThat(channel.getStringExtra(Settings.EXTRA_CHANNEL_ID)).isEqualTo("memories")
    assertThat(channel.getStringExtra(Settings.EXTRA_APP_PACKAGE)).isEqualTo(app.packageName)
  }

  // --- La revoca e chi e' in tendina (D19) ---------------------------------------------------------------------------------------------

  /** Con il ricordo che non c'e' piu' sullo scaffale (qui: nessuno scaffale) la revoca toglie la notifica e dimentica chi era; il giro dopo non trova niente da togliere. */
  @Test
  fun laRevocaTogliLaNotificaERicordaDiAverlaTolta() = runBlocking<Unit> {
    val n = notifier()
    val m = memory(MemoryKey.OnThisDay(today.minusYears(1)))
    n.publish(notice(m, LockScreenMode.TITLE_AND_PHOTO))
    assertThat(n.posted()?.memory).isEqualTo(m.id)
    assertThat(n.openTarget()).isEqualTo(m.id)
    assertThat(n.revalidate()).isTrue()
    assertThat(posted()).isNull()
    assertThat(n.posted()).isNull()
    assertThat(n.openTarget()).isNull()
    assertThat(n.revalidate()).isFalse()
  }

  /** Se l'utente ha gia' tolto la notifica (la tendina, o toccandola: `setAutoCancel`) non c'e' piu' niente da revocare e il ricordo di chi era si dimentica. */
  @Test
  fun unaNotificaGiaTolta() = runBlocking<Unit> {
    val n = notifier()
    n.publish(notice(memory(MemoryKey.OnThisDay(today.minusYears(1))), LockScreenMode.GENERIC))
    manager.cancel(AppNotifications.ID_MEMORY)
    assertThat(n.posted()).isNull()
    assertThat(n.revalidate()).isFalse()
  }

  /** Chi e' in tendina sta in un file con i soli id e la modalita': mai un titolo; un file che non torna vale "niente in tendina". */
  @Test
  fun ilFileDiChiEInTendina() = runBlocking<Unit> {
    val dir = Files.createTempDirectory("memories").toFile().also { dirs += it }
    val n = MemoryNotifier(app, NoMemories, closedVisibility, dir, { "Oslo" }, { _, _ -> Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888) })
    val m = memory(MemoryKey.OnThisDay(today.minusYears(1)), label = LABEL)
    n.publish(notice(m, LockScreenMode.TITLE_AND_PHOTO))
    val text = File(dir, MemoryNotifier.RECORD_FILE).readText(Charsets.US_ASCII)
    assertThat(text).doesNotContain(LABEL)
    assertThat(text).doesNotContain("Oslo")
    assertThat(text.lines().filter { it.isNotEmpty() }).hasSize(5)
    assertThat(MemoryNotifier.parsePosted(text.lines().dropLast(1))?.memory).isEqualTo(m.id)
    for (bad in listOf(null, emptyList(), listOf("MCGP1"), text.lines().dropLast(1).map { if (it == "title_and_photo") "tutto" else it }, text.lines().dropLast(1).map { if (it.length == 26) "x" else it })) {
      assertThat(MemoryNotifier.parsePosted(bad)).isNull()
    }
  }
}

private const val PLACE = 7_654_321
private const val LABEL = "Viaggio Sintetico Zeta"
