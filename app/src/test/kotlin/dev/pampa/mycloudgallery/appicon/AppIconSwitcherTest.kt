package dev.pampa.mycloudgallery.appicon

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.feature.settings.appicon.AppIconArt
import dev.pampa.mycloudgallery.feature.settings.appicon.AppIconChoice
import java.util.concurrent.Executor
import org.junit.Test

/**
 * I5: il cambio dell'icona dell'app (`AppIconSwitcher`) con un PackageManager finto. Si prova l'ordine (prima si abilita il nuovo, poi si
 * disabilitano gli altri, mai zero ingressi), che con l'app aperta non si tocca niente (si cambia in secondo piano), il ritorno all'automatica
 * se qualcosa va storto e il riallineamento all'avvio.
 */
class AppIconSwitcherTest {

  /** Gli ingressi del launcher come li vede il PackageManager, con il diario delle scritture ("+nome", "-nome"). */
  private class FakeComponents(vararg initial: String) : LauncherComponents {
    val enabled = initial.toMutableSet()
    val writes = mutableListOf<String>()
    var smallest = enabled.size
    var failOn: String? = null
    var broken = false

    override fun isEnabled(className: String): Boolean {
      if (broken) throw IllegalStateException("PackageManager morto")
      return className in enabled
    }

    override fun setEnabled(className: String, enabled: Boolean) {
      val write = (if (enabled) "+" else "-") + className.substringAfterLast('.')
      if (write == failOn) throw SecurityException("finto")
      writes += write
      if (enabled) this.enabled += className else this.enabled -= className
      smallest = minOf(smallest, this.enabled.size)
    }
  }

  private class MemoryStore(var value: AppIconChoice = AppIconChoice.AUTO) : AppIconStore {
    override fun read(): AppIconChoice = value

    override fun write(choice: AppIconChoice) {
      value = choice
    }
  }

  private val inline = Executor { it.run() }

  private fun switcher(components: FakeComponents, store: MemoryStore = MemoryStore()) =
    AppIconSwitcher(store, components, AppIconArt(foreground = 1, lightBackground = 2, darkBackground = 3), inline)

  @Test
  fun conLAppApertaLaSceltaSiSegnaSubitoELIngressoCambiaInSecondoPiano() {
    val components = FakeComponents(LauncherAliases.AUTO)
    val store = MemoryStore()
    val icon = switcher(components, store)
    icon.onStarted()

    icon.choose(AppIconChoice.DARK)

    assertThat(icon.choice.value).isEqualTo(AppIconChoice.DARK)
    assertThat(store.value).isEqualTo(AppIconChoice.DARK)
    assertWithMessage("con l'app aperta non si tocca nessun ingresso (disabilitare quello da cui e' aperta la chiuderebbe)").that(components.writes).isEmpty()

    icon.onStopped(changingConfigurations = false)

    assertWithMessage("prima si abilita il nuovo, poi si disabilitano gli altri").that(components.writes)
      .containsExactly("+LauncherDark", "-LauncherAuto", "-LauncherLight").inOrder()
    assertThat(components.enabled).containsExactly(LauncherAliases.DARK)
    assertWithMessage("mai zero ingressi del launcher").that(components.smallest).isAtLeast(1)
  }

  @Test
  fun unaRotazioneNonEUnUscita() {
    val components = FakeComponents(LauncherAliases.AUTO)
    val icon = switcher(components)
    icon.onStarted()
    icon.choose(AppIconChoice.LIGHT)

    icon.onStopped(changingConfigurations = true)
    icon.onStarted()
    assertThat(components.writes).isEmpty()

    icon.onStopped(changingConfigurations = false)
    assertThat(components.enabled).containsExactly(LauncherAliases.LIGHT)
  }

  @Test
  fun dueFinestreSiCambiaSoloQuandoSiFermaLUltima() {
    val components = FakeComponents(LauncherAliases.AUTO)
    val icon = switcher(components)
    icon.onStarted()
    icon.onStarted()
    icon.choose(AppIconChoice.DARK)

    icon.onStopped(changingConfigurations = false)
    assertThat(components.writes).isEmpty()
    icon.onStopped(changingConfigurations = false)
    assertThat(components.enabled).containsExactly(LauncherAliases.DARK)
  }

  @Test
  fun piuScelteDiFilaSiApplicaSoloLUltimaUnaVolta() {
    val components = FakeComponents(LauncherAliases.AUTO)
    val icon = switcher(components)
    icon.onStarted()
    icon.choose(AppIconChoice.DARK)
    icon.choose(AppIconChoice.LIGHT)
    icon.choose(AppIconChoice.DARK)

    icon.onStopped(changingConfigurations = false)
    icon.onStarted()
    icon.onStopped(changingConfigurations = false)

    assertThat(components.writes).containsExactly("+LauncherDark", "-LauncherAuto", "-LauncherLight").inOrder()
  }

