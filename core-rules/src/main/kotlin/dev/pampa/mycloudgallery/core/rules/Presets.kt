package dev.pampa.mycloudgallery.core.rules

import java.time.LocalTime

/** I preset del piano (§4.6). `CUSTOM` = regole per attivita', con i video separati. */
enum class Preset { NIGHT, BALANCED, WIFI_ALWAYS, MAXIMUM, CUSTOM }

/** La descrizione di un preset per l'utente, in italiano (le stime si aggiungono con lo stimatore). */
data class PresetInfo(
  val preset: Preset,
  val title: String,
  /** Cosa fa, in una o due frasi. */
  val summary: String,
  /** Per chi e'. */
  val forWhom: String,
)

/**
 * La fascia "notte" di default e la sua derivazione dall'orario del NAS (piano §3, proposta 14).
 *
 * Il NAS oggi e' acceso 08:00-22:00 (docs/decisioni.md), cioe' NON di notte: finche' l'orario non viene
 * allungato, la fascia notte resta quella ideale e il NAS risultera' spento (E-R5).
 */
object NightWindows {

  /** La notte "ideale": quando il telefono sta in carica sul comodino. */
  val DEFAULT = TimeWindow(LocalTime.of(23, 0), LocalTime.of(7, 0))

  /** Una sovrapposizione piu' breve di cosi' e' inutile: si ripiega sulla fascia ideale. */
  const val MIN_USEFUL_MINUTES = 30

  /**
   * Il risultato della derivazione.
   *
   * @property overlapsNas la fascia cade (almeno in parte) nelle ore in cui il NAS e' acceso. Falso =
   *   il NAS sara' spento nella fascia: la UI propone di allungare l'orario o di permettere il giorno.
   */
  data class Choice(val window: TimeWindow, val overlapsNas: Boolean)

  /**
   * La fascia notte ricavata dall'orario del NAS: l'intersezione piu' lunga fra la notte [ideal] e le
   * ore di accensione. Se il NAS e' acceso sempre (o l'orario non si conosce, [nas] nullo) vale [ideal].
   * Se l'intersezione ha due pezzi (es. NAS 06:00-23:00 contro notte 22:00-08:00) si sceglie il piu'
   * lungo; se non c'e' (o e' sotto [MIN_USEFUL_MINUTES]) si ripiega su [ideal] con `overlapsNas = false`.
   */
  fun derive(nas: NasSchedule?, ideal: TimeWindow = DEFAULT): Choice {
    if (nas == null || nas.alwaysOn) return Choice(ideal, overlapsNas = true)

    val inIdeal = minutesOf(ideal.start, ideal.end)
    val inNas = minutesOf(nas.on, nas.off)
    val both = BooleanArray(MINUTES_PER_DAY) { inIdeal[it] && inNas[it] }

    // La corsa piu' lunga di minuti consecutivi (circolare: puo' passare per la mezzanotte).
    var bestStart = -1
    var bestLength = 0
    for (start in 0 until MINUTES_PER_DAY) {
      if (!both[start] || both[(start + MINUTES_PER_DAY - 1) % MINUTES_PER_DAY]) continue // non e' un inizio
      var length = 0
      while (length < MINUTES_PER_DAY && both[(start + length) % MINUTES_PER_DAY]) length++
      if (length > bestLength) {
        bestStart = start
        bestLength = length
      }
    }
    if (bestLength < MIN_USEFUL_MINUTES || bestLength >= MINUTES_PER_DAY) {
      return Choice(ideal, overlapsNas = false)
    }
    val end = (bestStart + bestLength) % MINUTES_PER_DAY
    return Choice(TimeWindow(toTime(bestStart), toTime(end)), overlapsNas = true)
  }

  private const val MINUTES_PER_DAY = 24 * 60

  /** I minuti del giorno coperti da [start, end), anche a cavallo della mezzanotte. */
  private fun minutesOf(start: LocalTime, end: LocalTime): BooleanArray {
    val covered = BooleanArray(MINUTES_PER_DAY)
    val from = start.hour * 60 + start.minute
    val to = end.hour * 60 + end.minute
    var minute = from
    do {
      covered[minute] = true
      minute = (minute + 1) % MINUTES_PER_DAY
    } while (minute != to)
    return covered
  }

  private fun toTime(minuteOfDay: Int): LocalTime = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
}

/**
 * L'orario di accensione del NAS (dalla configurazione di famiglia). `on == off` significa acceso
 * sempre.
 */
data class NasSchedule(val on: LocalTime, val off: LocalTime) {
  val alwaysOn: Boolean get() = on == off
}

/** I preset con le loro regole, i tetti di default e le descrizioni. */
object PresetCatalog {

  private const val GB = 1_000_000_000L

