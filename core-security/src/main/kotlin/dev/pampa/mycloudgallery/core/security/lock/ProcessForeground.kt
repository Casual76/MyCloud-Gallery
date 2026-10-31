package dev.pampa.mycloudgallery.core.security.lock

/**
 * **Il processo e' in primo piano** (Fase 10, P4, D17, C434): finche' **almeno una finestra** dell'app e' avviata. Il blocco dell'app non vale per la
 * finestra ma per il processo: uscire dall'app vuol dire che **l'ultima** finestra si e' fermata, non che una ha perso il fuoco o si e' fermata mentre
 * l'altra resta visibile. Logica pura (nessuna classe Android a parte l'orologio che le arriva da fuori): la provano i test con finestre finte.
 *
 * Ogni finestra e' una maniglia ([Window], una per composizione del cancello: nessun riferimento all'activity, quindi niente perdite): comincia con
 * [start] (`ON_START`) e finisce con [stop] (`ON_STOP`) o [close] (fine della composizione, `ON_DESTROY`). Le chiamate sono **idempotenti**: un evento
 * ripetuto o fuori ordine non sposta il contatore.
 *
 * **Ricreazione di una finestra** (un cambio di densita', di lingua o di tema: Android distrugge l'activity e ne fa una nuova nello stesso task): uno
 * `stop` con `changingConfigurations = true` non toglie la finestra dal primo piano, lascia un **segno di ricreazione** che la finestra nuova consuma
 * con il suo `start`. Il segno **scade** ([RECREATION_GRACE_MS], orologio monotono): se la finestra nuova non arriva, passato il tempo la finestra
 * vecchia conta come uscita (fallire chiusi: mai un primo piano "per sempre"). Senza questo, con "Subito" una ricreazione bloccherebbe l'app; con il
 * segno senza scadenza una ricreazione mai finita lascerebbe l'app aperta.
 *
 * I metodi sono sincronizzati su di se'; chi li usa insieme ad altro stato (il controller del blocco) li chiama dentro il suo blocco sincronizzato:
 * l'ordine dei lucchetti e' sempre controller poi processo, e questa classe non richiama mai nessuno.
 */
class ProcessForeground(private val clock: MonotonicClock = SystemMonotonicClock) {

  /** Cosa cambia per il **processo** dopo un evento di una finestra. */
  enum class Change {
    /** Il numero di finestre avviate e' cambiato, ma il processo e' com'era (un'altra finestra resta visibile, o e' una ricreazione). */
    NONE,

    /** E' partita la **prima** finestra: l'app torna in primo piano. */
    ENTERED_FOREGROUND,

    /** E' ferma l'**ultima** finestra: l'app esce dal primo piano. */
    LEFT_FOREGROUND,
  }

  /** Una finestra dell'app, vista dal blocco. Opaca: la si apre con [newWindow] e si pilota con [start], [stop], [close]. */
  class Window internal constructor() {
    internal var closed = false
  }

  private val started = LinkedHashSet<Window>()
  private val recreations = ArrayDeque<Long>()
  private var foreground = false
  private var latestExitMs = 0L

  /** Quante finestre sono avviate adesso (le ricreazioni in corso non contano). */
  val startedWindows: Int @Synchronized get() = started.size

  /**
   * L'istante (orologio monotono) in cui il processo e' uscito dal primo piano l'ultima volta: quello della fermata dell'ultima finestra, o, se e' scaduta una
   * ricreazione mai finita, quello della ricreazione (**non** quello in cui ce ne accorgiamo: il tempo concesso dal blocco conta dall'uscita vera).
   */
  val lastExitAtMillis: Long @Synchronized get() = latestExitMs

  /** Una finestra nuova, non ancora avviata. */
  fun newWindow(): Window = Window()

  /** La finestra e' avviata (`ON_START`). Ripetuta o dopo [close]: nessun effetto. */
  @Synchronized
  fun start(window: Window): Change {
    if (window.closed || !started.add(window)) return Change.NONE
    purge()
    // Questa e' la finestra nuova di una ricreazione, se ce n'e' una in corso: ne prende il posto.
    recreations.removeFirstOrNull()
    if (foreground) return Change.NONE
    foreground = true
    return Change.ENTERED_FOREGROUND
  }

  /**
   * La finestra si e' fermata (`ON_STOP`). Con [changingConfigurations] e' una ricreazione: il processo resta in primo piano finche' la finestra nuova non
   * parte o il segno scade. Una finestra mai avviata, o gia' ferma: nessun effetto.
   */
  @Synchronized
  fun stop(window: Window, changingConfigurations: Boolean): Change {
    if (!started.remove(window)) return Change.NONE
    purge()
    val now = clock.elapsedRealtimeMillis()
    if (changingConfigurations) {
      recreations.addLast(now)
      return Change.NONE
    }
    latestExitMs = now
    return evaluate()
  }

  /** La finestra e' finita per sempre (composizione chiusa, activity distrutta senza ricreazione): come [stop] e non torna piu'. */
  @Synchronized
  fun close(window: Window): Change {
    window.closed = true
    if (!started.remove(window)) return Change.NONE
    purge()
    latestExitMs = clock.elapsedRealtimeMillis()
    return evaluate()
  }

  /**
   * Il tempo e' passato senza eventi: un segno di ricreazione scaduto fa uscire il processo dal primo piano. Lo chiama chi decide in base al primo piano
   * (sblocco d'ingresso, [AppLockController.isUnlockedNow]) prima di leggere lo stato.
   */
  @Synchronized
  fun poll(): Change {
    purge()
    return evaluate()
  }

  private fun evaluate(): Change {
    if (!foreground || started.isNotEmpty() || recreations.isNotEmpty()) return Change.NONE
    foreground = false
    return Change.LEFT_FOREGROUND
  }

  /** Toglie i segni scaduti (l'uscita risale al piu' tardo); un orologio che va indietro li fa scadere (fallire chiusi). */
  private fun purge() {
    if (recreations.isEmpty()) return
    val now = clock.elapsedRealtimeMillis()
    recreations.removeAll {
      val age = now - it
      val expired = age < 0 || age >= RECREATION_GRACE_MS
      if (expired) latestExitMs = maxOf(latestExitMs, minOf(it, now))
      expired
    }
  }

  companion object {
    /** Quanto puo' durare una ricreazione prima che la finestra vecchia conti come uscita: dieci secondi di orologio monotono. */
    const val RECREATION_GRACE_MS: Long = 10_000L
  }
}
