package dev.pampa.mycloudgallery.core.nas.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Il registro degli handle propri ([OwnHandles]): la prova che un `ACCESS_DENIED` su una cartella dipende da un handle nostro aperto sotto di
 * lei. Conta solo cio' che sta SOTTO la cartella (non lei, non un nome che ne ha il prefisso, non un'altra share), senza distinguere le
 * maiuscole (come Samba), dall'apertura alla chiusura vera.
 */
class OwnHandlesRegistryTest {

  private val handles = OwnHandles()

  @Test
  fun contaSoloQuelloCheStaSottoLaCartella() {
    val file = handles.opened(OwnHandles.Kind.FILE, "Test", "Foto\\Mare\\a.jpg")
    val folder = handles.opened(OwnHandles.Kind.DIRECTORY, "test", "Foto\\Mare")
    handles.opened(OwnHandles.Kind.FILE, "test", "Fotografie\\b.jpg")
    handles.opened(OwnHandles.Kind.FILE, "altra", "Foto\\x.jpg")

    assertThat(handles.countBelow("TEST", "foto")).isEqualTo(2)
    // La cartella stessa non e' "sotto": solo il file.
    assertThat(handles.countBelow("test", "Foto\\Mare")).isEqualTo(1)
    assertThat(handles.countBelow("test", "foto/mare/a.jpg")).isEqualTo(0)
    assertThat(handles.countBelow("test", "Altro")).isEqualTo(0)

    handles.closed(file)
    assertThat(handles.countBelow("test", "Foto")).isEqualTo(1)
    handles.closed(folder)
    assertThat(handles.countBelow("test", "Foto")).isEqualTo(0)
  }

  @Test
  fun rilascioSoloQuelliCheSannoRilasciarsiESoloSottoLaCartella() {
    val released = mutableListOf<String>()
    handles.opened(OwnHandles.Kind.FILE, "test", "Foto/a.jpg").release = { released += "a" }
    handles.opened(OwnHandles.Kind.FILE, "test", "Foto/Mare/b.jpg").release = { released += "b" }
    handles.opened(OwnHandles.Kind.DIRECTORY, "test", "Foto/Mare/Sera") // un elenco: non si rilascia, si aspetta
    handles.opened(OwnHandles.Kind.FILE, "test", "Altra/c.jpg").release = { released += "c" }

    assertThat(handles.releaseBelow("test", "Foto/Mare")).isEqualTo(1)
    assertThat(released).containsExactly("b")
    assertThat(handles.releaseBelow("test", "Foto")).isEqualTo(2)
    assertThat(released).containsExactly("b", "a", "b")
  }

  @Test
  fun unaSessioneChiusaNonHaPiuHandle() {
    handles.opened(OwnHandles.Kind.FILE, "test", "Foto/a.jpg")
    handles.clear()
    assertThat(handles.size).isEqualTo(0)
    assertThat(handles.countBelow("test", "Foto")).isEqualTo(0)
  }
}
