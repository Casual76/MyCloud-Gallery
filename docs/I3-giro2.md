# I3, giro di correzione 2 della fase I (12/11/2026)

Rilievi: `review-fase-I/giro2/TRIAGE.md` per I3 (CG-5, CU-28 = CS-18, CU-29, CS-22, CU-30, CS-17, CU-32, «media», mutazioni di CS-11),
più i componenti comuni di `85cef033` (`GalleryInfoRow`, `GalleryFigureState(why)`, `GalleryIconStatus.Waiting`).

## Che cosa è cambiato

- **Icona dell'app al rientro rapido (CG-5).** Il cambio accodato all'uscita ricontrolla **subito prima di ogni scrittura** nel PackageManager
  che nessuna activity sia avviata; se l'app è tornata davanti (anche mentre il filo legge il PackageManager, o fra l'accensione del nuovo
  ingresso e lo spegnimento del vecchio) non scrive più niente e la scelta resta in attesa per la prossima uscita. Mai zero ingressi: se il
  processo muore a metà restano due ingressi accesi, e il riallineamento dell'avvio finisce il cambio alla prima uscita. Quattro prove con un
  esecutore differito in `AppIconSwitcherTest` (rientro prima che il lavoro giri, rientro durante la lettura, rientro a metà cambio e processo
  morto a metà, uscita-rientro-uscita).
- **Libera spazio, il «+N» (CU-28 = CS-18 a).** Il riquadro si misura prima delle anteprime e prende la larghezza che gli serve; le anteprime sono
  quante ne stanno accanto, al più quattro (`freePreviewFit`, con la sua prova). «+1.236» non diventa più «+1.23».
- **Copertine del backup (CS-18 b).** Due righe riservate al nome in ogni copertina: barre e numeri della stessa riga allineati.
- **Un solo admin (CU-29).** È un rischio vero (perso quel telefono senza il kit, nessuno gestisce più la famiglia): «attenzione» anche nella
  testata della pagina principale, con il perché dietro la «i», come nella pagina Famiglia (`SettingsUiState.soleAdmin`, prova nel ViewModel).
- **Scelte e interruttori (CS-22).** Niente freccia accanto alle scelte delle Anteprime del NAS né accanto all'interruttore delle copertine
  degli album; le tre icone dell'app in tre terzi uguali, e col testo grande l'etichetta scende di un gradino.
- **Una sola «i» (CU-30, CS-21).** `SettingsInfo` e `BackupInfoToggle` non ci sono più: 45 righe usano `GalleryInfoRow` di core-designsystem
  (tinte fisse, tutta la riga si tocca, TalkBack sente etichetta e perché). La «i» della testata del Backup usa la tinta del componente.
- **Stati vuoti (CS-17).** Gli 11 `FluidEmptyState` di feature-settings sono `GalleryFigureState` con la figura, il titolo e il perché nel
  `why` del componente.
- **L'attesa.** `GalleryIconStatus.Waiting` (la clessidra comune) sulla testata del Backup in attesa, sulla riga «Backup», sulla famiglia in
  attesa di approvazione e sulle richieste dei dispositivi; il disco fatto a mano del giro 1 è tolto.
- **Screenshot di I5 (CU-32).** `docs/screenshots/beta-grafica/I5/` rifatti con la pagina Impostazioni di adesso.
- **«3 media spariti dall'area comune».** Nessuna stringa di risorsa contiene più «media» come sostantivo nel backup (grep su tutte le risorse
  di `feature-backup`; il titolo dice «foto e video»). Restano «posizione dei media» in core-mediastore (Diagnostica) e una frase di Ricerca simili
  («la media delle foto», nel senso di valore medio), non miei.

## Le prove di privacy con la mutazione (CS-11)

Un controllo tolto alla volta, un batch per mutazione con la sola classe di prova, il controllo rimesso subito dopo (script in un `finally`).

| Mutazione | Controllo tolto | Esito | Prova che cade |
|---|---|---|---|
| M1 | `BackupCovers.request`: `if (viewIndex < 0) return null` (foto fuori dalla Libreria visibile) | cade | `unaFotoFuoriDallaVistaNonHaRichiesta`, `leAnteprimeDiLiberaSpazioSaltanoLeFotoNascoste` |
| M2 | `BackupCovers.refresh`: il filtro `viewIndexOf(it) >= 0` sulle foto delle cartelle | cade | `unaFotoNascostaDopoEssereStataDisegnataSmetteDiEssereLaCopertina` |
| M3 | `CatalogSourceNewest`: `l.state = 'present'` | cade | `unaFotoToltaDalTelefonoOUnaCopiaDelNasNonSonoLaCopertina` |
| M4 | `CatalogSourceNewest`: `l.location_type = 'phone'` | cade | la stessa (la riga del NAS della prova ha ora volume e cartella: la esclude solo il tipo) |
| M5 | `CatalogSourceNewest`: il tetto per sorgente | **non cadeva**: con una sola sorgente il ciclo si fermava da solo. Prova nuova con due sorgenti, poi cade | `ilTettoValePerOgniSorgenteAncheConLeAltreNonPiene` |
| M6 | `CatalogSourceNewest`: solo le sorgenti chieste | cade | `dalPiuRecente_AlPiuTantePerSorgente_SoloQuelleChieste` |
| M7 | `AppIconSwitcher`: il ricontrollo «app ancora fuori» (CG-5) | cade | le tre prove `cg5_` con il rientro |

## Prove sul telefono (le fa l'orchestratore)

1. Icona dell'app: scegli «Scura», premi Home e riapri subito l'app, dieci volte di fila: la finestra non si chiude mai e la pagina resta quella;
   all'ultima uscita lasciata in pace l'icona sulla Home diventa la scura, una sola.
2. Libera spazio con migliaia di foto al sicuro: il «+N» si legge intero (anche a 1,3x).
3. Famiglia fondata da te, senza secondo admin: la testata della pagina principale ha il «!» e la «i» dice perché; aggiunto un secondo admin, la spunta.
4. Backup in attesa della carica: la clessidra sull'anello e sulla riga «Backup».