  /** Tetto dati mobili di default (copre il caricamento di cio' che e' sullo schermo, proposta 3). */
  const val DEFAULT_MOBILE_CAP_BYTES = 1 * GB

  /** Tetto dati mobili del preset Massimo, che usa i dati mobili per tutte le attivita'. */
  const val MAXIMUM_MOBILE_CAP_BYTES = 10 * GB

  private val WIFI_ONLY = setOf(AllowedNetwork.HOME_WIFI, AllowedNetwork.ANY_WIFI_VPN)
  private val WIFI_AND_MOBILE = WIFI_ONLY + AllowedNetwork.MOBILE_DATA

  /**
   * Le regole di un preset. `CUSTOM` parte dalle regole del preset Notte: `RuleSettings.toRuleSet()`
   * (package `settings`) le sovrascrive con quelle scelte dall'utente.
   */
  fun ruleSet(preset: Preset, night: TimeWindow = NightWindows.DEFAULT): RuleSet =
    when (preset) {
      Preset.NIGHT,
      Preset.CUSTOM ->
        uniform(
          ActivityRule(window = night, power = PowerPolicy.PLUGGED_ONLY, networks = WIFI_ONLY),
          caps = MonthlyCaps(mobileBytes = DEFAULT_MOBILE_CAP_BYTES, wifiBytes = null),
        )
      Preset.BALANCED ->
        RuleSet(
          rules =
            BackgroundActivity.entries.associateWith { activity ->
              val power =
                if (activity.weight == ActivityWeight.LIGHT) PowerPolicy.BATTERY_ALLOWED
                else PowerPolicy.PLUGGED_ONLY
              ActivityRule(window = null, power = power, networks = WIFI_ONLY)
            },
          caps = MonthlyCaps(mobileBytes = DEFAULT_MOBILE_CAP_BYTES, wifiBytes = null),
        )
      Preset.WIFI_ALWAYS ->
        uniform(
          ActivityRule(window = null, power = PowerPolicy.BATTERY_ALLOWED, networks = WIFI_ONLY),
          caps = MonthlyCaps(mobileBytes = DEFAULT_MOBILE_CAP_BYTES, wifiBytes = null),
        )
      Preset.MAXIMUM ->
        uniform(
          ActivityRule(window = null, power = PowerPolicy.BATTERY_ALLOWED, networks = WIFI_AND_MOBILE),
          caps = MonthlyCaps(mobileBytes = MAXIMUM_MOBILE_CAP_BYTES, wifiBytes = null),
          heavyOnlyAtHome = false,
        )
    }

  private fun uniform(rule: ActivityRule, caps: MonthlyCaps, heavyOnlyAtHome: Boolean = true) =
    RuleSet(
      rules = BackgroundActivity.entries.associateWith { rule },
      caps = caps,
      heavyOnlyAtHome = heavyOnlyAtHome,
    )

  fun info(preset: Preset): PresetInfo =
    when (preset) {
      Preset.NIGHT ->
        PresetInfo(
          preset,
          title = "Notte",
          summary =
            "Tutto il lavoro in background parte solo di notte, con il telefono collegato alla " +
              "corrente e in Wi-Fi. Consuma pochissimo e non te ne accorgi.",
          forWhom = "Chi lascia il telefono in carica di notte",
        )
      Preset.BALANCED ->
        PresetInfo(
          preset,
          title = "Bilanciato",
          summary =
            "Sincronizzazione dell'indice e backup delle foto appena c'è il Wi-Fi, anche a batteria " +
              "(sopra la soglia). I lavori pesanti (video, preparazione, calcolo AI, anteprime ad " +
              "alta qualità) aspettano la corrente.",
          forWhom = "Chi vuole il backup delle foto in giornata",
        )
      Preset.WIFI_ALWAYS ->
        PresetInfo(
          preset,
          title = "Wi-Fi sempre",
          summary =
            "Tutto il lavoro parte ogni volta che c'è il Wi-Fi, anche a batteria (sopra la soglia). " +
              "I lavori pesanti restano sul Wi-Fi di casa.",
          forWhom = "Chi è spesso in Wi-Fi",
        )
      Preset.MAXIMUM ->
        PresetInfo(
          preset,
          title = "Massimo",
          summary =
            "Tutto, sempre: anche con i dati mobili entro il tetto mensile e anche a batteria " +
              "(sopra la soglia). Il roaming resta escluso: si abilita solo con un interruttore apposito.",
          forWhom = "Chi ha dati illimitati",
        )
      Preset.CUSTOM ->
        PresetInfo(
          preset,
          title = "Personalizzato",
          summary =
            "Scegli tu, attività per attività, quando può girare, con quale alimentazione e su quale " +
              "rete. I video hanno regole separate.",
          forWhom = "Chi vuole il controllo",
        )
    }

  fun allInfo(): List<PresetInfo> = Preset.entries.map(::info)
}
