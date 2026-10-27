package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesRecords
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import dev.pampa.mycloudgallery.core.personal.contract.MemoryLessLevel
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryMemoriesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.testing.MemoriesRegister
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La suite di contratto delle scelte sui ricordi (Fase 9, P0; `docs/fase9-progetto.md` D10, D11, Q9, E-MEM8, E-MEM9, E-MEM22, §5.2): gira sul
 * fake (`InMemoryMemoriesPersonalSettings`) e, da P6, su `SyncedMemoriesSettings` sopra lo store e il motore di sincronizzazione veri. Due
 * dispositivi dello stesso account, A e B; ognuno puo' non avere l'ora del NAS (fuori casa); una sincronizzazione porta le scelte dall'uno
 * all'altro.
 */
abstract class MemoriesPersonalSettingsContract {

  interface Rig {
    val a: MemoriesPersonalSettings
    val b: MemoriesPersonalSettings

    fun aOnline(online: Boolean)

    /** Un giro completo: A porta le sue scelte, B le legge e porta le sue, A rilegge. */
    suspend fun syncAll()

    /** Una voce scritta da un altro dispositivo dell'account (anche in una forma che questa versione non capisce). */
    suspend fun remoteWrites(key: PersonalKey, value: PersonalValue)

    /** Tutte le voci vive dell'account (dopo [syncAll]): per le prove "nessun nome". */
    fun liveValues(): Map<PersonalKey, PersonalValue>
  }

  protected abstract fun rig(): Rig

  protected fun memoryId(n: Int): String {
    val digest = MessageDigest.getInstance("SHA-256").digest("ricordo-$n".toByteArray())
    return Base32.encode(digest.copyOf(16))
  }

  private val anna = PersonId.fromBytes(ByteArray(16) { (it + 7).toByte() })
  private val marco = PersonId.fromBytes(ByteArray(16) { (it + 70).toByte() })

  @Test
  fun senzaVociNienteSceltoEIRicordiConLePersoneSonoAccesi() {
    val choices = rig().a.current()
    assertThat(choices.seen).isEmpty()
    assertThat(choices.dismissed).isEmpty()
    assertThat(choices.people).isEmpty()
    assertThat(choices.neverDays).isEmpty()
    assertThat(choices.peopleMemories).isTrue()
  }

  /** E-MEM8: "visto" e "Non mostrare" valgono su tutti i dispositivi dell'account dopo un giro. */
  @Test
  fun vistoEScartatoViaggianoFraIDispositivi() = runTest {
    val rig = rig()
    rig.a.markSeen(memoryId(1), 20261027)
    rig.a.setDismissed(memoryId(2), true)
    assertThat(rig.a.current().seen).containsExactly(memoryId(1), 20261027)
    assertThat(rig.a.choices.value.dismissed).containsExactly(memoryId(2))
    rig.syncAll()
    assertThat(rig.b.current().seen).containsExactly(memoryId(1), 20261027)
    assertThat(rig.b.current().dismissed).containsExactly(memoryId(2))
  }

  /** D10: aprire di nuovo lo stesso giorno non riscrive; un giorno piu' vecchio non torna indietro; uno piu' nuovo si'. */
  @Test
  fun vistoSiScriveSoloSeIlGiornoAvanza() = runTest {
    val rig = rig()
    rig.a.markSeen(memoryId(1), 20261027)
    rig.a.markSeen(memoryId(1), 20261027)
    rig.a.markSeen(memoryId(1), 20251027)
    assertThat(rig.a.current().seen[memoryId(1)]).isEqualTo(20261027)
    rig.a.markSeen(memoryId(1), 20261101)
    assertThat(rig.a.current().seen[memoryId(1)]).isEqualTo(20261101)
  }

  /** "Annulla" entro 10 s: la lapide toglie lo scarto, anche sull'altro dispositivo. */
  @Test
  fun annullaToglieLoScartoOvunque() = runTest {
    val rig = rig()
    rig.a.setDismissed(memoryId(3), true)
    rig.syncAll()
    assertThat(rig.b.current().dismissed).contains(memoryId(3))
    rig.a.setDismissed(memoryId(3), false)
    rig.syncAll()
    assertThat(rig.a.current().dismissed).isEmpty()
    assertThat(rig.b.current().dismissed).isEmpty()
  }

  /** E-MEM22: fuori casa la scelta vale subito qui e viaggia alla prima sincronizzazione. */
  @Test
  fun senzaLOraDelNasLaSceltaValeSubitoEViaggiaDopo() = runTest {
    val rig = rig()
    rig.aOnline(false)
    rig.a.setLess(anna, MemoryLessLevel.LESS)
    rig.a.setNeverDays(MemoryDays(20250301, 20250310), true)
    assertThat(rig.a.current().people).containsExactly(anna, MemoryLessLevel.LESS)
    assertThat(rig.a.current().neverOn(20250305)).isTrue()
    assertThat(rig.b.current().people).isEmpty()
    rig.aOnline(true)
    rig.syncAll()
    assertThat(rig.b.current().people).containsExactly(anna, MemoryLessLevel.LESS)
    assertThat(rig.b.current().neverOn(20250310)).isTrue()
    assertThat(rig.b.current().neverOn(20250311)).isFalse()
  }

