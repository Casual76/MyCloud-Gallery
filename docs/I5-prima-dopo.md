# I5: l'icona dell'app a scelta, prima e dopo (fase I, 12/11/2026)

La richiesta dell'utente (12/11): «Mettiamo nelle impostazioni un'opzione di personalizzazione per scegliere un'icona singola al posto della
variabilità automatica in base al tema, nel caso qualcuno non lo volesse.» Il metro della fase I: **«se non sapessi leggere, potrei usarla?»**.
Gli screenshot sono in `docs/screenshots/beta-grafica/I5/`.

| Pagina | Prima | Dopo | Parole visibili |
|---|---|---|---|
| Impostazioni, gruppo «Foto» | — | la riga «Icona dell'app» accanto a «Widget»: a sinistra **l'icona scelta, quella vera, in piccolo**, a destra il suo nome | +3 («Icona dell'app», «Automatica») |
| Impostazioni → Icona dell'app (nuova) | — | tre anteprime grandi una accanto all'altra (Automatica metà chiara e metà scura in diagonale, Chiara, Scura) disegnate con le risorse vere; la scelta di adesso con l'anello e la spunta; sotto, una riga sola | 20 (il titolo, tre nomi, l'avviso di 15 parole) |

## Come funziona, in breve

- Tre ingressi del launcher (`activity-alias` della `MainActivity`), uno per icona, uno solo abilitato: di serie l'automatica (segue il tema; dove il
  launcher non lo segue si vede la scura, la risorsa di base).
- Il tocco segna subito la scelta; l'icona sulla Home cambia **quando si esce dall'app** (in secondo piano). Cambiarla con l'app aperta la
  chiuderebbe davanti all'utente: per Android l'app aperta dall'icona porta il nome di quell'ingresso, e disabilitarlo chiude la finestra.
- Prima si abilita il nuovo ingresso, poi si disabilitano gli altri: mai zero icone. Se qualcosa va storto si torna all'automatica.

## Le scelte di disegno che l'utente potrebbe voler cambiare

- La riga sta nel gruppo **«Foto»**, subito dopo «Widget»: sono le due cose dell'app che stanno sulla Home (un gruppo «Aspetto» nelle Impostazioni non c'è).
- L'anteprima «Automatica» è **metà chiara e metà scura in diagonale** (non le due icone sovrapposte): a 80 dp si legge meglio.
- Gli angoli delle anteprime sono quelli **del launcher di One UI** (circa un terzo del lato); su un altro launcher l'icona vera può essere tonda.
- L'icona cambia **uscendo dall'app**, non al tocco (la pagina lo mostra subito con l'anello e la spunta). La riga d'avviso non lo dice: tornando alla
  Home lo si vede da sé.
- La scelta vale per **questo dispositivo** (non si sincronizza con gli altri della famiglia).