  @Test
  fun tornareAllaSceltaDiAdessoNonScriveNiente() {
    val components = FakeComponents(LauncherAliases.AUTO)
    val icon = switcher(components)
    icon.onStarted()
    icon.choose(AppIconChoice.AUTO)
    icon.onStopped(changingConfigurations = false)

    assertThat(components.writes).isEmpty()
    assertThat(components.enabled).containsExactly(LauncherAliases.AUTO)
  }

  @Test
  fun conLAppInSecondoPianoSiCambiaSubito() {
    val components = FakeComponents(LauncherAliases.AUTO)
    val icon = switcher(components)

    icon.choose(AppIconChoice.LIGHT)

    assertThat(components.enabled).containsExactly(LauncherAliases.LIGHT)
  }

  @Test
  fun seDisabilitareIlVecchioFallisceSiTornaAllAutomatica() {
    val components = FakeComponents(LauncherAliases.LIGHT)
    val store = MemoryStore(AppIconChoice.LIGHT)
    val icon = switcher(components, store)
    icon.onStarted()
    icon.choose(AppIconChoice.DARK)
    components.failOn = "-LauncherAuto"

    icon.onStopped(changingConfigurations = false)

    assertThat(icon.choice.value).isEqualTo(AppIconChoice.AUTO)
    assertThat(store.value).isEqualTo(AppIconChoice.AUTO)
    assertThat(components.enabled).contains(LauncherAliases.AUTO)
    assertWithMessage("anche quando qualcosa va storto, mai zero ingressi").that(components.smallest).isAtLeast(1)
  }

  @Test
  fun seAbilitareIlNuovoFallisceNonSiDisabilitaNienteESiTornaAllAutomatica() {
    val components = FakeComponents(LauncherAliases.AUTO).apply { failOn = "+LauncherDark" }
    val icon = switcher(components)
    icon.onStarted()
    icon.choose(AppIconChoice.DARK)

    icon.onStopped(changingConfigurations = false)

    assertThat(components.enabled).containsExactly(LauncherAliases.AUTO)
    assertThat(icon.choice.value).isEqualTo(AppIconChoice.AUTO)
    assertThat(components.smallest).isAtLeast(1)
  }

  @Test
  fun allAvvioSenzaIngressiSiAbilitaSubitoQuelloScelto() {
    val components = FakeComponents()
    val icon = switcher(components, MemoryStore(AppIconChoice.DARK))

    icon.realign()

    assertThat(components.writes).containsExactly("+LauncherDark")
    assertThat(components.enabled).containsExactly(LauncherAliases.DARK)
    // Allineato: il passaggio in secondo piano non riscrive niente.
    icon.onStarted()
    icon.onStopped(changingConfigurations = false)
    assertThat(components.writes).containsExactly("+LauncherDark")
  }

  @Test
  fun allAvvioUnoStatoDiversoDallaSceltaSiCorreggeInSecondoPiano() {
    // Un processo chiuso prima del cambio, o un aggiornamento: la scelta salvata e' la chiara, il launcher ha ancora l'automatica.
    val components = FakeComponents(LauncherAliases.AUTO)
    val icon = switcher(components, MemoryStore(AppIconChoice.LIGHT))

    icon.realign()
    assertWithMessage("all'avvio non si disabilita niente (l'app si sta aprendo da quell'ingresso)").that(components.writes).isEmpty()

    icon.onStarted()
    icon.onStopped(changingConfigurations = false)
    assertThat(components.writes).containsExactly("+LauncherLight", "-LauncherAuto", "-LauncherDark").inOrder()
  }

  @Test
  fun dueIngressiAbilitatiSiRiportanoAUno() {
    val components = FakeComponents(LauncherAliases.AUTO, LauncherAliases.DARK)
    val icon = switcher(components, MemoryStore(AppIconChoice.DARK))

    icon.realign()
    icon.onStarted()
    icon.onStopped(changingConfigurations = false)

    assertThat(components.enabled).containsExactly(LauncherAliases.DARK)
  }

  @Test
  fun unPackageManagerCheNonRispondeNonFaToccareNiente() {
    val components = FakeComponents(LauncherAliases.AUTO).apply { broken = true }
    val icon = switcher(components, MemoryStore(AppIconChoice.DARK))

    icon.realign()
    icon.onStarted()
    icon.onStopped(changingConfigurations = false)

    assertThat(components.writes).isEmpty()
  }

  @Test
  fun unValoreSalvatoRovinatoValeAutomatica() {
    assertThat(AppIconChoice.ofCode("arcobaleno")).isEqualTo(AppIconChoice.AUTO)
    assertThat(AppIconChoice.ofCode(null)).isEqualTo(AppIconChoice.AUTO)
    assertThat(AppIconChoice.entries.map { AppIconChoice.ofCode(it.code) }).containsExactlyElementsIn(AppIconChoice.entries).inOrder()
    assertThat(AppIconChoice.entries.map(LauncherAliases::of)).containsExactlyElementsIn(LauncherAliases.all).inOrder()
  }
}
