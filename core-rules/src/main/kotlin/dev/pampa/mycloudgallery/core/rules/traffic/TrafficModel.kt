package dev.pampa.mycloudgallery.core.rules.traffic

import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * Chi ha generato il traffico: le sei attivita' piu' il caricamento di cio' che e' sullo schermo
 * (miniature, anteprime e originali aperti dall'utente con l'app in primo piano, proposta 3).
 */
enum class TrafficSource {
  PHOTO_BACKUP,
  VIDEO_BACKUP,
  INDEX_SYNC,
  PREPARATION,
  AI_COMPUTE,
  HQ_PREVIEWS,
  VISIBLE_CONTENT;

  companion object {
    fun of(activity: BackgroundActivity): TrafficSource =
      when (activity) {
        BackgroundActivity.PHOTO_BACKUP -> PHOTO_BACKUP
        BackgroundActivity.VIDEO_BACKUP -> VIDEO_BACKUP
        BackgroundActivity.INDEX_SYNC -> INDEX_SYNC
        BackgroundActivity.PREPARATION -> PREPARATION
        BackgroundActivity.AI_COMPUTE -> AI_COMPUTE
        BackgroundActivity.HQ_PREVIEWS -> HQ_PREVIEWS
      }
  }
}

/**
 * Su quale rete e' passato il traffico: va registrato quella EFFETTIVAMENTE usata dal socket (E-R12).
 * Un Wi-Fi a consumo conta come [MOBILE] (E-R13).
 */
enum class TrafficNetwork {
  HOME_WIFI,
  EXTERNAL_WIFI,
  MOBILE,
  ROAMING;

  /** Conta nel tetto dei dati mobili (mobile e roaming); altrimenti nel tetto Wi-Fi. */
  val isMobile: Boolean get() = this == MOBILE || this == ROAMING

  companion object {
    /** Null se la rete non porta traffico (offline, rete non riconosciuta). */
    fun of(networkClass: NetworkClass): TrafficNetwork? =
      when (networkClass) {
        NetworkClass.HOME_WIFI -> HOME_WIFI
        NetworkClass.EXTERNAL_WIFI -> EXTERNAL_WIFI
        NetworkClass.MOBILE -> MOBILE
        NetworkClass.ROAMING -> ROAMING
        NetworkClass.OFFLINE,
        NetworkClass.UNTRUSTED -> null
      }
  }
}

data class TrafficKey(val source: TrafficSource, val network: TrafficNetwork)

/**
 * Il "mese" dei contatori: di norma il mese di calendario, ma chi ha un piano dati con il rinnovo
 * a meta' mese puo' scegliere il giorno di inizio (1..28). I contatori si azzerano all'inizio di ogni
 * ciclo: un ciclo nuovo e' semplicemente un ciclo senza byte registrati.
 */
data class BillingCycle(val month: YearMonth, val startDay: Int = 1) {

  init {
    require(startDay in 1..MAX_START_DAY) { "Il giorno di inizio del ciclo va da 1 a $MAX_START_DAY." }
  }

  val start: LocalDate get() = month.atDay(startDay)

  /** Primo giorno del ciclo successivo (escluso da questo). */
  val endExclusive: LocalDate get() = month.plusMonths(1).atDay(startDay)

  /** Identificatore stabile ("2026-10"): e' la parte di chiave che finisce in DataStore. */
  val id: String get() = String.format(Locale.ROOT, "%04d-%02d", month.year, month.monthValue)

  companion object {
    const val MAX_START_DAY = 28

    fun containing(date: LocalDate, startDay: Int = 1): BillingCycle {
      require(startDay in 1..MAX_START_DAY) { "Il giorno di inizio del ciclo va da 1 a $MAX_START_DAY." }
      val month = YearMonth.from(date)
      return if (date.dayOfMonth >= startDay) BillingCycle(month, startDay)
      else BillingCycle(month.minusMonths(1), startDay)
    }

    fun parseId(id: String, startDay: Int = 1): BillingCycle? =
      runCatching { BillingCycle(YearMonth.parse(id), startDay) }.getOrNull()
  }
}

/** Il traffico di un ciclo, per attivita' e per rete. */
data class CycleUsage(val cycle: BillingCycle, val bytes: Map<TrafficKey, Long> = emptyMap()) {

  /** Byte passati su dati mobili e roaming: quelli che contano nel tetto dati mobili. */
  val mobileBytes: Long get() = sumWhere { it.network.isMobile }

  /** Byte passati su Wi-Fi (di casa e esterno): quelli che contano nel tetto Wi-Fi. */
  val wifiBytes: Long get() = sumWhere { !it.network.isMobile }

  val totalBytes: Long get() = sumWhere { true }

  fun bytesFor(source: TrafficSource): Long = sumWhere { it.source == source }

  fun bytesFor(activity: BackgroundActivity): Long = bytesFor(TrafficSource.of(activity))

  fun bytesFor(network: TrafficNetwork): Long = sumWhere { it.network == network }

  fun bytesFor(source: TrafficSource, network: TrafficNetwork): Long =
    bytes[TrafficKey(source, network)] ?: 0L

  private inline fun sumWhere(predicate: (TrafficKey) -> Boolean): Long {
    var total = 0L
    for ((key, value) in bytes) {
      if (predicate(key)) total = saturatingAdd(total, value)
    }
    return total
  }

  companion object {
    fun empty(cycle: BillingCycle) = CycleUsage(cycle)
  }
}

internal fun saturatingAdd(a: Long, b: Long): Long {
  val sum = a + b
  // Overflow: i due operandi hanno lo stesso segno e la somma ha segno opposto.
  return if (((a xor sum) and (b xor sum)) < 0) Long.MAX_VALUE else sum
}