  /** D11: "Mostra meno" e "Mai" per una persona; togliere la scelta e' una lapide. */
  @Test
  fun mostraMenoEMaiPerUnaPersonaETogliere() = runTest {
    val rig = rig()
    rig.a.setLess(anna, MemoryLessLevel.LESS)
    rig.a.setLess(marco, MemoryLessLevel.NEVER)
    assertThat(rig.a.current().people).containsExactly(anna, MemoryLessLevel.LESS, marco, MemoryLessLevel.NEVER)
    rig.a.setLess(anna, null)
    rig.syncAll()
    assertThat(rig.b.current().people).containsExactly(marco, MemoryLessLevel.NEVER)
  }

  /** D11, D37: "Disattiva e cancella" delle persone: tutte le voci `memories_less:person:*` diventano lapidi, anche quelle di altri dispositivi. */
  @Test
  fun leLapidiDelJanitorTolgonoOgniSceltaSulleePersone() = runTest {
    val rig = rig()
    rig.a.setLess(anna, MemoryLessLevel.LESS)
    rig.syncAll()
    rig.b.setLess(marco, MemoryLessLevel.NEVER)
    rig.syncAll()
    assertThat(rig.a.dropAllPeopleChoices()).isEqualTo(2)
    rig.syncAll()
    assertThat(rig.a.current().people).isEmpty()
    assertThat(rig.b.current().people).isEmpty()
    assertThat(rig.a.dropAllPeopleChoices()).isEqualTo(0)
  }

  /** Q9 (a): di serie acceso; spento su un dispositivo, spento ovunque. */
  @Test
  fun ricordiConLePersoneSiSpengonoPerLAccount() = runTest {
    val rig = rig()
    rig.a.setPeopleMemories(false)
    rig.syncAll()
    assertThat(rig.b.current().peopleMemories).isFalse()
    rig.b.setPeopleMemories(true)
    rig.syncAll()
    assertThat(rig.a.current().peopleMemories).isTrue()
  }

  /** Un valore che non si capisce vale "nessuna scelta" e non rovina le altre voci (un'app piu' nuova, dati ostili). */
  @Test
  fun unValoreCheNonSiCapisceValeNessunaScelta() = runTest {
    val rig = rig()
    rig.a.setLess(anna, MemoryLessLevel.NEVER)
    rig.remoteWrites(MemoriesRecords.lessKey(marco), PersonalValue.Text("molto-meno"))
    rig.remoteWrites(MemoriesRecords.seenKey(memoryId(5)), PersonalValue.Number(20261399))
    rig.remoteWrites(MemoriesRecords.dismissedKey(memoryId(6)), PersonalValue.Text("si"))
    rig.remoteWrites(MemoriesRecords.peopleKey, PersonalValue.Text("no"))
    rig.syncAll()
    val choices = rig.a.current()
    assertThat(choices.people).containsExactly(anna, MemoryLessLevel.NEVER)
    assertThat(choices.seen).doesNotContainKey(memoryId(5))
    assertThat(choices.dismissed).doesNotContain(memoryId(6))
    assertThat(choices.peopleMemories).isTrue()
  }

  /** D11: mai un nome; i valori sono codici, giorni e flag; le chiavi solo id. */
  @Test
  fun nelleVociNessunNomeSoloIdCodiciEGiorni() = runTest {
    val rig = rig()
    rig.a.markSeen(memoryId(1), 20261027)
    rig.a.setDismissed(memoryId(2), true)
    rig.a.setLess(anna, MemoryLessLevel.LESS)
    rig.a.setNeverDays(MemoryDays(20240101, 20241231), true)
    rig.a.setPeopleMemories(false)
    rig.syncAll()
    val live = rig.liveValues()
    assertThat(live).hasSize(5)
    for ((key, value) in live) {
      assertThat(MemoriesRecords.isMemoriesKey(key)).isTrue()
      if (value is PersonalValue.Text) assertThat(value.text).isAnyOf("less", "never")
    }
  }

  /** Il giorno di "visto" deve essere un giorno; un id che non e' un id di ricordo si rifiuta prima di scrivere. */
  @Test
  fun idEGiorniNonValidiSiRifiutano() = runTest {
    val rig = rig()
    for (bad in listOf("", "ricordo-2025-10", memoryId(1).uppercase(), memoryId(1) + "a")) {
      val failure = runCatching { rig.a.markSeen(bad, 20261027) }.exceptionOrNull()
      assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }
    assertThat(runCatching { rig.a.markSeen(memoryId(1), 20261340) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    assertThat(rig.a.current().seen).isEmpty()
  }
}

/** La suite sul fake (§5.2): due dispositivi finti sullo stesso registro. */
class InMemoryMemoriesPersonalSettingsTest : MemoriesPersonalSettingsContract() {
  override fun rig(): Rig = object : Rig {
    private val register = MemoriesRegister()
    private val first = InMemoryMemoriesPersonalSettings(register)
    private val second = InMemoryMemoriesPersonalSettings(register)
    override val a: MemoriesPersonalSettings = first
    override val b: MemoriesPersonalSettings = second

    override fun aOnline(online: Boolean) {
      first.online = online
    }

    override suspend fun syncAll() {
      if (first.online) first.sync()
      second.sync()
      if (first.online) first.sync()
    }

    override suspend fun remoteWrites(key: PersonalKey, value: PersonalValue) {
      register.inject(key, value)
    }

    override fun liveValues(): Map<PersonalKey, PersonalValue> = register.live()
  }
}
