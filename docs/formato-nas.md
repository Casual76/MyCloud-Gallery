# Formato dei dati condivisi sul NAS e modello di fiducia

| | |
|---|---|
| **Stato** | Fase 1.4, 12/10/2026, **aggiornato dopo la revisione di sicurezza e la sua verifica** (`docs/review/1.4-core-trust.md`, due giri) e **esteso nella Fase 2 (pacchetto P3)**: configurazione di famiglia completa (§5.3), segnalazione "perso" (tipo 7, §5.9), stato del dispositivo (tipo 8, §5.10), rifondazione dopo un reset (§4.10), residui N2, R2, R3, R4 della revisione chiusi (§2.3, §4.4). **Completato nella Fase 4 (P0, 14/10/2026)** con le modifiche M1–M12 di `docs/fase4-progetto.md` §0.3: classi dei segmenti nelle teste (§5.5), contenuto dei segmenti (§5.5-bis), regole di pubblicazione (§5.5), unita' di lavoro (§5.6), campi nuovi del cestino (§5.7), pulizia dei propri file (§2.3), dati personali (§5.5), salto dell'ora (§6.1), tetti nuovi (§7), modulo unico delle operazioni distruttive (§9), involucro dei file cestinati (§2.1), mappa (§14). Formato **versione 1**. **Fase 5 (avvio, 16/10/2026)**: N1–N5 di `docs/fase5-progetto.md` §0.3, **nessun file firmato cambia** (il backup scrive media, non dati del formato): temporanei del backup e cartelle `Backup/` e `Versioni/` (§2.1, §2.2), pulizia dei propri file (§2.3), record `meta` delle versioni (§5.5-bis), il backup che scrive ma non toglie (§9, punto 8). **Fase 6 (avvio, 21/10/2026)**: A1–A8 di `docs/fase6-progetto.md` §0.3, **versione 2 del formato solo per i file `ai`**: testa `ai` con le sole classi `vec` (7) e `ocr` (8) in `devices/<id>/ai/` (§2.2, §5.5, §5.5-bis), attestazione e fusione dei dati derivati (§5.5-bis), unita' di lavoro `ai` (§5.6), tetti nuovi (§7), versioni accettate 1..2 (§10), copie dei modelli in `devices/<id>/models/` (§2.1, §2.2); §9 invariato. I file della versione 1 non cambiano. Nessun dato e' ancora stato scritto sul NAS. L'uso da parte dell'app: `docs/famiglia-dispositivi.md`. |
| **Codice** | modulo `:core-trust` (`dev.pampa.mycloudgallery.core.trust`); la mappa sezione → file → test e' al §14. |
| **Sigle** | `E-xx` = casi limite del piano; `C1`…`C23`, `L1`…`L17`, `S1`…`S12` = `docs/sicurezza.md`; `A1`…`I10` = voci della revisione. `F6-Dn` = decisione di `docs/fase6-progetto.md` §0.1 (le `Dn` senza prefisso sono quelle della Fase 4 o 5 indicate nel testo); `A1`…`A8` con "Fase 6" = le modifiche di `docs/fase6-progetto.md` §0.3. |
| **Regola** | Ogni costante citata qui esiste identica nel codice (`TrustFormat`, `TrustLimits`). Cambiarne una vuol dire cambiare questo documento. Le righe del §7 che nominano una costante tra apici inversi sono confrontate con il codice da un test (`IndexSegmentTypesTest`). |

---

## 0. Il problema in una pagina

In famiglia non c'e' un server. Il NAS non esegue codice nostro: e' solo un disco condiviso via SMB. I
telefoni e i profili del tablet scrivono e leggono file nelle share (aree private e area comune), e ogni
dispositivo si costruisce da quei file la propria replica locale. Quindi:

1. **Chi scrive conta, non dove scrive.** Chiunque abbia accesso SMB in scrittura (un PC con un virus, un
   apparecchio della rete se le cartelle sono "pubbliche", un familiare) puo' mettere file nelle cartelle
   dell'app. Un dato vale solo se e' **firmato** da una chiave che la famiglia ha approvato.
2. **La radice di fiducia e' una chiave, non il NAS.** La *chiave di famiglia* firma il *registro*: l'elenco
   dei dispositivi approvati. Ogni dispositivo firma i propri dati con la *sua* chiave, chiusa nel Keystore.
3. **Il NAS non puo' farci tornare indietro.** Il registro ha un contatore che cresce e puo' solo diventare
   piu' severo verso i dispositivi gia' noti; ogni dispositivo ricorda l'ultimo visto, e un dispositivo nuovo
   riceve dal QR il minimo accettabile.
4. **Leggere non deve mai far male.** Tutto cio' che arriva dal NAS si legge con tetti di dimensione, in una
   forma unica (canonica, firme comprese), con percorsi validati; e nessun dato letto puo' da solo far
   cancellare o spostare un file.
5. **Semplice di proposito.** Un algoritmo di firma, un algoritmo di cifratura, un formato di busta, nessuna
   negoziazione, nessuna sovrascrittura: ogni file si pubblica una volta con un nome nuovo.

---

## 1. Attori e chiavi

| Chi | Chiave | Dove vive | Che cosa firma |
|---|---|---|---|
| **Famiglia** (l'admin) | *chiave di famiglia*, ECDSA P-256 | Sul dispositivo admin, cifrata da una chiave AES del Keystore usabile solo a telefono sbloccato (§4.2). Nel kit di recupero, cifrata col codice di recupero (§4.8). | Solo il **registro di famiglia** (configurazione + dispositivi). |
| **Ogni dispositivo** (ogni profilo Android e' un dispositivo) | *chiave del dispositivo*, ECDSA P-256 | Keystore Android, StrongBox se c'e' (altrimenti TEE). **Non estraibile.** | La propria richiesta di registrazione, le proprie teste di dati (indice, dati personali), prenotazioni, record del cestino, album condivisi, segnalazioni. |
| **Il NAS** | nessuna | – | Nulla. Fornisce solo file e l'**ora di riferimento** (§6). |

Un dispositivo non approvato (in attesa) **legge** ma nessuno accetta cio' che scrive (E-S14).

---

## 2. Struttura sul NAS

### 2.1 La cartella dell'app

Ogni share usata dall'app contiene **una** cartella `.mycloudgallery/`:

- **nascosta**: il punto iniziale la nasconde su Linux e, con l'opzione di Samba `hide dot files` (attiva di
  serie; da verificare sul firmware WD, domanda aperta 5 della revisione), anche ai client Windows;
- **nessun file ha un'estensione**: Twonky, Plex, DLNA e Foto di Windows scelgono i file per estensione, quindi
  non vedono nulla (E-I13);
- **i file del formato iniziano con una firma propria** (`MCGS`, `MCGB`, `MCGK`), mai con i byte di
  un'immagine: le miniature stanno *dentro* i segmenti. **Eccezione dichiarata** (I6): i file nel cestino
  (`trash/<id>/files/`) sono le foto e i video cestinati, rinominati senza estensione, e conservano i loro
  byte; li nascondono solo il punto iniziale della cartella e l'assenza di estensione. Se in Fase 4.5 si
  scopre che un servizio del NAS riconosce i media dal contenuto, quei file si avvolgono in un contenitore.
- **Seconda eccezione dichiarata (Fase 6, A1, F6-D18).** Le copie dei modelli sul NAS (`devices/<id dispositivo>/models/<sha256>`,
  §2.2) sono i file dei modelli come arrivano da Internet: grezzi, **senza busta, senza firma e senza estensione**, con il nome
  uguale al loro SHA-256. Non sono dati del formato: le protegge la dimensione con l'SHA-256 fissati nell'app e controllati da chi le
  legge a ogni caricamento (F6-D16), non una firma; una copia con il nome giusto e byte diversi e' scartata e basta. Le nascondono solo
  il punto iniziale della cartella e l'assenza di estensione, come per il cestino.

**Prova E-I13 (Fase 4, M11).** La prova sul campo F14 (`docs/fase4-progetto.md` §5.7: Twonky e Plex con
`test-comune` aggiunta per la prova, Foto di Windows sulla share) dice se l'indice o il cestino compaiono. **Esito: da
fare** (F14 e' della chiusura della 4A; il cestino arriva con la 4B). Se un servizio riconosce i file cestinati dal
contenuto, si avvolgono nell'involucro **`MCGW`**, definito qui come proposta e non ancora scritto da nessuno:

```
"MCGW" | versione (1) | 0x00 0x00 0x00 | byte originali del file, invariati
```

Il ripristino toglie gli 8 byte iniziali; la dimensione registrata nel record del cestino (§5.7) resta quella
dell'originale. Si decide alla 4B, con l'esito di F14 (L30 di `docs/sicurezza.md`).

**`Backup/` e `Versioni/` sono cartelle normali (Fase 5, N5).** Le crea l'app fuori da `.mycloudgallery/` (§2.2) e
contengono media veri con la loro estensione, non file del formato: se la share e' nelle librerie di Twonky o di Plex, i
due servizi le vedono come ogni altra cartella di foto e di video, e le versioni precedenti possono comparire in TV
(L43 di `docs/sicurezza.md`). Non c'e' niente da nascondere: sono i file dell'utente. La prova F45 di
`docs/fase5-progetto.md` §5.6 dice se compaiono; l'utente decide poi se escludere `Versioni/` da Twonky e Plex.

L'indice di una share sta **dentro quella share** (stessi permessi dei file, C19). Il registro di famiglia
sta solo nell'**area comune**, perche' tutti devono leggerlo.

### 2.2 L'albero

```
<share>/.mycloudgallery/
├── family/                               solo nell'area comune
│   ├── registry/<versione>               [1] registro di famiglia (chiave di famiglia)
│   ├── requests/<id dispositivo>         [2] richieste di registrazione (auto-firmate)
│   └── reports/<id dispositivo>/<vers.>  [7] segnalazioni "perso" (§5.9; copia anche nell'area privata)
├── devices/<id dispositivo>/
│   ├── status/<versione>                 [8] stato del dispositivo (§5.10), solo nell'area comune
│   ├── index/head/<versione>             [3] testa dell'indice di questa share
│   ├── index/seg/<impronta>              segmenti (contenitori MCGB), nome = impronta
│   ├── ai/head/<versione>                [3] testa dei dati AI di questa share (Fase 6, versione 2 del formato)
│   ├── ai/seg/<impronta>                 segmenti vec e ocr (contenitori MCGB versione 2), nome = impronta
│   ├── models/<sha256>                   copia di un modello (Fase 6, F6-D18): solo nell'area comune, file grezzo (§2.1)
│   ├── personal/head/<versione>          [3] dati personali: SOLO nell'area privata del proprietario
│   └── personal/seg/<impronta>
├── leases/<impronta risorsa>/<gruppo>/<generazione>   [4] prenotazioni, gruppi di 256 generazioni
├── trash/<id dispositivo>/
│   ├── rec/<id elemento>                 [5] record firmato del cestino
│   └── files/<id elemento>               il file cestinato, rinominato senza estensione
├── albums/<id album>/<id dispositivo>/head/<versione>   [6] album condivisi (Fase 7, versione 3), SOLO area comune
├── albums/<id album>/<id dispositivo>/seg/<impronta>    segmenti MCGB classe `album` (17), nome = impronta (Fase 7)
├── faces/<generazione>/<id dispositivo>/head/<versione>  [9] dati dei volti (Fase 8, versione 4), SOLO area privata dell'account
├── faces/<generazione>/<id dispositivo>/seg/<impronta>   segmenti MCGB versione 4, classi `faces` (18), `cluster` (19), `people` (20)
├── faces/<generazione>/leases/<impronta risorsa>/<gruppo>/<numero>   [4] prenotazioni dei volti, sotto la generazione (Fase 8)
└── tmp/                                  temporanei della pubblicazione atomica; dalla Fase 5 anche i
                                          temporanei del backup <id dispositivo>-bk-<id> (senza estensione)

<area privata dell'admin>/.mycloudgallery/recovery/<id famiglia>/<impronta della chiave>   kit di recupero (§4.8)
<area privata di un account>/.mycloudgallery/family/reports/<id dispositivo>/<vers.>   [7] copia della segnalazione (prova di possesso, §5.9)
```

`[n]` e' il tipo del file firmato (§5.1). Ogni dispositivo scrive **solo sotto i propri id** (piu' le
richieste e le prenotazioni); l'admin scrive il registro.

**Album condivisi (Fase 7, A1; `docs/fase7-progetto.md` D6).** `albums/` esiste **solo nell'area comune** (`commonShare` del registro):
un **contributo** per album e per dispositivo, la testa in `albums/<id album>/<id dispositivo>/head/<versione>` e i suoi segmenti in
`albums/<id album>/<id dispositivo>/seg/<impronta>`. Ogni dispositivo scrive solo sotto la propria cartella, solo con `publishExclusive`
(nessuna sovrascrittura, E-SH6); `<id album>` e' l'id dell'album in base32 (26 caratteri, i suoi 128 bit, §5.8). Fuori da
`.mycloudgallery/` c'e' la cartella dei file dell'album scelta con la risposta Q1 (a), `Album condivisi/<nome>`: non fa parte del
formato, sono file di media normali (un file aggiunto li' dal PC non entra nell'album, D39).

**Dati dei volti (Fase 8, A1; `docs/fase8-progetto.md` D11, D12, D16).** `faces/` esiste **solo nell'area privata di un account**, mai
nell'area comune, nemmeno per i volti delle foto dell'area comune (proposta 13, L23): una cartella per **generazione** (`<generazione>` =
128 bit casuali in base32, 26 caratteri, `FaceGeneration`; ne nasce una a ogni attivazione, voce `faces` del §5.5-bis) e, dentro, una
cartella per ogni dispositivo **dello stesso account** con la testa dei volti (tipo 9, §5.11) e i segmenti delle classi 18–20 (§5.5-bis).
Le prenotazioni dei volti stanno **sotto la generazione** (`faces/<G>/leases/`, §5.6): "Disattiva e cancella" toglie tutto togliendo una
cartella. Ogni dispositivo scrive solo sotto `faces/<G>/<io>/` e nelle prenotazioni, solo con `publishExclusive`; i percorsi li
costruisce `NasLayout` (`facesGenerationDir`, `facesDeviceDir`, `facesHead`, `facesSegment`, `facesLeaseRoot`), mai un dato letto.

**Dati AI e copie dei modelli (Fase 6, A1).**
- `ai/head/` e `ai/seg/` stanno **in ogni share indicizzata**, accanto a `index/`: vettori e testo letto di un file stanno nella
  share di quel file, con gli stessi permessi (C19). Una testa `ai` e' una testa di tipo 3 con `kind` = `ai`, in una serie propria
  (§5.5); una build senza la Fase 6 non apre mai `ai/` e non vede altro che `index/` e `personal/` (E-I3, E-I22).
- `models/<sha256>` esiste **solo nell'area comune** (`devices/<id dispositivo>/models/`), e solo nella cartella del dispositivo che
  l'ha pubblicata: un file per modello, scritto una volta (scrittura esclusiva, nessuna sostituzione), mai nelle aree private.
  File grezzo senza estensione: eccezione dichiarata al §2.1.

**Fuori da `.mycloudgallery/` (Fase 5, N1).** Le cartelle che l'app crea nelle aree degli utenti. Sono file normali con la
loro estensione, **non firmati** e visibili dal PC (non fanno parte del formato: nessuna firma, nessuna versione):

```
<area privata di un account>/Backup/<dispositivo>/AAAA/MM/                foto e video della fotocamera, per data di scatto
<area privata di un account>/Backup/<dispositivo>/<Nome>/AAAA/MM/         screenshot e cartelle aggiuntive
<area privata di un account>/Backup/<dispositivo>/Senza data/             data di scatto assente o assurda (E-B17)
<area privata di un account>/Backup/<dispositivo>/Modificate/AAAA/MM/     modifiche di media di aree in sola lettura (5B, D35)
<area privata di un account>/Backup/Area comune/AAAA/MM/                  copie private di media dell'area comune (guardiano, D9)
<share>/Versioni/<percorso della cartella del file>/<nome> (originale).<ext>              versioni precedenti (5B, D17)
<share>/Versioni/<percorso della cartella del file>/<nome> (versione del AAAA-MM-GG HH.MM).<ext>
```

- Il **temporaneo del backup** sta in `.mycloudgallery/tmp/` della share di destinazione, con il nome
  `<id dispositivo>-bk-<id elemento>` e **nessuna estensione** (D33): cartella col punto e nome senza estensione lo
  tengono lontano dalla scansione, da DLNA e dalle viste normali del PC. Si pubblica con il nome definitivo solo dopo la
  rilettura completa (D4) e **senza sostituire** (`publishTemp`).
- `Backup/` sta nell'area privata dell'utente; `Versioni/` sta alla **radice di ogni share** e ne rispecchia i
  percorsi (D17, Q2): un solo posto da documentare e da escludere in Twonky e Plex.
- Il nome del dispositivo in `Backup/<dispositivo>/` e' scelto una volta (D7) e validato con `SharePath` (§8.1).
- **`MyCloud Gallery/Modelli/` (Fase 6, A1, F6-D17).** Cartella visibile dell'**area comune**, fuori da `.mycloudgallery/`:
  `<area comune>/MyCloud Gallery/Modelli/<nome del file>`. **Non fa parte del formato**: nessuna firma, nessuna versione, nessuna
  busta. La crea l'utente e vi copia a mano dal PC i file dei modelli (sorgente "dal PC" dello scaricamento); l'app la **legge** e
  non la scrive mai, e usa solo un file con il nome e la dimensione del catalogo, verificandone l'SHA-256 (come per ogni modello).

### 2.3 Come si scrive e come si legge (l'interfaccia `NasFileSystem`)

`core-trust` vede una share attraverso tre operazioni, e **solo tre**:

| Operazione | Contratto |
|---|---|
| `list(dir, limite)` | Nomi dei figli, al massimo `limite`, con l'indicazione "troncato". Cartella assente = vuota. L'ordine non e' garantito. |
| `read(path, maxBytes)` | Il file intero se non supera `maxBytes`, altrimenti "troppo grande". Mai allocare piu' di `maxBytes + 1` byte, qualunque dimensione dichiari il server. |
| `publishExclusive(path, bytes)` | Crea il file **solo se il nome non esiste** (anche con maiuscole diverse: Samba non le distingue per i client Windows); per chi legge e' atomico. Su SMB (Fase 2.1): temporaneo in `tmp/` aperto con `FILE_CREATE` (in Samba `O_CREAT\|O_EXCL`, atomico), scrittura, flush, verifica della lunghezza, `rename(path, replaceIfExist = false)`; collisione = "esiste gia'". Le cartelle mancanti si creano trattando "esiste gia'" come successo. |

Non ci sono cancellazione, spostamento o sovrascrittura (un test lo verifica, §9). Conseguenze:

- **Serie numerate, lette per nome.** I dati che cambiano (registro, teste, album) sono serie di file
  `<versione>` di 20 cifre con zeri a sinistra, da 1 a `MAX_VERSION` (10^9). Chi legge **non si fida
  dell'elenco della cartella**: legge direttamente `v+1`, `v+2`, … fino al primo nome assente (revisione M1:
  creare migliaia di nomi non nasconde piu' una versione). L'elenco serve solo come indizio per le versioni
  oltre un buco (una versione cancellata), e un elenco troncato e' segnalato.
- **Il numero lo sceglie chi pubblica: il primo nome libero.** Un nome gia' occupato da un file non valido
  (spazzatura messa da chi ha accesso SMB) si scavalca e si riporta in Diagnostica; quindi le versioni
  **possono avere buchi** (revisione A2: prima un nome occupato bloccava per sempre la pubblicazione di una
  revoca). Chi legge scavalca allo stesso modo i nomi non validi. Si scavalcano al massimo
  `MAX_JUNK_RUN` (32) nomi non validi di fila: oltre, chi legge si ferma per quel giro (limite al lavoro che
  uno scrittore ostile puo' imporre, B5) e chi pubblica riceve l'elenco esatto dei file da cancellare.
- **Due scrittori sullo stesso numero: vince il primo.** L'altro trova al suo posto una versione valida piu'
  nuova e deve risincronizzarsi (due admin che approvano insieme non si sovrascrivono). Prima di firmare al
  primo nome libero si leggono **per nome** i `MAX_JUNK_RUN` (32) nomi successivi e poi gli indizi dell'elenco
  **oltre** quel nome, con lo stesso tetto di 32 file non validi di fila (R4): una versione valida dopo un buco
  vuol dire che la base e' vecchia, e non si pubblica nel buco (verifica N2). Se l'elenco era troncato
  (inondazione) o gli indizi si sono fermati per la spazzatura, non si puo' escludere una versione nascosta
  oltre un buco piu' largo di 32 nomi: si pubblica (il ramo dell'admin riceve la modifica) ma **non in
  silenzio** (`CommitResult.Published.unverifiedBeyond`, avviso all'admin e in Diagnostica).
- **Contenuti per impronta.** I segmenti si chiamano con lo SHA-256 del loro contenuto: immutabili, senza
  conflitti.
- **Pulizia dei propri file vecchi**: sotto.

**Pulizia dei propri file (Fase 4, M6; `OwnFilesPolicy` in `core-trust`, accesa nella 4B).** L'interfaccia promessa
qui: una **politica** pura che decide, file per file, se un file e' **proprio** e se si puo' togliere; la cancellazione
la esegue solo `core-nasops` (§9, punto 7), mai `core-trust`.

| Che cosa | Si toglie quando | Mai |
|---|---|---|
| Teste proprie (`devices/<io>/<tipo>/head/<n>`) | superate: si tengono sempre le ultime `OWN_FILES_KEEP_HEADS` (3) | la piu' alta |
| Segmenti propri (`devices/<io>/<tipo>/seg/<impronta>`) | non elencati da nessuna delle ultime 3 teste **e** piu' vecchi di `OWN_FILES_GRACE_MILLIS` (7 giorni, ora del NAS) | un segmento elencato da una delle ultime 3 teste |
| Generazioni proprie di prenotazione (`leases/.../<n>`) | superate da una generazione piu' alta della stessa risorsa | la generazione piu' alta della risorsa, anche se propria |
| Teste, segmenti e prenotazioni propri dei volti (Fase 8, A8: `faces/<G>/<io>/head/<n>`, `.../seg/<impronta>`, `faces/<G>/leases/.../<n>`) | con le stesse regole delle tre righe sopra, **solo nella generazione corrente** (`FacesOwnFiles` in `core-trust`, eseguita da `core-nasops`); le generazioni non correnti si tolgono intere (§9, punto 6-bis) | la testa piu' alta |
| Temporanei propri (`tmp/<io>-*`) | piu' vecchi di un giorno (come `NasHousekeeping`) | quelli citati da **uno dei due** diari locali: operazioni (4B, `tmp/<io>-op-…`) e backup (Fase 5, `tmp/<io>-bk-…`; N2, D33) |
| Kit della chiave ruotata (§4.8) | dopo la rotazione confermata dall'admin | il kit della chiave corrente |

- **Un file e' proprio solo se lo dicono insieme la firma e il posto**: firmato da questo dispositivo (per i file
  firmati) **e** sotto i suoi id (`devices/<io>/`, `trash/<io>/`, `tmp/<io>-`). Per i segmenti, che non sono firmati,
  vale il posto piu' la presenza dell'impronta in una delle proprie teste passate (diario locale). Un file messo da
  altri nella mia cartella non e' mio: non lo tocco (E-S11).
- **Tetti per giro**: al massimo `MAX_OWN_FILES_PER_ROUND` (500) file; le stesse condizioni della pulizia del cestino
  (nessun congelamento, nessun salto dell'ora, §6.1).
- **Generazioni condivise** (nota della revisione 1.4, riga 109): la generazione piu' alta di una risorsa non si toglie
  mai, nemmeno se e' mia, cosi' chi legge trova sempre l'ultimo stato e il numero successivo non si ripete.
- Una testa di dati personali (`personal`) e i suoi segmenti seguono le stesse regole nella propria area privata.
- **Temporanei del backup (Fase 5, N2, D33).** Un temporaneo proprio si toglie **solo se nessuno dei due diari** (operazioni
  e backup) lo cita. Un video a meta' fermo da piu' di un giorno (telefono fuori casa) e' citato dal diario del backup con il
  suo punto di ripresa: resta, anche oltre il giorno (E-B21). `OwnFilesCleaner` riceve i temporanei citati da entrambi i diari
  da una porta (`CitedTemps`) che compone `:app`. La pulizia di `core-nas` (`SmbNasHousekeeping`) riconosce solo i suoi nomi
  da 32 cifre esadecimali e non tocca i `-bk-`.

### 2.4 Nomi

| Nome | Forma | Validazione in lettura |
|---|---|---|
| id (dispositivo, famiglia, elemento del cestino, album) | 128 bit in base32 (§3.1): 26 caratteri `[0-9a-z]` | decodifica stretta di 16 byte |
| id di un dispositivo | primi 128 bit di SHA-256(chiave pubblica): **auto-certificante** | deve coincidere con la chiave |
| versione / generazione / gruppo | 20 cifre ASCII, valore tra 1 e 10^9 | esattamente 20 cifre, entro il tetto |
| impronta | SHA-256 in base32: 52 caratteri | decodifica stretta di 32 byte |
| impronta di una chiave (kit) | 26 caratteri base32 (§4.1) | uguale all'impronta attesa |

Ogni nome letto da un elenco che non rispetta la sua forma si **ignora** (e si conta in Diagnostica): un
attaccante puo' riempire una cartella di nomi strani, ma non farli interpretare.

---

## 3. Codifiche

### 3.1 Base32

L'unica codifica binario-testo del formato: Base32 di Crockford, **minuscolo, senza riempimento**, alfabeto
`0123456789abcdefghjkmnpqrstvwxyz` (senza `i l o u`). La decodifica e' **stretta**: lunghezza esatta per il
numero di byte e bit di coda a zero, cosi' ogni valore ha una sola scrittura. Solo per cio' che una persona
digita (codice di recupero, codice di approvazione, impronta) si accettano maiuscole, spazi, trattini e gli
alias `O→0`, `I/L→1`, e poi si passa comunque dalla decodifica stretta.

### 3.2 JSON canonico

Il contenuto dei file firmati e' un sottoinsieme di JSON con **una sola forma possibile** per ogni valore:

- UTF-8 valido, senza BOM; **nessuno spazio** fuori dalle stringhe; niente dopo la fine;
- tipi: oggetto, array, stringa, intero a 64 bit, booleano. **Niente `null`**, niente numeri con virgola o
  esponente, niente `-0`, niente zeri iniziali;
- chiavi: lettera ASCII seguita da lettere o cifre ASCII (max 32 caratteri), **in ordine crescente**,
  **mai ripetute**;
- stringhe: si scrivono con escape solo `"`, la barra rovesciata e i caratteri di controllo (le forme corte
  per backspace, form feed, a capo, ritorno, tabulazione; la forma a sei caratteri con `00` e due cifre
  esadecimali minuscole per gli altri sotto U+0020); tutto il resto letterale; un surrogato spaiato e' un
  errore.

Chi verifica: (1) controlla la firma sui **byte**; (2) legge con il parser stretto e con i tetti (§7);
(3) **riserializza e pretende gli stessi byte**. Cosi' due programmi (o due versioni dell'app) non possono
mai leggere lo stesso file firmato in due modi, e un dispositivo compromesso non puo' sfruttare differenze
tra parser.

I campi di ogni oggetto sono fissati dal tipo (§5): **un campo sconosciuto e' un errore**, non si ignora.
Una novita' nel formato aumenta la versione (§10).

Il parser e' scritto nel modulo (`CanonicalJson`) invece di usare `kotlinx.serialization`: serve il
controllo di profondita', duplicati, numeri e forma canonica, e nessuna libreria generica lo garantisce.

---

## 4. Modello crittografico

### 4.1 Algoritmi (nessuna negoziazione)

| Uso | Algoritmo | Perche' |
|---|---|---|
| Firme | **ECDSA P-256 con SHA-256**, firma DER **in forma canonica** (sotto) | Keystore e StrongBox lo supportano su tutti i dispositivi da Android 6; minSdk e' 29. **Ed25519** nel Keystore arriva solo da Android 13 (API 33) e StrongBox non lo supporta: con Ed25519 le chiavi dei dispositivi Android 10-12 starebbero fuori dall'hardware. |
| Cifratura (kit, cassaforte) | **AES-256-GCM**, tag di 128 bit | Standard, autenticata, nel Keystore e in JCA. |
| Derivazione dal codice di recupero | **HKDF-SHA256** | Il codice ha 128 bit casuali: una derivazione lenta (PBKDF2, scrypt) non aggiungerebbe nulla. |
| Impronte | **SHA-256** | – |

Tutto con JCA e il Keystore di Android: **nessuna libreria crittografica aggiunta**. Un algoritmo diverso
vorrebbe dire una nuova versione del formato, mai un campo "algoritmo" da negoziare.

**Forma canonica delle firme** (revisione A3). ECDSA ammette due firme valide per lo stesso messaggio,
`(r, s)` e `(r, n − s)`, e la seconda si ottiene dalla prima **senza chiave**. Per questo:

- una firma e' accettata solo come `SEQUENCE { INTEGER r, INTEGER s }` in DER minimo (lunghezze corte, interi
  positivi senza zeri superflui), con `1 ≤ r < n` e `1 ≤ s ≤ n/2` ("s basso"); ogni altra forma e' una firma
  non valida (`EcdsaDer`);
- chi firma normalizza sempre (il Keystore non garantisce `s` basso: il test sull'S25 lo verifica);
- in piu', l'**identita' di un file firmato non dipende dai byte della firma** (§5.1): anche se un giorno una
  seconda firma valida passasse, sarebbe lo stesso dato e non un'"equivocazione".

**Chiavi pubbliche.** Si accettano solo come SubjectPublicKeyInfo P-256 con punto non compresso: 91 byte, i
primi 27 fissi, e con un punto della curva (coordinate minori di p e `y² = x³ − 3x + b mod p`, controllato
da noi e non dal provider: JVM dei test e Conscrypt del telefono decidono allo stesso modo, e ogni chiave ha
una sola codifica, B3).

L'**impronta** di una chiave (da mostrare, da mettere nel QR, da confrontare) sono i primi 26 caratteri
base32 di SHA-256(chiave): 130 bit. Dove la chiave intera e' nota (registro gia' accettato) si confronta la
chiave intera, non l'impronta (I7).

### 4.2 La chiave di famiglia

- Generata dall'app dell'admin all'onboarding (piano 2.2, passo 12), in software (JCA), e **subito** cifrata
  in due posti: la *cassaforte* sul dispositivo admin e il **kit di recupero** (§4.8).
- La **cassaforte**: AES-256-GCM con una chiave del Keystore, StrongBox se c'e', non estraibile e
  **utilizzabile solo a telefono sbloccato** (`setUnlockedDeviceRequired`, revisione M3: la chiave di famiglia
  serve solo in operazioni interattive, quindi un telefono rubato acceso e bloccato, o uno strumento forense,
  non la decifra). "Solo sbloccato" non vale nulla su un telefono senza blocco schermo sicuro: la cassaforte
  **rifiuta di lavorare** se il dispositivo non ha PIN, sequenza o password (`isDeviceSecure`, verifica M3 e
  domanda 9), quindi **il dispositivo admin deve avere un blocco schermo sicuro** (l'onboarding lo chiede
  prima di creare la chiave di famiglia; per gli altri dispositivi resta l'avviso di E-S3). Il file sta nello
  spazio privato dell'app, escluso da backup e trasferimenti. Chiedere in piu' l'impronta o il PIN a ogni uso
  e' la decisione 2 del §13: protegge anche dal malware che agisce a telefono sbloccato.
- E' **estraibile per costruzione**: deve poter stare nel kit e su un secondo dispositivo admin. In chiaro
  esiste solo in memoria, durante un'operazione dell'admin.
- Firma **solo** il registro di famiglia. Non firma dati dell'indice: per quelli il dispositivo admin usa la
  propria chiave di dispositivo, come tutti.
- La sua impronta e' la radice di fiducia che il QR porta ai dispositivi nuovi (§4.7).

### 4.3 Le chiavi dei dispositivi

- Una per dispositivo, e una per **ogni profilo** Android del tablet (ogni profilo ha il suo Keystore).
- Generata nel Keystore (`PURPOSE_SIGN`, P-256, SHA-256), **StrongBox se c'e'**, altrimenti TEE. Il test
  sull'S25 verifica che la chiave privata non sia esportabile e che, se il telefono ha StrongBox, stia li'.
- **Utilizzabile senza sblocco**: i lavori notturni (indice, prenotazioni) firmano a telefono bloccato, come
  per le credenziali del NAS (`sicurezza.md` §8). Prezzo dichiarato in §12. Il test sul telefono verifica
  questa politica (e quella opposta della cassaforte).
- **L'id del dispositivo e' derivato dalla chiave** (§2.4): non si puo' rivendicare l'id di un altro senza la
  sua chiave, e un dispositivo reinstallato o ritrovato ha una chiave nuova, quindi un id nuovo (E-O7, E-O8,
  E-S2). Non esiste "riapprovare" una chiave revocata.
- Distruggibile (`destroyDeviceKey`) quando il dispositivo si scopre segnato come perso (Fase 2.4).

### 4.4 Il registro di famiglia

Un unico documento firmato dalla chiave di famiglia con **configurazione + dispositivi** e un contatore
`version` che cresce a ogni modifica (un solo contatore: niente incoerenze tra "configurazione N" e
"registro M"). Si pubblica in `family/registry/<version>` al primo nome libero (§2.3).

**Accettazione** (`RegistryVerifier.accept`), in quest'ordine:

1. busta integra (§5.1) e di tipo registro, firma in forma canonica;
2. se il contenuto firmato e' identico a quello gia' accettato, e' la stessa versione (nessun errore);
3. la chiave che firma e' **quella attesa**: la chiave intera del registro gia' accettato (o il successore
   annunciato, §4.10), oppure, per un dispositivo nuovo, quella con l'impronta dell'ancora (§4.7);
4. contenuto canonico e ben formato (§5.3), famiglia giusta;
5. **anti-rollback**: versione piu' bassa dell'ultima accettata → `ROLLBACK`; stessa versione con contenuto
   firmato diverso → `EQUIVOCATION`; dispositivo nuovo: versione almeno quella del QR (`minRegistryVersion`);
6. **anti-regressione** (revisione A1): rispetto al registro gia' fidato, il nuovo puo' solo diventare **piu'
   severo** verso i dispositivi che quello conosce. Ogni dispositivo noto resta presente, con la stessa
   chiave e lo stesso account del NAS, con uno stato uguale o piu' severo (§4.6); un dispositivo che resta
   dismesso puo' solo perdere teste congelate; l'ora di emissione non torna indietro. Possono cambiare la
   configurazione, i nomi, il segno di admin, e possono arrivare dispositivi nuovi. Altrimenti `REGRESSION`.
   **Unica eccezione** (verifica della revisione, R3): l'account di un dispositivo puo' cambiare solo se diventa
   (o e') revocato o perso, perche' di un dispositivo cosi' nessun dato vale e l'account non da' potere; senza
   l'eccezione un dispositivo approvato in due rami con due account non si poteva piu' revocare. La regola e'
   transitiva, quindi vale anche quando si saltano versioni;
7. il nome del file deve coincidere con la versione dichiarata.

Senza il punto 6 bastava che un admin lavorasse su una base vecchia (il secondo dispositivo admin rimasto
indietro, o un admin appena recuperato, e chiunque con accesso SMB poteva cancellare la versione con la
revoca) perche' la versione successiva **riapprovasse in silenzio** un dispositivo revocato. Ora quella
versione e' rifiutata da tutti i dispositivi che hanno visto la revoca, con un allarme.

`EQUIVOCATION` e `REGRESSION` di un registro **firmato dalla famiglia** producono un **ramo**
(`RegistryFork`): allarme in Diagnostica (`tamperingSuspected`) e, all'admin, l'elenco dei dispositivi che
compaiono solo li' e l'avviso se il ramo ha una configurazione diversa (`configDiffers`, domanda 10).

**Un registro "contiene" un ramo** (`dominates`) se nessun dispositivo del ramo manca o e' meno severo (stessa
chiave, stesso account, stato uguale o piu' severo, teste congelate non in piu'), e il ramo non contiene una
rotazione. Un registro che contiene un ramo e' accettato anche dai dispositivi rimasti su quel ramo. E' la
regola che rende sempre possibile **uscire** da qualunque numero di rami, di qualunque lunghezza (verifica
N1: prima un ramo di due o piu' versioni bloccava ogni pubblicazione, revoche comprese). L'admin, sulla
**stessa modifica** che voleva pubblicare (per esempio una revoca):

- se riconosce i rami (tipicamente il secondo admin rimasto indietro), li **unisce tutti**
  (`FamilyAuthority.mergeForks`): ogni dispositivo con lo stato piu' severo, le teste congelate in comune, la
  configurazione dell'admin (se il ramo ne aveva un'altra, la schermata lo mostra e l'admin sceglie);
- se non li riconosce (la chiave di famiglia l'ha usata qualcun altro), li **rifiuta**
  (`FamilyAuthority.rejectForks`): come l'unione, ma i dispositivi che esistono solo nei rami entrano
  **revocati**; poi **reset della famiglia** (§4.10).

In entrambi i casi la versione pubblicata contiene i rami, quindi la accettano tutti i dispositivi, la
modifica dell'admin esce e nessuna revoca va persa. Nessuna unione avviene da sola.

Un dispositivo che nei due rami ha **account diversi** esce dall'unione (o dal rifiuto) almeno revocato, con
l'account del ramo dell'admin e senza segno di admin (R3). Un ramo che contiene un **annuncio di rotazione**
non si puo' "contenere": i dispositivi che l'hanno visto si fidano ormai di un'altra chiave e vanno comunque
riconfigurati (reset). Unione e rifiuto ne prendono solo gli stati piu' severi dei dispositivi gia' noti (quelli
che esistono solo li' entrano revocati) e la pubblicazione lo scavalca (`RegistryDraft.acknowledgedForks`): cosi'
almeno il ramo dell'admin riceve la sua modifica, per esempio una revoca (R2; prima l'admin restava bloccato fino
al reset). I rami che lo stato
finale di un dispositivo contiene gia' sono **storia**: chi risale le versioni li attraversa senza allarme e
senza contarli come spazzatura (N5).

**Lettura dal NAS** (`RegistrySync`): per nome dalla versione successiva a quella nota (§2.3), poi gli indizi
dell'elenco, e infine il controllo della **versione fidata**: se il suo file e' sparito dal NAS lo si
**ripubblica** (`healed`), cosi' cancellare la versione con una revoca non basta a nasconderla a chi non l'ha
ancora vista; se al suo posto c'e' un altro contenuto firmato dalla famiglia, e' un'equivocazione (allarme e
ramo); se al suo posto c'e' un file non valido, e' una **sostituzione** (`trustedVersionReplaced`, allarme):
sostituirla con spazzatura ne ritarda l'arrivo a chi non l'ha ancora vista fino alla ripubblicazione
dell'admin, che l'allarme fa partire (`FamilyAuthority.reissue`: lo stesso contenuto con un numero nuovo;
verifica N3). **Lo stato locale non e' mai sostituito da qualcosa di piu' vecchio, non verificato o che torna
indietro**: nel caso peggiore si continua con l'ultimo registro accettato e la Diagnostica lo dice
(`newerUnverified`, `listingTruncated`, `incomplete`, `needsAppUpdate`, `tamperingSuspected`).

**Pubblicazione** (`FamilyAuthority.commit`): l'admin prepara una modifica sulla base del registro fidato,
poi controlla il nome della base (un altro contenuto firmato li' e' un ramo) e legge per nome `v+1`, `v+2`,
…: versione valida piu' nuova → `NewerExists` (risincronizzarsi e rifare la modifica: un admin non firma mai
su una base che sa vecchia); registro di un'app piu' nuova → `NeedsAppUpdate`; ramo che la modifica contiene
gia' → si scavalca; spazzatura → si scavalca e si riporta; troppa spazzatura di fila → `Blocked` con
l'elenco esatto dei file da cancellare. Al primo nome libero legge per nome i 32 nomi successivi e poi gli
indizi dell'elenco oltre quel nome, con il tetto alla spazzatura (N2, R4); se non ha potuto controllare tutto lo
dice (`unverifiedBeyond`). Se restano rami che la modifica non contiene (e che l'admin non ha gia' trattato, R2)
→ `Fork` con **tutti** i rami trovati; altrimenti firma con l'ora di emissione non inferiore a quella di ogni
ramo scavalcato e pubblica.

**Memoria locale**: ogni dispositivo salva nello spazio privato i byte dell'ultimo registro accettato e
l'impronta della chiave che lo ha firmato; al riavvio li riverifica (`RegistryVerifier.restore`).

**Le voci non si tolgono mai**: un dispositivo revocato, perso o dismesso resta nel registro (la regola del
punto 6 lo richiede, e "sconosciuto" non deve mai poter tornare "approvato"). Il tetto e' 256 dispositivi.

**Configurazione** (estesa nella Fase 2, §5.3): IP letterale del NAS (`nasHost`), nome, GUID, prefissi ULA di
casa, alimentazione, MAC per il WoL, area comune, esclusioni, preset e qualita' di default. Indirizzo del NAS e
identita' (E-R7, revisione M5): una firma su un file copiabile **non prova chi lo sta servendo**, quindi "il
NAS a quell'indirizzo serve un registro firmato" non e' un criterio. L'identita' del server sta nella sessione
SMB 3.1.1 con firma obbligatoria (la chiave di sessione dimostra che il server conosce la password
dell'account) su una rete riconosciuta o in VPN (C2); un indirizzo nuovo si accetta solo se arriva da una
configurazione firmata dall'admin, oppure con la conferma esplicita dell'utente; mai dalla sola scoperta in
rete.

### 4.5 Registrazione e approvazione (piano 2.3)

1. Il dispositivo nuovo crea la sua chiave nel Keystore e pubblica `family/requests/<suo id>`: una
   **richiesta firmata da se stessa** (famiglia, id, chiave, nome, modello, account del NAS, ora). La firma
   prova solo che chi la scrive possiede quella chiave.
2. L'admin vede le richieste valide (firma giusta, id = chiave, nome del file = id, famiglia giusta; le
   altre si scartano e si contano). Se la cartella e' stata riempita di nomi, la richiesta si legge **per id**
   (`PendingRequests.readOne`), con l'id mostrato dal dispositivo nuovo.
3. **Prova di presenza** (revisione M2). L'admin approva solo con una di queste (`ApprovalProof`):
   - **scansione** del QR mostrato dal dispositivo nuovo con la sua chiave intera (via consigliata quando si
     e' nella stessa stanza);
   - **codice digitato**: 16 caratteri (`ABCD-EFGH-JKMN-PQRS`, primi 80 bit di SHA-256 della chiave,
     `ApprovalCodes`) che l'admin **digita** leggendoli sullo schermo del dispositivo nuovo; non si
     "confrontano a vista", perche' a vista si controllano pochi caratteri. Requisito per la schermata della
     Fase 2.3 (verifica N6): `ApprovalProof.TypedCode` si costruisce **solo** dal testo digitato; il campo
     parte vuoto, non si precompila ne' si incolla dalla richiesta, e un test dell'interfaccia lo verifica.

   La chiave del richiedente e' leggibile nell'area comune prima dell'approvazione, quindi chi vuole farsi
   approvare al posto del dispositivo vero puo' cercare con calma una chiave con lo stesso codice: con 50 bit
   (prima versione) bastavano giorni di GPU, con 80 bit servono circa 10^24 tentativi.
4. **Finestra di 15 minuti**: l'app dell'admin annota quando ha visto la richiesta per la prima volta (ora del
   NAS, in locale: non `requestedAt`, che scrive il richiedente) e oltre 15 minuti non la approva; il
   dispositivo nuovo rifa' la richiesta con una chiave nuova (`ApprovalWindow`).
5. **Sosia**: le richieste il cui nome coincide, dopo NFKC, rimozione di ZWJ e selettori, minuscole e spazi,
   con un'altra richiesta o con un dispositivo gia' registrato sono segnalate all'admin (`LookAlike`).
6. **Account del NAS**: e' dichiarato dal richiedente (revisione B6). L'admin lo vede e lo conferma. Il primo
   potere legato all'account, la segnalazione "perso" del proprietario (§5.9), chiede la **prova di possesso**:
   una copia firmata nell'area privata di quell'account, dove scrive solo chi ne ha la password. La stessa prova
   servira' all'unione dei dati personali (Fase 4.7).
7. L'admin firma la versione successiva del registro con il dispositivo `approved`.

Fino all'approvazione il dispositivo legge soltanto: i suoi dati, se ne scrivesse, sono rifiutati da tutti
(`SIGNER_NOT_APPROVED`) e l'app non li scrive (`RegistryState.canWrite`).

### 4.6 Stati, revoca e destino dei dati

| Stato | Si entra da | Dati del dispositivo accettati dagli altri |
|---|---|---|
| `approved` | approvazione | tutti (con i controlli del loro tipo) |
| `retired` (dismesso) | `approved` | **solo le teste congelate** elencate dall'admin (`frozenHeads`): impronte dei contenuti firmati delle teste che l'admin aveva gia' verificato. Nient'altro, nemmeno teste piu' nuove. |
| `revoked` (revocato) | `approved`, `retired` | **nessuno**, qualunque data dichiarino |
| `lost` (perso) | `approved`, `retired`, `revoked` | nessuno; in piu' il dispositivo stesso, quando legge il registro, distrugge la chiave e poi i dati locali (Fase 2.4) |

Gli stati sono in ordine di severita' e vanno solo avanti (`DeviceStatus.canBecome`), sia per chi firma sia
per chi verifica (§4.4 punto 6); non si torna mai ad `approved`.

**Perche' "nessuno" e non "quelli firmati prima della revoca":** i dati non hanno un'ora certificata; un
dispositivo compromesso puo' scrivere qualunque data. Distinguere "prima" e "dopo" sarebbe una promessa
falsa. Il costo e' accettabile perche' quasi tutto cio' che firma un dispositivo e' **ricalcolabile**:

| Dato del dispositivo revocato | Che cosa succede |
|---|---|
| Teste e segmenti dell'indice | ignorati; il lavoro torna da fare e lo rifanno gli altri indicizzatori/preparatori (le prenotazioni di quel dispositivo non contano piu'). |
| Dati personali | ignorati i suoi contributi; restano quelli degli altri dispositivi dello stesso account (Fase 4.7 decide se ognuno ripubblica lo stato gia' unito). |
| Prenotazioni | ignorate: la risorsa e' libera. |
| Record del cestino | ignorati: i file restano nel cestino e **non vengono mai eliminati in automatico** (la pulizia la fa solo chi ha cestinato, §9); in "Eliminati di recente" compaiono come "senza record valido" e decide l'utente. |
| Album condivisi (Fase 7, A7) | **dismesso**: valgono solo i contributi delle sue teste congelate (le voci gia' viste restano, nessuna nuova). **Revocato o perso**: il suo contributo e' ignorato del tutto, anche le aggiunte fatte da li' (un elemento che solo lui aveva pubblicato esce dall'album per tutti); i file restano dove sono. Un altro dispositivo dello stesso account puo' ripubblicare gli elementi i cui file sono ancora nell'area comune ("Ripristina gli elementi di un dispositivo revocato", azione locale, E-SH20). L'account del creatore resta creatore finche' ha un dispositivo approvato o dismesso (D11). P2: i contributi esclusi restano solo come memoria locale (mai nella fusione ne' in una schermata) per quei ripristini e per l'erede, che ricostituisce l'album di un creatore uscito dalla famiglia con lo stesso id (§5.8). |
| Dati dei volti (Fase 8, A8) | **dismesso**: valgono solo le sue teste dei volti congelate. **Revocato o perso**: ignorati, e la sua cartella `faces/<G>/<id>/` si toglie dall'area privata dell'account (§9, punto 6-bis, D31): i rilevamenti sono ricalcolabili e i nomi e le correzioni restano nello stato unito delle voci `people`, che ogni dispositivo dell'account ripubblica. |

**Dismissione** (telefono sostituito, funzionava): l'admin la sceglie quando il dispositivo non e'
sospetto; congela le teste che il suo dispositivo ha gia' accettato, cosi' l'indice gia' fatto non si
ricalcola. L'impronta congelata e' quella del contenuto firmato, non dei byte del file: una copia della stessa
testa con un'altra firma valida resta valida (A3).

### 4.7 L'ancora di fiducia di un dispositivo nuovo

Il QR dell'app (piano 2.3) porta, oltre a indirizzi, utente, aree e preset (mai password), l'**ancora**
(`RegistryState.toAnchor`):

- l'**id della famiglia**;
- l'**impronta della chiave che ha firmato la versione corrente** del registro;
- la **versione minima del registro** (quella corrente quando il QR viene mostrato).

La rotazione si pubblica **completa** (`FamilyAuthority.rotate`): l'annuncio firmato dalla chiave vecchia e
subito una versione firmata dalla nuova, cosi' il QR mostra gia' la chiave nuova (B4). Finche' la versione
corrente e' solo l'annuncio, **non c'e' QR** (`toAnchor` restituisce null, verifica N4): un QR li' ancorerebbe
i dispositivi nuovi alla chiave vecchia, forse sospetta. Lo stesso QR ("stato della famiglia") lo puo' mostrare **qualunque dispositivo approvato**: e'
l'ancora del recupero dell'admin (§4.8).

Senza QR, la procedura manuale chiede l'id della famiglia e l'impronta, che l'utente **digita per intero
leggendole dallo schermo dell'admin**: non si precompilano mai leggendole dal NAS (premere "Conferma" su un
valore letto dal NAS non verifica nulla, revisione B9). La versione minima vale 0: e' accettato qualunque
registro firmato da quella chiave, anche uno vecchio, e la Diagnostica lo segnala finche' il dispositivo non
vede la versione corrente.

Un QR di un'altra famiglia o con un'impronta diversa da quella che firma il registro sul NAS non porta a
nulla (E-O5); una configurazione cambiata dopo il QR vale quella firmata sul NAS (E-O6).

### 4.8 Il kit di recupero (proposta 15)

Due pezzi, **servono entrambi**:

1. **Il foglio**: il **codice di recupero**, 128 bit casuali generati dall'app (mai scelti dall'utente) piu'
   10 bit di controllo che scoprono gli errori di battitura (ne lascia passare circa uno su mille) prima di
   tentare la decifratura, stampato come 7 gruppi di 4 caratteri (`ABCD-EFGH-JKMN-PQRS-TVWX-YZ01-2345`);
   e, **non segreti, l'id della famiglia e l'impronta della chiave di famiglia**, che al recupero fanno da
   ancora indipendente dal NAS. Si mostra solo nella schermata protetta dell'onboarding e l'utente riscrive
   il codice per verificarlo (2.2).
2. **Il file cifrato** `recovery/<id famiglia>/<impronta della chiave>`, nell'**area privata dell'admin** sul
   NAS (gli altri familiari non lo leggono). Un file per chiave: dopo una rotazione il kit nuovo ha un posto
   suo (revisione M4).

```
0    4   "MCGK"
4    1   versione del formato
5    16  id della famiglia
21   32  SHA-256 della chiave pubblica di famiglia
53   8   ora di creazione (ora del NAS, ms, big endian)
61   16  sale
77   12  nonce
89   ... AES-256-GCM( chiave = HKDF-SHA256(codice, sale, "mycloudgallery/recovery-kit/v1" || id famiglia),
                      dati aggiuntivi autenticati = byte 0..88,
                      testo = lunghezza + chiave pubblica + lunghezza + PKCS#8 della privata )
```

Aprire il kit (`RecoveryKit.open`) **richiede l'id della famiglia e l'impronta attesi** (dal foglio, o dal QR
di stato): un kit con un'altra chiave, messo nell'area dell'admin da chi conosce il codice, e' rifiutato
prima ancora di decifrare (revisione M7). Poi controlla struttura, versione, autenticazione GCM (codice
sbagliato o file alterato danno lo stesso errore), che la chiave dentro sia quella dichiarata e che privata e
pubblica stiano insieme (firma di prova). Tetto: 1 KiB. L'intestazione in chiaro (id, impronta, ora) si legge
senza codice con `RecoveryKit.inspect`: il recupero elenca solo i kit la cui intestazione coincide con il nome
del file e della cartella.

**Recupero dell'admin** (E-S13, piano 2.4), senza nulla del vecchio telefono:

1. sul dispositivo nuovo si configura l'account dell'admin e si apre il kit con codice, id e impronta del
   foglio: torna la **stessa** chiave di famiglia;
2. l'ancora del registro e' il **QR di stato** mostrato da un dispositivo approvato qualunque (versione
   corrente): cosi' chi ha cancellato le versioni recenti non fa partire l'admin recuperato da un registro
   vecchio. Se nessun dispositivo e' a portata, si parte con versione minima 0 e la Diagnostica avverte
   finche' non si vede un dispositivo aggiornato;
3. si sincronizza il registro dal NAS e si controlla che la chiave aperta dal kit sia **quella che firma** il
   registro sincronizzato (`RegistryState.acceptsRecoveredKey`): se no, il QR di stato veniva da un
   dispositivo che ancora la fiducia a un'altra chiave, e ci si ferma subito (nota della verifica su M7);
4. si approva il dispositivo nuovo (`admin = true`) con la scansione della sua chiave; il vecchio si segna
   come perso o revocato. Le impronte nei QR gia' distribuiti restano valide.

### 4.9 Il secondo dispositivo admin

E' un dispositivo che possiede la chiave di famiglia: la ottiene **aprendo il kit** (stesso foglio, stesso
file) ed e' segnato `admin = true` nel registro (informativo: il potere vero e' la chiave). Il caso previsto
e' un profilo dell'admin sul tablet, con lo stesso account del NAS (quindi legge il file del kit). Due admin
non si sovrascrivono (§2.3) e, se uno dei due lavora su una base vecchia, la sua versione non annulla le
revoche dell'altro: diventa un ramo da unire (§4.4).

### 4.10 Rotazione e reset

- **Rotazione** (manutenzione, o il foglio del codice forse visto da qualcuno, ma il file nell'area privata
  al sicuro): la versione pubblicata dalla rotazione, firmata dalla chiave attuale, contiene `successorKey`;
  da li' in poi firma solo la nuova; la rotazione si pubblica completa, con una versione firmata dalla nuova
  subito dopo l'annuncio (`FamilyAuthority.rotate`, §4.7). Ogni dispositivo che accetta l'annuncio smette per
  sempre di accettare la vecchia chiave. Subito dopo si genera e si stampa un kit nuovo, **con un codice
  nuovo**, che si pubblica nel suo posto (`recovery/<famiglia>/<impronta nuova>`); il kit vecchio va
  **cancellato** dall'area privata dell'admin: la chiave vecchia vale ancora per i dispositivi che non hanno
  visto l'annuncio. `RecoveryKitFiles.obsolete` elenca i percorsi esatti dei kit da cancellare; finche' la
  Fase 2.4 non porta la cancellazione come azione locale confermata, l'app li mostra e la guida chiede di
  cancellarli a mano dal PC, e la rotazione non risulta conclusa finche' l'elenco non e' vuoto (verifica M4). Un QR mostrato subito dopo la
  rotazione funziona (§4.7). Se qualcuno cancella l'annuncio dal NAS, chi l'aveva visto lo ripubblica (§4.4).
  La rotazione **non** protegge da una chiave gia' rubata: il ladro potrebbe annunciare un suo successore.
- **Reset della famiglia** (chiave di famiglia persa senza kit, o rubata, o un ramo firmato che l'admin non
  riconosce): nuova chiave, **nuovo id di famiglia**, nuovo kit; ogni dispositivo deve essere riconfigurato
  con il nuovo QR (o l'impronta digitata a mano) e riapprovato. La fondazione della famiglia nuova sta nella
  **stessa cartella** del registro (`FamilyAuthority.refound`): al primo nome libero dopo l'ultima versione
  nota della famiglia vecchia, seguito da 32 nomi liberi. Il QR nuovo porta quel numero come versione minima,
  quindi i dispositivi nuovi partono da li'; le versioni della famiglia vecchia, firmate da un'altra chiave,
  per loro sono spazzatura (e viceversa). I dati legati al vecchio id di famiglia non
  valgono piu': ogni dispositivo, riapprovato, ripubblica le proprie teste col nuovo id (i segmenti,
  identificati per impronta, restano gli stessi e non si ricalcolano). Un dispositivo non ancora migrato
  continua a fidarsi della vecchia famiglia: la guida chiede di migrarli subito e, se la chiave e' stata
  rubata, di cambiare le password del NAS.

---

## 5. I file firmati

### 5.1 La busta

Ogni file firmato ha la stessa struttura binaria (`Envelope`):

```
offset  byte  contenuto
0       4     "MCGS"
4       1     versione del formato (1)
5       1     tipo (sotto)
6       91    chiave pubblica di chi firma (SPKI P-256)
97      4     lunghezza N del contenuto (big endian), 1 ≤ N ≤ tetto del tipo
101     N     contenuto: JSON canonico (§3.2)
101+N   1     lunghezza S della firma, 8 ≤ S ≤ 72
102+N   S     firma ECDSA P-256 / SHA-256 in DER, forma canonica (§4.1)
```

Si firma `"mycloudgallery/signed" + byte 0x00 + byte[0, 101+N)`: tipo, versione e chiave sono dentro la
firma (un file di un tipo non si spaccia per un altro). Il file finisce esattamente dopo la firma.

**Identita' di un file firmato** (`contentHash`): SHA-256 dei soli **byte firmati** `byte[0, 101+N)`, senza
la firma. E' l'identita' usata per le versioni del registro, i segni di anti-rollback delle teste
(`HeadMark`), le teste congelate e le equivocazioni: "stessa versione, **contenuto firmato** diverso".

| Tipo | Codice | Firmato da | Tetto del contenuto |
|---|---|---|---|
| Registro di famiglia | 1 | chiave di famiglia | 256 KiB |
| Richiesta di registrazione | 2 | il dispositivo stesso | 4 KiB |
| Testa di un dispositivo | 3 | dispositivo approvato | 1 MiB |
| Prenotazione | 4 | dispositivo approvato | 2 KiB |
| Record del cestino | 5 | dispositivo approvato | 8 KiB |
| Testa di un album condiviso | 6 | dispositivo approvato | 256 KiB |
| Segnalazione "perso" | 7 | dispositivo approvato dello stesso account | 2 KiB |
| Stato del dispositivo | 8 | il dispositivo stesso, approvato | 2 KiB |

**Ordine della lettura** (verifica prima di interpretare): dimensione del file entro il tetto del tipo →
copia privata dei byte (chi chiama non puo' cambiarli tra verifica e lettura, I7) → firma del formato →
versione (piu' nuova = "aggiorna l'app") → tipo atteso → forma della chiave e punto sulla curva → lunghezze
coerenti, nessun byte in piu' → firma valida e canonica → **giudizio sul firmatario** (§5.2) → solo ora il
JSON, con i suoi tetti e la forma canonica → campi del tipo → coerenza con il posto in cui il file si trova.
Nessuna allocazione dipende da lunghezze dichiarate dal file.

### 5.2 La regola unica per i dati dei dispositivi

`DeviceTrust`: la chiave che firma deve essere nel registro accettato; `approved` → si prosegue; `retired` →
solo una testa il cui `contentHash` e' in `frozenHeads`; `revoked`/`lost` → rifiuto; assente (sconosciuta o
in attesa) → rifiuto. Poi ogni contenuto deve dichiarare la famiglia giusta e **lo stesso id del firmatario**:
un dispositivo non puo' firmare dati "a nome" di un altro.

### 5.3 Registro di famiglia (tipo 1)

```json
{"config":{"commonExclusions":["Film","Musica/Copertine"],"commonShare":"test-comune",
           "defaults":{"preset":"balanced","quality":"standard"},"homeUla":["fd12:3456:789a::/48"],
           "nasGuid":"0f8fad5b-d9cb-469f-a165-70867728950e","nasHost":"192.168.178.58",
           "nasName":"MyCloudEX2Ultra.fritz.box","nasPower":"always"},
 "devices":[{"admin":true,"id":"…","key":"…","model":"SM-S931B","name":"Telefono di …",
             "nasUser":"…","since":1791806400000,"status":"approved"}],
 "family":"…","issuedAt":1791806400000,"version":1}
```

(Mostrato su piu' righe per leggerlo; il file e' su una riga sola, senza spazi.) Campi facoltativi:
`successorKey` (rotazione) e, per ogni dispositivo `retired`, `frozenHeads` (impronte in ordine crescente,
non vuoto). Vincoli: al massimo 256 dispositivi, in ordine crescente di id, senza doppioni; `id` = id della
`key`; `version` tra 1 e 10^9; testi da mostrare (§8.4); `nasUser` ASCII stretto.

**Configurazione** (`FamilyConfig`, Fase 2). Ogni campo ha **una sola scrittura**; quelli facoltativi si
**omettono** quando sono assenti o vuoti (una lista vuota scritta e' `NOT_CANONICAL`):

| Campo | Obbligatorio | Forma |
|---|---|---|
| `nasHost` | si' | IP **letterale** (E-R7): IPv4 decimale stretto (niente zeri iniziali) o IPv6 tra parentesi quadre. Mai un nome. |
| `commonShare` | si' | nome di share (§8.1), al massimo 80 caratteri |
| `nasName` | no | nome DNS informativo (mai risolto) |
| `nasGuid` | no (l'app lo scrive sempre) | GUID del server SMB dalla NEGOTIATE, forma di `UUID.toString()` (minuscolo, 36 caratteri) |
| `homeUla` | no | al massimo 4 prefissi ULA /48 nella forma `fdxx:xxxx:xxxx::/48` (4 cifre esadecimali minuscole per gruppo), in ordine crescente. `UlaPrefix.canonical` converte le altre scritture (compresse, maiuscole). |
| `nasPower` | si' | `always` oppure `HH:MM-HH:MM` (inizio diverso dalla fine): da qui la fascia notte |
| `wolMac` | no | `aa:bb:cc:dd:ee:ff` minuscolo (WoL, dietro `FeatureFlags.wol`) |
| `commonExclusions` | no | al massimo 256 percorsi di media (§8.1, mai dentro `.mycloudgallery`), ognuno al massimo 512 byte UTF-8, in ordine crescente |
| `defaults` | si' | `{preset, quality}`: valori `[a-z][a-z0-9_]{0,15}` (l'app traduce i suoi enum; uno sconosciuto vale il default) |

### 5.4 Richiesta di registrazione (tipo 2)

`family`, `device`, `key`, `name`, `model`, `nasUser`, `requestedAt`. La `key` deve essere quella della
busta, `device` il suo id, e il file deve chiamarsi `requests/<device>`.

### 5.5 Testa di un dispositivo (tipo 3) e segmenti

`family`, `device`, `share`, `kind` (`index`, `personal` o, dalla Fase 6, `ai`), `version`, `registryVersion`, `createdAt`,
`segments` (al massimo 8192 elementi `{class, hash, size}`, in ordine crescente di impronta, ognuno al massimo
16 MiB). Il file deve stare in `devices/<device>/<kind>/head/<version>` della share `share`. Le teste sono una
serie numerata con le stesse regole del registro (§2.3: primo nome libero, lettura per nome, spazzatura
scavalcata).

- **Classi dei segmenti (Fase 4, M1).** `class` e' una fra `meta`, `thumbS`, `thumbM`, `analysis`, `hq`, `frames`,
  `personal` (`SegmentClass`); `faces` e' **riservata** e una testa che la elenca e' rifiutata
  (`INVALID_FIELD`) finche' una versione del formato non la definisce (E-I4). **Fase 6 (A2):** `vec` (7) e `ocr` (8) sono
  definite dalla versione 2 e valgono **solo in una testa `ai`**, che elenca solo segmenti `vec` e `ocr`; una testa `index` o
  `personal` non le elenca mai (se lo fa e' rifiutata, `INVALID_FIELD`). Una testa `personal` elenca solo segmenti
  `personal`; una testa `index` mai. Ogni dispositivo scarica solo le classi che gli servono (D16); la classe sta anche
  nel primo byte del contenuto del segmento (§5.5-bis) e i due valori devono coincidere.
- **Regole di pubblicazione (Fase 4, M3).** Un dispositivo pubblica nell'indice di una share **solo dati ricavati da
  file di quella share** (percorsi, impronte, metadati, miniature): niente di un file dell'area privata finisce
  nell'indice dell'area comune (C19; un test di proprieta' lo verifica). Al massimo **una testa ogni
  `HEAD_MIN_INTERVAL_MILLIS` (5 minuti) per share e tipo**, ora del NAS, salvo la chiusura di un'unita' di lavoro
  (§5.6). Prima di firmare: registro fresco, permesso di scrittura, nessun congelamento (`docs/fase4-progetto.md` D10),
  nessuna pubblicazione sulle share vere finche' vale D31, nessun salto dell'ora (§6.1). I segmenti si pubblicano prima
  della testa, con il nome uguale all'impronta: una ripetizione dopo un crash trova "esiste gia'" con gli stessi byte.
- **Lettura delle teste per nome (Fase 4, M3).** Come `DeviceStatusSeries` (§5.10): dall'ultima testa accettata
  (`HeadMark`) si legge la successiva **per nome**; di solito una sola lettura che risponde "non trovato" (nessuna
  novita'). Per un ritardo lungo, salti doppi e poi bisezione, tornando indietro di al massimo `MAX_JUNK_RUN` (32) nomi
  se in cima c'e' spazzatura; al massimo `MAX_HEAD_READS_PER_SHARE` (1024) letture per share e per giro e
  `MAX_INDEX_DEVICES_PER_SHARE` (256) dispositivi per share. Si tengono solo i dispositivi approvati o dismessi del
  registro accettato.

- **Testa `ai` (Fase 6, A2, A4, F6-D6, F6-D8).** `devices/<device>/ai/head/<version>`: stessa busta (tipo 3), stessa serie numerata e
  stessa lettura per nome e stesso anti-rollback delle altre teste (qui sotto), ma una serie **propria** (la cartella `ai/head/`:
  una testa `ai` non si confonde con una `index`) e la **versione 2** del formato nel quinto byte della busta e dei segmenti; elenca solo segmenti
  `vec` e `ocr` (§5.5-bis). La versione della busta deve corrispondere al tipo (P2): una testa `ai` alla versione 1 o una testa `index` o
  `personal` alla versione 2 sono rifiutate (`INVALID_FIELD`), e nessun altro tipo di file firmato accetta la versione 2 (`NEWER_FORMAT`). **Regole di pubblicazione:** la regola M3 qui sopra vale con le **chiavi di contenuto**: una voce `vec`
  o `ocr` si pubblica solo per la chiave di contenuto di un file **di quella share** presente nel catalogo locale, con timbro e `qh1`
  ancora freschi (niente di un file privato nell'indice dell'area comune; un test di proprieta' lo verifica). Valgono gli stessi
  cancelli dell'indice (nessuna pubblicazione sulle share vere finche' vale D31, area in sola lettura, congelamento, emergenza,
  salto dell'ora, fiducia), al massimo una testa `ai` ogni `HEAD_MIN_INTERVAL_MILLIS` per share salvo la chiusura di un'unita' di
  lavoro (§5.6), segmenti chiusi a `SEGMENT_TARGET_BYTES`. Sotto un blocco permanente (D31 su una share vera, area in sola lettura)
  i risultati restano nel dispositivo e si pubblicano **dopo**, quando la share si apre, senza ricalcolarli. I propri segmenti `ai`
  si compattano come quelli dell'indice (`Compactor` della Fase 4).
- **Anti-rollback per dispositivo**: chi legge ricorda per (share, dispositivo, tipo) l'ultima versione e
  l'impronta del contenuto firmato accettati (`HeadMark`); versione piu' bassa → `ROLLBACK`, stessa versione
  con contenuto diverso → `EQUIVOCATION`. La stessa testa con un'altra firma valida non e' un'equivocazione
  (domanda aperta 1 della revisione).
- **Dati personali** (`personal`): validi solo se firmati da un dispositivo dello **stesso account del NAS**
  di chi legge, e mai se stanno nell'area comune. **Fase 4 (M7)**: il registro non elenca le aree private, quindi
  valgono solo se trovati nella share privata **dichiarata localmente** per l'account di chi legge (onboarding,
  `LocalDeviceProfile.privateShare`), mai in un'altra share anche se firmati bene (revisione B6). Il contenuto della
  classe `personal` e' al §5.5-bis (voci per media e campo, 4B).
- `registryVersion` dice quale registro conosceva chi ha scritto: se e' piu' alto del nostro, il nostro e'
  indietro (forse qualcuno ce ne nasconde uno nuovo) e lo si rilegge; se non si trova, e' un'anomalia da
  Diagnostica.
- **Segmenti**: non firmati uno per uno; li autentica l'impronta nella testa firmata. Formato: `"MCGB"`, un
  byte di versione, poi il contenuto (§5.5-bis). Si accettano solo se dimensione e impronta
  coincidono con la testa (`Segments.unwrap`). Le immagini che contengono (miniature, anteprime) si
  decodificano solo entro 4096 × 4096 pixel e 16384 per lato (`ImageBounds`), leggendo prima le sole
  dimensioni.

### 5.5-bis Contenuto dei segmenti (Fase 4, M2)

Il contenuto dopo `"MCGB" | versione` (`Segments.wrap`). Codec stretto in `core-trust` (`IndexSegments`, Fase 4 P3);
tipi in `data/IndexSegment.kt` (`SegmentClass`, `MetaRecord`, `MetaFile`, `SegmentContent`).

```
offset  byte    contenuto
0       1       classe (1 meta, 2 thumbS, 3 thumbM, 4 analysis, 5 hq, 6 frames, 7 vec, 8 ocr, 16 personal; 9–15 e 17–31 riservate;
                17 album della Fase 7, §5.8; 18 faces, 19 cluster, 20 people della Fase 8, solo nelle teste dei volti, §5.11)
1       3       riservati = 0
4       4       N voci, 1 ≤ N ≤ MAX_SEGMENT_ENTRIES (4096), big endian
8       N×36    tabella: chiave (32 byte) + lunghezza del corpo (4 byte, big endian); chiavi strettamente crescenti
...             i corpi, nell'ordine della tabella, uno dopo l'altro, senza spazi ne' byte in piu'
```

- **Chiavi** (32 byte):
  - `meta`: `SHA-256("mcg-path" ‖ 0x00 ‖ percorso)`, con il percorso relativo alla share in UTF-8, forma **NFC** e
    **minuscolo** (`Locale.ROOT`): due nomi diversi solo per maiuscole sono lo stesso posto per Samba (E-N7);
  - `thumbS`, `thumbM`, `analysis`, `hq`, `frames` e, dalla Fase 6, `vec` e `ocr`: la **chiave di contenuto** `SHA-256("mcg-ck" ‖ 0x00 ‖ qh1 ‖
    dimensione)`, con `qh1` come 32 byte grezzi (`QuickHashAlgorithm`) e la dimensione come 8 byte big endian
    (`ContentKey` in `core-model`). Una rinomina dal PC cambia il record ma non la chiave: la preparazione non si
    rifa' (D4). Due file diversi solo nel mezzo hanno la stessa chiave (L32): la chiave decide cosa **mostrare**, mai
    un'unione d'identita';
  - `personal`: `SHA-256("mcg-pers" ‖ 0x00 ‖ tipo ‖ id del media o dell'album ‖ campo)` (4B, `docs/fase4-progetto.md`
    §2.9).
- **Corpi**:
  - `meta` e `personal`: JSON canonico (§3.2), al massimo `MAX_META_RECORD_BYTES` (4 KiB) e 32 campi;
  - immagini (`thumbS`, `thumbM`, `analysis`, `hq`): **solo** JPEG (`FF D8 FF`) o WebP (`RIFF…WEBP`), al massimo
    `MAX_BLOB_BYTES` (1 MiB). Le dimensioni si leggono dall'intestazione e si controllano con `ImageBounds` **prima** di
    qualunque decodifica; un altro formato o dimensioni oltre il tetto = segmento rifiutato;
  - `frames`: conteggio (1 byte, da 1 a `MAX_FRAMES_PER_MEDIA` = 9) e, per ogni fotogramma, istante nel video in ms
    (4 byte) + lunghezza (4 byte) + JPEG; al massimo `MAX_FRAMES_BODY_BYTES` (2 MiB) per media;
  - `vec` e `ocr` (Fase 6, versione 2): corpi **binari** con i loro tetti, descritti per intero sotto, in "Dati AI: i corpi `vec` e
    `ocr`".
- **Ordine della lettura**: dimensione e impronta uguali alla testa (`Segments.unwrap`) → classe uguale a quella della
  testa → `N` entro il tetto → tabella coerente (chiavi crescenti, somma delle lunghezze = resto del file esatto) → ogni
  corpo con i suoi tetti. **Nessuna allocazione da lunghezze dichiarate**: i corpi si leggono come finestre del
  segmento gia' verificato. Un errore scarta **il segmento intero** (`INDEX_REJECTED` in Diagnostica, E-I5, E-I14) e
  non ferma gli altri; i dati gia' nella replica restano (E-I17).
- **Un segmento si chiude** a `SEGMENT_TARGET_BYTES` (12 MiB) o a fine unita' di lavoro, sempre sotto
  `MAX_SEGMENT_BYTES` (16 MiB). Record vivi per dispositivo e share: al massimo `MAX_RECORDS_PER_DEVICE_SHARE` (262.144).

**Il record `meta`** (JSON canonico; i campi facoltativi si omettono, mai `null`):

| Campo | Forma | Note |
|---|---|---|
| `path` | percorso di **media** (§8.1, `SharePath.parseMediaPath`: mai dentro `.mycloudgallery`) | relativo alla share della testa; deve corrispondere alla chiave |
| `seq` | intero da 1 a `MAX_RECORD_SEQ` (10^9), crescente per (dispositivo, share) | fra record dello stesso dispositivo per lo stesso percorso vince il piu' alto (D5) |
| `gone` | `true` | **lapide**: il record ha solo `path`, `seq` e `gone`; al massimo `MAX_SEGMENT_TOMBSTONES` per segmento |
| `size`, `mtime`, `fid` | interi; `fid` facoltativo | il **timbro** (D6): il record vale solo se coincide con l'ultimo elenco del file |
| `qh`, `fh` | 52 caratteri base32 (32 byte); `fh` facoltativo | `qh1` e SHA-256 dell'intero file |
| `media`, `version`, `parent` | id da 128 bit in base32 (26 caratteri); `parent` facoltativo | identita' condivise (D7) |
| `vkind` | `original`, `edited`, `corrected` | codici di `VersionKind` |
| `kind`, `src`, `mime` | codici stabili di `MediaKind` e `MediaSource`; `mime` facoltativo | |
| `taken`, `offset`, `conf`, `origin` | ms UTC, minuti (±18 h), codici di `CaptureTimeConfidence` e `CaptureTimeSource` | tutti o nessuno |
| `w`, `h`, `dur`, `traits` | interi; dimensioni gia' orientate | |
| `geo` | `[lat×10^7, lon×10^7]` (interi) | facoltativo; sta solo nella share del file che lo contiene gia' |
| `th` | ThumbHash in base32, al massimo `MAX_THUMBHASH_BASE32_CHARS` (58) caratteri | segnaposto sfocato |
| `prep` | maschera delle classi pubblicate per la chiave di contenuto (bit = codice della classe) | mai `meta` o `personal` |
| `fail` | `{"code":…,"dec":…}` | D14: codice (`DECODER_MISSING`, …) e famiglia del decoder del dispositivo |

Un campo sconosciuto, un valore fuori forma, una lapide con campi in piu' o un record senza i campi obbligatori
(`path`, `seq` e, se non e' una lapide, `size`, `mtime`, `qh`, `media`, `version`, `kind`, `src`) rifiutano il
segmento. **Fiducia** (D8): un record serve a mostrare (miniature, metadati), a dare l'identita' ai media solo-NAS e a
coordinare il lavoro; **non basta** per unire un media del telefono a un file del NAS (serve l'impronta letta da chi
unisce), per dire "al sicuro" (rilettura locale con SHA-256) ne' per nessuna azione sui file (§9).

**Versioni caricate dal backup (Fase 5, N4).** Nessun campo nuovo e nessuna versione del formato: bastano `vkind` e `parent`
della tabella qui sopra.
- Il record `meta` di una **versione caricata** dal backup (D17, D18: una modifica fatta sul telefono o con un editor
  esterno, una correzione fatta dall'app) lo pubblica chi carica (D10), con il timbro letto **dopo** la pubblicazione;
  `vkind` e' `edited` (modifica) o `corrected` (correzione di data o luogo, E-L8) e `parent` e' la versione da cui nasce.
  `media` resta quello del media (stessa identita'), `version` e' quello della versione nuova.
- I file in `Versioni/` (§2.2) sono **file normali della share**, con il loro record `meta` pubblicato come quello degli
  altri file: la regola di pubblicazione del §5.5 (solo dati ricavati da file di quella share) vale cosi' com'e'. Il loro
  `path` e' un percorso di media come gli altri (§8.1).
- Archiviare una versione (5B) e' una **rinomina** nella stessa share: i byte e il `fileId` non cambiano, e la scansione la
  riconosce per `fileId` (E-N1) senza rifare il lavoro.

**Il record `personal`** (4B, M7; JSON canonico, codec `PersonalRecords` di `core-personal`): una *voce* `(tipo, soggetto, campo) →
valore`. Il tipo non sta nel corpo: lo dice la chiave della tabella, che chi legge ricalcola per ogni tipo noto.

| Campo | Forma | Note |
|---|---|---|
| `at` | ms, ora del NAS della modifica, entro il periodo plausibile del formato | D32: mai l'orologio del telefono |
| `author` | id del dispositivo (26 caratteri base32) | chi ha fatto la modifica, non chi pubblica (lo stato e' unito, D26) |
| `media` | il soggetto: id del media o dell'album, o `settings`; al massimo 128 caratteri | deve corrispondere alla chiave |
| `field` | il campo, al massimo 128 caratteri | deve corrispondere alla chiave |
| `fp` | `{"qh":…,"size":…}`, facoltativo | `qh1` in base32 e dimensione del **soggetto**: lo si ritrova se gli id divergono fra dispositivi |
| `value` | `true`/`false`, intero o testo (al massimo 1024 caratteri, senza caratteri vietati); assente = lapide | il significato dipende dal campo |

Un campo sconosciuto, un valore `null`, un oggetto o un elenco come valore, un testo fuori forma dove il campo ne chiede una
rifiutano il segmento.

**Separazioni fra copie simili (Fase 5, D36; impronta dell'altro media dal 20/10).** "Separa" e' una voce `media_flag` sul media
minore, campo `similar_apart:<id dell'altro media>`, con gli id del dispositivo che l'ha scritta. Fra due dispositivi la stessa foto
puo' avere id diversi (D7 converge al minore, D8 tiene l'id di un media con la copia sul telefono): per questo la voce porta
l'impronta di **entrambi** i media, quella del soggetto in `fp` e quella dell'altro nel `value`.
- `value` = il testo `<qh1 in base32>:<dimensione>` (52 caratteri, `:`, la dimensione in decimale senza zeri davanti): separati, e chi
  legge ritrova l'altro media per id o per questa impronta. Una sola scrittura per ogni impronta; un altro testo in una separazione
  rifiuta il segmento (`INVALID_FIELD`).
- `value` = `true` (voci scritte prima del 20/10, che sul NAS di prova possono esserci): separati, e l'altro media si ritrova **solo
  per id**, come allora. Chi scrive usa `true` solo se non conosce l'impronta dell'altro media.
- Lapide (nessun `value`): di nuovo unibili. Vale per la chiave, quindi anche con id diversi; la scrive chi riunisce, su ogni
  separazione che da lui nomina la stessa coppia (per id o per impronta).
- **Regola di chi legge** (soggetto e altro media allo stesso modo): l'identita' attuale dell'id (anche tramite un alias); se qui
  non c'e', l'impronta, **solo se indica un media solo**. Due media con la stessa impronta rapida (L32, o una raffica che il catalogo
  tiene separata) non ne indicano nessuno: la voce resta in attesa e non separa una copia a caso. Una voce letta dal NAS e' un dato,
  non un comando (E-S11): decide solo quali copie restano fuori da un gruppo che il dispositivo ricalcola da se'.
- **Versione**: nessuna. Il valore e' un testo, che la versione 1 gia' ammette, e non c'e' nessun campo nuovo: una versione
  precedente dell'app legge il segmento intero, prende quel testo per un testo qualunque e non separa niente (le copie restano unite
  li' finche' non si aggiorna), senza scartare le altre voci. L'impronta sta nel corpo, quindi la copre l'impronta del segmento
  elencata nella testa firmata, come ogni altro byte della voce.

**Campi della Fase 7 (A3; `docs/fase7-progetto.md` D4, risposta Q5).** Album, Raccolte e viaggi aggiungono **campi** (valori) dentro i
tipi esistenti `album`, `setting` e `media_flag`, senza tipi nuovi e senza versione nuova: un tipo nuovo farebbe scartare il segmento
intero a un'app vecchia, un campo nuovo no (un'app della Fase 6 lo legge, lo conserva e non lo usa). Le costanti sono
`PersonalFields` di `core-personal`.
- `album`: `kind` (`manual`/`smart`), `sort`, `smart:period`, `smart:place`, `smart:text`, `smart:example:<media>`, `smart:sources`,
  `smart:types`, `exclude:<media>`, `accept:<media>`, `frozen`, `shared` (id dell'album condiviso nato da questo), `copy_of` (id
  dell'album condiviso di cui e' la copia personale, Q5);
- `setting`: `collections_order`, `collections_hidden:<sezione>`, `albums_sort`, `trip_dismissed:<impronta>`, `shared_album_left:<id>`,
  `full_hash_nightly`;
- `media_flag`: `shared_origin` (la cartella d'origine di un mio media spostato in un album condiviso: sta qui, nell'area privata, e
  **mai** nella definizione comune).

**Campo della Fase 8 (A4; `docs/fase8-progetto.md` D3, D13, D30).** `setting`: `faces` (soggetto `settings`), testo `1|on|<G>|<v>`
(Persone attiva per l'account, generazione `G` in base32 da 26 caratteri, versione `v` del testo della pagina di attivazione accettato)
oppure `1|off|<G>` (spenta dopo la generazione `G`: pubblicata da un dispositivo **solo dopo** aver cancellato i dati di `G`, quindi e'
anche la sua conferma); assente = mai attivata. Una forma sconosciuta (un'app piu' nuova) non rifiuta il segmento: chi legge non calcola,
non pubblica e **non cancella** niente e dice "Aggiorna l'app" (`FacesSettingCodec`, `FacesSetting.Unknown` di `core-model`). Nessun tipo
nuovo. **`person` resta riservato**: rifiutato nelle teste `personal` (`RESERVED_KIND`) e mai scritto dal magazzino
(`Phase8FacesContractTest`); nomi e correzioni delle persone stanno nella classe `people` sotto `faces/` (sotto), cosi' "Disattiva e
cancella" li toglie con la cartella della generazione, anche quelli dei dispositivi spenti.

**Impronta obbligatoria** (`fp`) sulle voci `item:`, `exclude:`, `accept:`, `smart:example:`: chi legge ritrova il media per impronta se
gli id divergono fra dispositivi (stessa regola delle separazioni: solo se indica un media solo).

**Dati AI: i corpi `vec` e `ocr` (Fase 6, versione 2, A3, F6-D6).** Dopo `"MCGB" | versione 2` il contenuto ha la stessa tabella
di sopra (classe 7 o 8, N voci, chiavi strettamente crescenti, lunghezze), con la **chiave di contenuto** come chiave. Il corpo e'
binario, big endian come il resto del formato. Una voce `vec` o `ocr` descrive **un contenuto** (non un percorso).

*Corpo `vec`* (il vettore di una foto, o i vettori dei fotogrammi di un video):

```
offset  byte        contenuto
0       1           versione del corpo (1)
1       1           spazio (1 = embeddinggemma2-float-v1; altri rifiutati)
2       2           dimensione (768 per lo spazio 1)
4       1           precisione (2 = float16; altre rifiutate)
5       1           ricetta (1..255)
6       1           sorgente (1 anteprima di analisi, 2 fotogrammi, 3 byte originali del telefono)
7       1           N vettori (1..9; esattamente 1 per le sorgenti 1 e 3)
8       N × 4       istante del fotogramma in ms (solo sorgente 2)
...     N × 768 × 2 componenti float16
```

Lunghezza esatta; ogni componente finita (niente NaN, niente infiniti); norma ricalcolata in float32 fra 0,99 e 1,01; spazio, precisione,
dimensione o ricetta sconosciuti rifiutano il segmento. Il corpo piu' grande possibile (9 vettori con gli istanti) e' di **13.868 byte**,
sotto il tetto di **14 KiB** del §7.

*Corpo `ocr`* (il testo letto in un'immagine, con la posizione delle righe e delle parole):

```
offset  byte        contenuto
0       1           versione del corpo (1)
1       1           motore (1 = ML Kit Text Recognition v2, script latino)
2       1           ricetta OCR (1..255)
3       1           sorgente (1 analisi, 2 HQ, 3 fotogrammi, 4 byte originali del telefono)
4       2           N righe (0..400; 0 = "analizzata, nessun testo")
6       ...         per riga: riquadro (4 × 2 byte, x0 y0 x1 y1 in 1/65535 dell'immagine raddrizzata, x0 ≤ x1, y0 ≤ y1),
                    inclinazione (1 byte con segno, gradi da −90 a 90, dall'angolo della riga di ML Kit),
                    M parole (1 byte, 1..64),
                    M × (2 + 2 byte): inizio e fine di ogni parola lungo la riga, in 1/65535 della lunghezza della riga,
                    non decrescenti,
                    lunghezza del testo (2 byte, 1..1024), testo UTF-8 (le parole sono i pezzi separati da uno spazio: devono
                    essere M)
```

Testo: UTF-8 valido (niente forme lunghe ne' surrogati), in forma NFC, senza caratteri di controllo C0 e C1, senza i caratteri di
direzione (U+200E, U+200F, U+202A–U+202E, U+2066–U+2069) e senza U+FEFF: in pratica (P2) nessuno dei caratteri che `TextRules` vieta in
ogni testo letto dal NAS (anche gli invisibili, i separatori di riga U+2028 e U+2029 e i non caratteri), un elenco esplicito che non
dipende dalla versione di Unicode del dispositivo. Le parole sono i pezzi del testo separati da uno spazio e **nessuna e' vuota** (niente
spazi ai bordi o doppi: una parola vuota non ha estremi utili al testo dal vivo). Corpo al massimo **64 KiB**, al massimo 400 righe, ogni
riga al massimo 1.024 byte di testo e 64 parole. Inclinazione ed estremi delle parole servono al testo dal vivo nel viewer: costano
circa 4 byte a parola (una schermata fitta da 400 parole, 1,6 KB in piu') e permettono di selezionare e copiare il testo anche dove
ML Kit non gira. "Nessun testo" (0 righe) e' un risultato valido: dice che l'immagine e' stata letta.

**Regole di chi legge** (come sopra: dimensione e impronta uguali alla testa, classe uguale, tabella coerente, ogni corpo nei suoi
tetti, nessuna allocazione da lunghezze dichiarate). Ogni errore scarta **il segmento intero** (`AI_SEGMENT_REJECTED` in
Diagnostica), non ferma gli altri segmenti; i dati gia' nella replica restano (E-I17, E-I20). I nomi `AI_SEGMENT_REJECTED` e
`AI_VECTOR_DISAGREE` sono codici della Diagnostica, non costanti del formato.

**Attestazione e fusione dei dati derivati (Fase 6, A4, F6-D7).**
- Una voce `vec` o `ocr` vale solo se, **nella stessa share**, c'e' un record `meta` **presente** (di qualunque dispositivo
  approvato: il vincitore della regola D6 della Fase 4) con la **stessa chiave di contenuto intera** (32 byte, non i primi 64 bit:
  R1 della 4A). Il dispositivo che pubblica la voce **non deve avere un record suo**: il calcolo lo fa chi indicizza, non chi ha
  preparato, quindi la regola "stesso dispositivo" dei blob dell'indice qui non vale. Senza il record la voce resta in attesa (E-I21)
  e si riattesta a ogni testa applicata.
- Teste `ai` di dispositivi revocati, persi o sconosciuti: ignorate e dimenticate (come per le teste `index`); area persa (E-I8): le
  voci di quella share escono dalla replica.
- Fra piu' voci valide per (share, chiave di contenuto, classe) vince la **ricetta piu' alta**, a parita' l'id del dispositivo
  minore; `vec` e `ocr` si scelgono separatamente. A parita' anche di dispositivo (lo stesso dispositivo ha ripubblicato la stessa
  ricetta con un corpo diverso, in due segmenti) vince il corpo con l'impronta SHA-256 minore: arbitrario ma uguale per tutti (P2). Due vettori della stessa ricetta che non concordano (coseno < 0,95): vince
  comunque la regola qui sopra e il disaccordo si conta in Diagnostica (`AI_VECTOR_DISAGREE`). Un dispositivo approvato compromesso
  puo' far trovare male una foto, non toccare file (L45 di `docs/sicurezza.md`).
- **Pubblicazione:** la regola M3 del §5.5 (solo chiavi di file di quella share, timbro e `qh1` freschi), gli stessi cancelli
  dell'indice, una testa `ai` al piu' ogni 5 minuti per share salvo la chiusura di un'unita' (§5.6).

**Compatibilita'** (E-I3, E-I22): la versione 2 vale solo per teste e segmenti `ai`; chi legge accetta 1..2 (§10); un'app senza
la Fase 6 non apre `ai/`, non rifiuta le altre teste e i suoi dati `index` e `personal` restano validi.

**Dati dei volti: le classi `faces`, `cluster` e `people` (Fase 8, versione 4, A3; `docs/fase8-progetto.md` §2.4, D12, D13, D20-D23).**
Stanno **solo** nei segmenti elencati da una testa dei volti (tipo 9, §5.11), versione 4 del contenitore `MCGB`, con la stessa tabella di
sopra (classe, N voci, chiavi strettamente crescenti, lunghezze), al piu' `MAX_FACES_SEGMENT_BYTES` (12 MiB). In tutte le altre teste
(`index`, `personal`, `ai`, album) il nome di filo resta `faces`, **riservato** (`RESERVED_WIRE`): una di quelle teste che elenca una
classe dei volti e' rifiutata, e `IndexSegments` rifiuta le classi 18–20 nei loro segmenti. Una classe dei volti non e' mai un blob di
contenuto ne' una classe dell'indice (`SegmentClass.isFaces`).

*Corpo `faces`* (classe 18; chiave = chiave di contenuto; binario, big endian):

```
offset  byte        contenuto
0       1           versione del corpo (1)
1       1           spazio (1 = sface-2021dec-v1: 128 dimensioni, float16; altri rifiutati)
2       1           rilevatore (1 = YuNet 2023mar, 2 = YuNet 2026may)
3       1           ricetta (1..255)
4       1           sorgente (1 analisi, 2 HQ a tasselli, 3 fotogrammi, 4 byte originali del telefono)
5       1           N volti (0..64; 0 = "analizzato, nessun volto")
6       N x V       per volto:
                    1   fotogramma (0 per le foto; 0..8, l'indice nel blob `frames`)
                    8   riquadro x0 y0 x1 y1 (4 x 2 byte, in 1/65535 dell'immagine raddrizzata, x0 < x1, y0 < y1)
                    20  cinque punti (occhio destro, sinistro, naso, bocca a destra, a sinistra) x (x, y), in 1/65535
                    1   punteggio del rilevatore (0..255 = 0..1)
                    1   livello (2 bit: 0 NONE, 1 WEAK, 2 CORE) e motivi (bit: piccolo, profilo, sfocato, fotogramma)
                    2   lato corto del riquadro in pixel della sorgente dell'allineamento (1..65535)
                    1   imbardata stimata (gradi, con segno, -90..90)
                    1   nitidezza (0..255)
                    0 o 256  vettore: 128 x 2 byte float16, presente solo con livello WEAK o CORE
```

Lunghezza esatta; ogni componente finita; norma ricalcolata in float32 fra 0,99 e 1,01; al piu' `MAX_FACES_BODY_BYTES` (il corpo piu'
grande e' di 18.630 byte: 6 + 64 volti da 291). Nessun attributo della persona (eta', genere, emozioni: D19). Chi pubblica scrive una voce
solo per chiavi di contenuto di media del NAS di questo account che il suo catalogo conosce con timbro e `qh1` freschi; chi legge **usa**
una voce solo se il suo catalogo ha un media visibile con quella chiave. **Fusione**: per una chiave, fra i corpi dei dispositivi
dell'account vince la ricetta piu' alta, poi il dispositivo minore, poi l'impronta del corpo minore (`FaceBody.winner` di `core-faces`);
i riferimenti dell'istantanea portano le prime 8 byte di quell'impronta (`FaceRef.bodyDigest`).

*Voci `cluster`* (classe 19, solo dal custode, D21; corpi binari, al piu' `MAX_CLUSTER_ENTRY_BYTES`):

| Voce | Chiave | Corpo |
|---|---|---|
| Persona | SHA-256(`"mcg-face-person\0"` ‖ 16 byte dell'id) | versione (1), stato (1 attiva, 2 unita in un'altra, 3 dimenticata), id (16), alias (16, zeri se nessuno), `bornSeq` (8), `changedSeq` (8), volti (4), centroide (128 x 2 float16), N esemplari (1, ≤ 16) x riferimento a un volto (chiave di contenuto 32, fotogramma 1, indice 1, prime 8 byte dell'impronta del corpo `faces`) |
| Assegnazione | SHA-256(`"mcg-face-assign\0"` ‖ riferimento del volto) | versione (1), riferimento del volto (42), persona (16), punteggio (2: coseno x 10.000), origine (1: automatica, dell'utente, ignorata) |

*Voci `people`* (classe 20; JSON canonico al piu' `MAX_PEOPLE_ENTRY_BYTES`, come i dati personali): `{at, author, field, subject,
value}`, `value` assente = lapide; chiave = SHA-256(`"mcg-people\0"` ‖ soggetto ‖ `\0` ‖ campo); `at` = ora del NAS (dal 2020 al 2100,
non oltre 2 minuti nel futuro); `author` = un dispositivo approvato dell'account; l'ultima scrittura vince come `PersonalMerge` (ora, poi
autore minore, poi valore; una voce non ancora datata vale subito sul dispositivo che l'ha scritta e prende l'ora alla pubblicazione).

| Campo | Soggetto | Valore |
|---|---|---|
| `name` | persona | testo da 1 a 64 caratteri (`MAX_PERSON_NAME_CHARS`), regole di `TextRules`, senza spazi ai bordi |
| `hidden` | persona | vero/falso (Nascondi) |
| `forget` | persona | vero (Dimentica) |
| `alias` | persona | id della persona in cui e' stata unita |
| `apart:<persona>` | persona | vero: mai unite in automatico ("Separati", da "Dividi") |
| `born` | persona | `split:<id d'origine>` (persona nata da "Dividi") |
| `cover` | persona | localizzatore del volto scelto |
| `is` | localizzatore di un volto | id della persona ("E' …") |
| `not:<persona>` | localizzatore di un volto | vero ("Non e' …") |
| `ignore` | localizzatore di un volto | vero ("Ignora questo volto") |

Persona = `PersonId` (UUID canonico, 128 bit; nei corpi binari 16 byte, nei nomi base32). Localizzatore (`FaceLocator`, D23):
`<chiave di contenuto in base32>:<fotogramma>:<x0>.<y0>.<x1>.<y1>` (riquadro in 1/65535), al piu' `MAX_FACE_LOCATOR_CHARS`; un volto
attuale corrisponde con IoU ≥ 0,5. Un campo sconosciuto o un valore d'altra forma rifiuta il segmento. Ogni dispositivo pubblica **lo
stato unito** delle voci `people` che conosce (come F4-D26): un telefono spento non porta via i nomi.

### 5.6 Prenotazione (tipo 4)

`family`, `device`, `share`, `resource` (chiave del lavoro, al massimo 256 caratteri), `generation`,
`acquiredAt` (ora del NAS), `durationMillis` (0 = rilasciata). Sta in
`leases/<SHA-256 di resource>/<gruppo>/<generation>`, con `gruppo = (generation − 1) / 256 + 1`: una
prenotazione copiata in un altro posto non vale. Regole al §6.2.

**Unita' di lavoro (Fase 4, M4).** La chiave di una prenotazione di lavoro e'
`work/1/<passaggio>/<aaaa-mm>/<parte>` (es. `work/1/prep/2024-03/2`; `0000-00` per i media senza data): passaggio,
mese di scatto e numero della parte, **mai nomi di cartelle o di file** (la chiave sta in chiaro nel record firmato).
Una parte e' al massimo `WORK_UNIT_MAX_ITEMS` (200) media del mese, ordinati per chiave di contenuto. Durata 1 ora;
**rinnovo ogni `LEASE_RENEW_MILLIS` (20 minuti)** con una generazione nuova; fine = generazione con durata 0. Si prenota
l'unita', mai il singolo media (`docs/fase4-progetto.md` D13).

**Unita' del calcolo AI (Fase 6, A5, F6-D9).** Stessa chiave, stessa durata, stesso rinnovo, nuovo passaggio: `work/1/ai/<aaaa-mm>/<parte>`
(es. `work/1/ai/2024-03/2`; `0000-00` per i media senza data), al massimo `WORK_UNIT_MAX_ITEMS` (200) media del mese per parte, ordinati per
chiave di contenuto. Nessun campo nuovo e nessuna versione del formato: una prenotazione e' un file del tipo 4 come le altre.
Un contenuto e' "fatto" **per classe**: quando una testa `ai` attestata (§5.5-bis) di **qualunque** dispositivo approvato ha la voce
della ricetta corrente. `vec` lo cerca ogni indicizzatore; `ocr` solo un indicizzatore con la lettura del testo attiva; per gli
altri un `ocr` mancante non e' lavoro da fare. All'inizio di ogni unita' si rileggono le teste `ai` della share e si salta cio'
che altri hanno gia' pubblicato (due indicizzatori non fanno lo stesso lavoro, E-I1; uno che muore libera l'unita' entro l'ora,
E-I2). Per i dati `ai` non c'e' la prova "ha pubblicato la miniatura S" (evidenza `THUMB_S`) delle unita' di preparazione.

**Prenotazioni dei volti (Fase 8, A5; `docs/fase8-progetto.md` D16, D21).** Stanno **sotto la generazione**, nell'area privata
dell'account: radice `faces/<G>/leases/` (`NasLayout.facesLeaseRoot`), con la stessa struttura di `leases/`
(`<SHA-256 della risorsa>/<gruppo>/<numero>`), cosi' "Disattiva e cancella" le toglie con la cartella. Due risorse, **mai nomi di share,
cartelle o file**: `faces/<G>/work/<area>/<aaaa-mm>/<parte>` (`area` = `priv` o `comm`, mai il nome della share; al massimo
`WORK_UNIT_MAX_ITEMS` (200) media del mese per parte, per chiave di contenuto; `NasLayout.facesWorkResource`) e `faces/<G>/cluster` (il
custode del raggruppamento, uno per account; `NasLayout.facesClusterResource`). Stessa durata (1 ora) e stesso rinnovo (20 minuti), nessun
campo nuovo. Un contenuto e' "fatto" quando un dispositivo **dell'account** ha una voce `faces` della ricetta corrente o lo ha segnato
"non supportato": le unita' della Fase 6 stanno nella share e sono di tutti gli account, quelle dei volti no.

### 5.7 Record del cestino (tipo 5)

`family`, `device`, `share`, `trashId`, `originalPath` (percorso di media validato, §8.1), `size`,
`trashedAt`. Sta in `trash/<device>/rec/<trashId>`; il file cestinato e' `trash/<device>/files/<trashId>`,
senza estensione (§2.1). Serve a mostrare "Eliminati di recente" e a sapere dove ripristinare; **non decide
mai una cancellazione** (§9). Il ripristino e' un'azione locale confermata: rinomina nella stessa share verso
`originalPath` senza sovrascrivere (collisione = nome alternativo, E-D3, E-SH4).

**Campi nuovi (Fase 4, M5; codice nella 4B, P12):**
- `reason`: `delete` (eliminato dall'utente) o `move` (sorgente di uno spostamento tra share, tenuta 30 giorni: D24, Q3);
- `folder`: `true` se l'elemento cestinato e' una cartella intera (una sola rinomina, E-D10), `false` altrimenti;
- `count`: solo per le cartelle, quanti file conteneva al momento del cestino (per "Eliminati di recente"); assente per i
  file. `size` per una cartella e' la somma dei file.

**Chi puo' fare cosa:** il **ripristino** lo puo' chiedere qualunque dispositivo approvato che abbia il permesso di
scrittura sulla share (l'utente lo conferma sul suo dispositivo); la **pulizia** dopo 30 giorni la fa solo l'autore, dal
proprio diario locale (§9, punto 3). Un dispositivo dismesso o azzerato lascia i suoi elementi nel cestino per sempre
(L31 di `docs/sicurezza.md`): si eliminano solo a mano.

### 5.8 Testa di un album condiviso (tipo 6, Fase 7)

**Fase 7 (A2; `docs/fase7-progetto.md` D6-D11, §2.4; codec `AlbumDocs` di `core-trust`, replica, fusione, permessi e pubblicatore in
`core-albums/.../shared/`, P2).** Un file per album e per dispositivo (`albums/<id album>/<id dispositivo>/head/<versione>`), firmato da un
dispositivo approvato, con anti-rollback ed equivocazione come le teste (§5.5, `HeadMark`): ogni dispositivo scrive solo il **suo
contributo**, cosi' due familiari che modificano lo stesso album non si sovrascrivono (E-SH6). Versione **3** del formato (§10), tetto
256 KiB (`MAX_ALBUM_HEAD_PAYLOAD`). I tetti degli album sono `AlbumLimits` di `core-trust` (uguali a `SharedAlbumLimits` di `core-albums`,
test).

**La testa** (`AlbumDocs.encodeHead`/`decodeHead`): `{album, createdAt, device, family, registryVersion, segments, version}`; `album` e'
l'id in base32 (26 caratteri, §2.4), `device` = firmatario, `createdAt` = ora del NAS, `segments` da 0 a 64 elementi `{class, hash, size}` con
`class` = `album`, in ordine crescente di impronta, ognuno al piu' 4 MiB e la somma al piu' 32 MiB (una testa che dichiara di piu' si rifiuta
**prima** di scaricare). Ordine della lettura: busta (tipo 6; versione oltre la 3 = `NEWER_FORMAT`) → firmatario nel registro accettato
(approvato; dismesso **solo** se l'impronta dei byte firmati della testa e' fra le sue `frozenHeads`, altrimenti `SIGNER_RETIRED`; revocato o
perso `SIGNER_REVOKED`; sconosciuto o in attesa `SIGNER_NOT_APPROVED`) → versione della busta uguale alla 3 (una testa d'album alla 1 o alla 2
e' `INVALID_FIELD`) → JSON e campi (famiglia, `device` = firmatario, tetti) → posto (album, cartella del dispositivo e numero del nome
uguali a `album`, `device`, `version`: `WRONG_LOCATION`) → anti-rollback (`ROLLBACK`, `EQUIVOCATION`) rispetto all'ultima testa accettata
dello stesso album e dispositivo. Al piu' una testa ogni 5 minuti per album e dispositivo (ora del NAS), modifiche accorpate in 30 s.

**I segmenti** (`AlbumDocs.encodeSegment`/`decodeSegment`): contenitore `MCGB` con la stessa tabella del §5.5-bis e la classe nuova **`album`
(codice 17)**, solo nelle teste di tipo 6 (una testa `index`, `personal` o `ai` che la elenca e' rifiutata; il codec dell'indice e quello dei
dati AI non la leggono):

```
"MCGB" | 3 | 17 | 0 0 0 | N (1..8.192, BE) | N x (chiave 32 + lunghezza 4, BE) | corpi (JSON canonico, 1..4.096 byte ciascuno)
```

Chiavi strettamente crescenti, somma delle lunghezze = resto del file; versione oltre la 3 = `NEWER_FORMAT`, la 1 o la 2 = `MALFORMED` (un
segmento d'altro tipo spacciato per album), classe diversa da 17 = `WRONG_TYPE`. Chiave di una voce = `SHA-256("mcg-album\0" ‖ 16 byte
dell'id album ‖ campo in UTF-8)`: chi legge la ricalcola e una chiave diversa rifiuta il segmento. Ogni errore rifiuta il segmento, e con lui
il contributo intero.

**La voce**: `{account, at, author, field, value}`, con `value` assente = **lapide**.
- `account` = il `nasUser` del firmatario nel registro, **in minuscolo** (una sola scrittura dell'account: `Test` e `test` sono lo stesso
  account per Samba), altrimenti `NOT_OWNER`; `author` = id del firmatario, altrimenti `WRONG_LOCATION`; `at` = ora del NAS della modifica,
  dal 2020 al 2100.
- `field`: `definition`, `level`, `cover`, `sort`, `heir` oppure `item:<id del media in base32>`; un campo sconosciuto rifiuta il segmento.
- La lapide vale solo per `cover` (copertina automatica), `sort` (ordine predefinito) e `item:` (elemento tolto); per gli altri campi rifiuta.

| Campo | Valore | Regole del codec |
|---|---|---|
| `definition` | `{afterClose?, creator, folder, nonce, state, title}` | `title`: da 1 a 64 caratteri (punti di codice), senza spazi ai bordi, nessun carattere vietato del §8.1 (controlli, direzione del testo, invisibili); `folder`: percorso di media relativo all'area comune (§8.1, mai dentro `.mycloudgallery`, confronto NFKC), al piu' 1.024 byte; `creator`: account minuscolo; `nonce`: 16 byte in base32; `state`: `active`, `closed`, `deleted`; `afterClose` **obbligatorio** con `closed`/`deleted` e vietato con `active`, un codice `[a-z][a-z0-9_]{0,15}` (`remove`, `keep_copy`; uno sconosciuto vale `remove`, risposta Q5) |
| `level` | `{history: [{at, level}]}` | da 1 a 32 cambi, ore strettamente crescenti e mai dopo l'`at` della voce, livelli `all`, `add`, `view` |
| `cover` | `{key, media}` | chiave di contenuto (52 caratteri base32) e id del media |
| `sort` | `{sort}` | `oldest`, `newest`, `custom` |
| `item:<media>` | `{addedBy, fh?, key, path, position, version?}` | chiave di contenuto **intera**, `fh` (SHA-256 del file, se chi aggiunge lo conosce: chi legge lo confronta con il suo, D9), `path` percorso di media relativo all'area comune al piu' 1.024 byte (§8.1, NFKC), `position` da 0 a 2^53, `version` id facoltativo, `addedBy` account minuscolo |
| `heir` | `{account, registryVersion}` | account minuscolo, versione del registro da 1 a 10^9 |

**Il contributo intero** (`AlbumDocs.checkContribution`, dopo tutti i segmenti): al piu' 20.000 voci, **una per chiave**, e ogni `definition`
con l'account e il nonce da cui si ricalcola **questo** id (E-SH14). Una violazione rifiuta il contributo intero; gli altri contributi valgono.

**Regole di chi legge** (`NasSharedAlbumSync`, D8): solo nell'area comune (`commonShare` del registro) e solo con la recinzione D30 aperta per
quella share (`AlbumReadGate`); `albums/` elencata al piu' per 1.024 nomi (i nomi che non sono id si ignorano; con l'elenco inondato si leggono
comunque gli album gia' noti e i propri), le cartelle dei dispositivi approvati o dismessi del registro, le teste **per nome** come al §5.5 (al
piu' 4.096 letture per giro, a rotazione fra gli album); i segmenti gia' scaricati si riusano. Una testa o una voce con l'ora oltre 2 minuti
nel futuro fa **aspettare** il contributo; una voce **nuova** (assente o diversa nella testa precedente dello stesso dispositivo letta da
questo telefono) con `at` anteriore alla `createdAt` di quella testa meno 2 minuti e' **retrodatata**: si scarta, e resta scartata nelle teste
dopo (un dispositivo mai letto prima puo' retrodatare: L59). Tetti per dispositivo (100.000 voci e 512 album nella replica) e per giro (64 MiB
di segmenti, il resto al giro dopo). Il contributo di un dispositivo revocato, perso, sconosciuto, o dismesso con una testa non congelata esce
dalla fusione (A7, E-SH20; resta solo come memoria locale per il ripristino, sotto). Un errore qualunque resta nell'album che l'ha prodotto: la
sincronizzazione dell'indice e gli altri album vanno avanti.

**Fusione e permessi** (`AlbumFusion`, `AlbumPermissions.check`, la stessa tabella di chi scrive, C25). Per ogni dispositivo vale l'ultima
testa accettata (unione commutativa, associativa e idempotente); le voci di una chiave si ordinano per `at` e, a pari ora, vince l'autore
minore. Poi:
1. il **creatore** e' l'account dell'id (D7); gli **eredi** (D11) sono una linea del tempo: una voce `heir` vale se la firma un dispositivo
   admin approvato, per il proprio account, con l'account del creatore senza dispositivi approvati ne' dismessi e `registryVersion` non oltre
   il registro di chi legge, e da quell'ora l'erede ha i diritti del creatore;
2. la **base** dell'album e' la prima `definition` del creatore o dell'erede in vigore (anche `closed`/`deleted`: ogni dispositivo tiene solo
   l'ultima voce per chiave e la creazione puo' essere gia' stata sostituita dalla chiusura); senza base l'album non c'e'. La **chiusura** e'
   la prima loro `definition` `closed`/`deleted`: e' definitiva, dopo non vale piu' nessuna voce;
3. i **livelli** sono la storia dell'ultima voce `level` del creatore o dell'erede (mai dopo la chiusura); **prima del primo cambio vale
   `view`** (solo il creatore);
4. ogni altra voce si giudica con il livello in vigore alla **sua** ora e con la voce gia' ammessa per la stessa chiave subito prima: un
   elemento nuovo e' "Aggiungi" (e `addedBy` = chi scrive); sopra un elemento vivo `addedBy` non cambia mai (il proprio = come aggiungere,
   quello di un altro = come toglierlo); una lapide e' "Togli" confrontata con `addedBy` di prima, oppure "Riporta" del proprio elemento (a
   `view` vale per chi l'aveva aggiunto quando il livello lo permetteva); una lapide su niente non ha effetto; una `definition` che cambia
   titolo o cartella e' "Rinomina", una che cambia lo stato e' "Smetti di condividere"/"Elimina l'album".
Una voce non permessa si **scarta da sola** (`ALBUM_ENTRY_DENIED`); le altre valgono.

**Pubblicazione** (`NasAlbumPublisher`): le voci del proprio contributo (pubblicate e in coda) si dividono in segmenti **stabili** per secchi
della chiave (da 1 a 32 secchi dai primi bit, circa 512 voci a secchio): una modifica di solito riscrive un segmento solo. Prima i segmenti
(nome = impronta; un nome gia' presente deve avere gli stessi byte), poi la testa al primo numero libero (§2.3); **solo `publishExclusive`,
solo sotto `albums/<album>/<proprio dispositivo>/`**. Nessuna voce in coda e' datata prima della propria ultima testa (con un salto indietro
dell'ora del NAS chi legge la scarterebbe come retrodatata). Chi scrive non pubblica mai una voce che chi legge rifiuterebbe
(`AlbumDocs.entryBytesOrNull`). Con lo store locale perso, la propria ultima testa sul NAS dice numero, ora e voci gia' pubblicate.

**Id dell'album legato al creatore (D7)**: i primi 128 bit di `SHA-256("mcg-album-id\0" ‖ account del creatore ‖ nonce)`; chi legge li
ricalcola da `definition` e rifiuta il contributo se non tornano (`AlbumId.derived` di `core-model`, `AlbumIds.matches`, E-SH14). In app lo
stesso id ha la forma di un UUID; nei percorsi la sua forma base32 (`AlbumIds.text`, la stessa conversione dei 128 bit per album, media e
versioni).

**Ricostituzione dell'erede (D11, E-SH11).** Quando l'account del creatore non ha piu' dispositivi approvati ne' dismessi, i suoi contributi
non valgono piu' (A7) e l'album resta senza base. L'admin che eredita pubblica nel **suo** contributo la voce `heir`, la `definition` con lo
stesso creatore e nonce (stesso id, stessa cartella), la storia dei livelli com'era (`level` con le ore di allora: le voci degli altri si
giudicano come prima) e gli elementi del creatore i cui file il suo catalogo vede ancora nell'area comune, a suo nome. Li prende dalla
memoria locale della replica (i contributi esclusi non si cancellano subito: non entrano mai nella fusione ne' in una schermata). Se il
creatore torna in famiglia, l'eredita' non vale piu'.

**Q5 (22/10): cosa vedono gli altri alla chiusura lo decide chi chiude.** `afterClose` = `remove`: l'album sparisce dalle Raccolte degli
altri, con l'avviso. `keep_copy`: ogni familiare che non l'aveva lasciato tiene una **copia personale** (un album dei suoi dati personali,
area privata, con id `SharedAlbumCopies.copyIdFor(album, account)` e la voce `copy_of`) con gli elementi che il suo catalogo attesta,
finche' i file restano nell'area comune. Un `afterClose` sconosciuto vale `remove`. La scelta e' dentro la `definition` firmata (l'impronta del
segmento sta nella testa firmata): chi non e' il creatore non la cambia, e un segmento cambiato dal PC rifiuta il contributo.

**Attestazione (D9)**: un elemento si mostra solo se il catalogo di chi legge vede, **nell'area comune**, un file presente con la stessa
chiave di contenuto intera (e lo stesso hash completo se entrambi lo hanno), e non e' nascosto (D22). Una definizione d'album, vera o falsa,
**non sposta, non copia, non cancella e non scarica nulla** (§9 punto 4, E-S11).

### 5.9 Segnalazione "perso" (tipo 7)

`family`, `device` (chi segnala, = firmatario), `target` (il dispositivo perso), `nasUser`, `reportedAt` (ora del
NAS). Firmata da un altro dispositivo **approvato** (non dismesso, revocato, perso o in attesa) **dello stesso
account** del dispositivo perso: `nasUser` deve essere l'account del firmatario e quello di `target` nel registro
accettato (`NOT_OWNER` altrimenti); `target` deve essere nel registro e diverso da `device`; `reportedAt` al
massimo 2 minuti nel futuro. Sta in `family/reports/<target>/<n>`, una serie numerata (primo nome libero; chi
legge va per nome da 1, spazzatura scavalcata, al massimo 32 di fila: inondare la cartella non nasconde una
segnalazione). Una copia in un'altra cartella non vale (`WRONG_LOCATION`).

La stessa segnalazione si pubblica in **due** share (`LostReports.publish`):
- nell'**area comune**, dove la vede l'admin, che la trasforma nello stato `lost` del registro con un gesto suo;
- nell'**area privata dell'account**, dove scrive solo chi ne ha la password: e' la **prova di possesso
  dell'account** (revisione B6, §12 punto 10). Con la decisione 3(a) il dispositivo perso, appena si ricollega,
  rispetta **solo** la copia che trova nella propria area privata.

Cosi' un dispositivo che ha dichiarato l'account di un altro familiare non puo' farne azzerare i telefoni: non
scrive nella sua area privata, e la copia nell'area comune la vede solo l'admin.

### 5.10 Stato del dispositivo (tipo 8)

`family`, `device`, `version`, `appVersion` (`[0-9A-Za-z][0-9A-Za-z.+_-]*`, al massimo 32 caratteri), `roles`
(array, sempre presente, valori `indexer`/`preparer` in ordine crescente, senza doppioni), `lastSeenAt` (ora del
NAS, al massimo 2 minuti nel futuro), `registryVersion`. Sta in `devices/<device>/status/<version>` dell'area
comune; firmato dal dispositivo stesso, **approvato** (in attesa, dismesso, revocato o perso → rifiuto, E-S14).
Serve solo all'elenco "Dispositivi" (L11): nessuna decisione dipende da qui, salvo l'avviso "registro rimasto
indietro" quando `registryVersion` e' piu' alto del nostro.

- **Anti-rollback come per le teste** (§5.5): chi legge ricorda per dispositivo l'ultima versione e l'impronta
  del contenuto firmato (`HeadMark`); versione piu' bassa → `ROLLBACK`, stessa versione con contenuto diverso →
  `EQUIVOCATION`, numero diverso dal nome → `WRONG_LOCATION`.
- **Al massimo una pubblicazione al giorno** (`DEVICE_STATUS_INTERVAL_MILLIS`, ora del NAS), approfittando di
  una sessione gia' aperta, oppure subito se cambiano versione dell'app o ruoli.
- La serie cresce di un file al giorno: chi legge non la percorre da 1 ma cerca **per nome** l'ultimo nome
  occupato partendo dall'ultimo stato accettato (salti doppi poi bisezione, poche letture anche dopo anni) e
  prende il piu' recente valido, tornando indietro di al massimo 32 nomi se in cima c'e' spazzatura. Un buco o
  un nome piantato lontano fanno vedere uno stato vecchio o nessuno: informazione persa, mai potere.

### 5.11 Testa dei volti (tipo 9, Fase 8)

`docs/fase8-progetto.md` §2.4, D11-D13, D21, D30, D31 (A2). Codec `FacesDocs` di `core-trust` (P3); costanti `TrustFormat.FACES_VERSION`
(4), `DocType.FACES_HEAD` e i tetti dei volti di `TrustLimits` (§7). Sta in `<area privata>/.mycloudgallery/faces/<generazione>/<id
dispositivo>/head/<versione>`: una serie numerata per dispositivo **e generazione**, letta per nome come al §5.5.

**Contenuto** (JSON canonico, al massimo `MAX_FACES_HEAD_PAYLOAD`, 1 MiB): `account` (il `nasUser` del firmatario, in minuscolo),
`createdAt` (ora del NAS), `device`, `family`, `generation` (base32, 26 caratteri), `registryVersion`, `segments` (da 0 a 8.192
`{class, hash, size}` con `class` ∈ {`faces`, `cluster`, `people`}, in ordine di impronta, ognuno al piu' 12 MiB; al piu'
`MAX_FACES_HEAD_ENTRIES` voci in tutto), `snapshot` (`{seq, base}`, **se e solo se** la testa elenca segmenti `cluster`), `version`. Un
campo sconosciuto rifiuta la testa.

**Regole di chi legge**, in quest'ordine; ogni errore scarta la testa o il segmento e non ferma gli altri dispositivi (E-F41):
1. busta: tipo 9; versione oltre la 4 = `NEWER_FORMAT`; una testa dei volti a una versione diversa dalla 4 = `INVALID_FIELD` (la busta
   sigilla la 4 solo per il tipo 9);
2. firmatario nel registro accettato: approvato, oppure dismesso con la testa fra le sue `frozenHeads`; **il suo `nasUser` e' l'account
   di questo dispositivo**, altrimenti `NOT_OWNER` (le teste dei volti di un altro account non si leggono nemmeno se finiscono nella mia
   area); revocato o perso = ignorato, e la sua cartella si toglie (§9, punto 6-bis);
3. campi: famiglia, `device` = firmatario, `account`, tetti;
4. posto: generazione e dispositivo della cartella e numero del nome uguali ai campi (`WRONG_LOCATION`);
5. **generazione corrente**: quella che dice la voce `faces` (§5.5-bis) in uno stato **fresco** dei dati personali (un giro completo
   da meno di 15 minuti in questo processo, orologio monotono, D30); una generazione non corrente non si legge mai;
6. anti-rollback ed equivocazione per (generazione, dispositivo) (`HeadMark`, come §5.5);
7. segmenti con le regole delle loro classi (§5.5-bis). Per giro: al piu' `MAX_FACES_BYTES_PER_ROUND` (64 MiB) di segmenti (il resto al
   giro dopo), `MAX_FACES_DEVICES_PER_GENERATION` (32) cartelle di dispositivi per generazione e `MAX_LIST_FACES_GENERATIONS` (64)
   generazioni elencate in `faces/` (i nomi che non sono id o generazioni si ignorano e si contano).

**Pubblicazione** (`FacesPublisher`, P3): prima i segmenti (nome = impronta), poi la testa al primo numero libero; **solo**
`publishExclusive`, **solo** sotto `faces/<G>/<io>/` dell'area privata dichiarata (mai se la share e' l'area comune, L23); dietro
`FacesWriteGate` (i cancelli di `IndexWriteRules` piu' la recinzione delle share vere, D34, D35); al piu' una testa ogni 5 minuti d'ora del
NAS, salvo la fine di un'unita'; **solo con lo stato fresco** che dice "attiva, generazione G" (D30). Voci `people` ripubblicate unite.

**Custode e istantanee** (D21): le voci `cluster` le pubblica solo chi tiene la prenotazione `faces/<G>/cluster` (§5.6). Un'istantanea
sono **tutte** le voci `cluster` elencate da una testa con `snapshot`; vale quella con `seq` piu' alto fra i dispositivi dell'account (a
pari `seq`, il dispositivo minore); `base` e' la `seq` da cui e' partita (serve a riallineare due custodi). Tetti `MAX_SNAPSHOT_PERSONS`
e `MAX_SNAPSHOT_ASSIGNMENTS` (§7).

**Una testa dei volti non decide azioni**: mostra gruppi e nomi solo per contenuti che il catalogo di chi legge vede gia' (come
l'attestazione degli album, F7-D9). Le sole cancellazioni legate ai dati dei volti sono quelle del §9, punto 6-bis.

---

## 6. Ora di riferimento e prenotazioni

### 6.1 L'ora del NAS

Scadenze, prenotazioni e istanti firmati usano **solo** l'ora del NAS (`NasClock.nowMillis()`), mai
l'orologio del dispositivo (E-P9): un test controlla che il codice del modulo non legga l'orologio di sistema
in nessuna delle forme note (`System`, `java.time`, `Calendar`, `SystemClock`…).

L'implementazione (Fase 2.1) somma all'orologio monotono del dispositivo lo scarto letto alla NEGOTIATE SMB
(`getTimeOffsetMillis`, gratuito a ogni connessione). Requisiti per la Fase 2.1 (revisione B8):

- solo **SMB 3.1.1 con firma obbligatoria**: l'hash di pre-autenticazione copre anche l'ora della
  NEGOTIATE (con 3.0/3.0.2 chi fa MITM in LAN potrebbe spostarla);
- controllo incrociato con l'ora assegnata dal server a un file appena pubblicato;
- un salto dell'ora del NAS tra due sessioni oltre una soglia sospende cestino e prenotazioni e va in Diagnostica.

**Soglia ed effetti (Fase 4, M8; `docs/fase4-progetto.md` D32, E-N17).** La soglia e' `NAS_CLOCK_JUMP_MILLIS`
(**5 minuti**, uguale a `SessionNasClock.DEFAULT_JUMP_THRESHOLD_MS` di `core-nas`): l'ora del NAS letta a una NEGOTIATE
che si scosta di piu' da quella prevista dall'ancora precedente (`SessionNasClock.lastJumpMs`) **sospende** le
prenotazioni, la pulizia del cestino, la compattazione e la pulizia dei propri file finche' l'utente non lo conferma in
Diagnostica o finche' l'ora non resta stabile per `NAS_CLOCK_STABLE_RESUME_MILLIS` (24 ore). Sotto la soglia bastano le
tolleranze esistenti (2 minuti nel futuro). Motivo: il NAS di casa oggi e' avanti di 53–58 s e deriva di ~5 s al giorno;
quando l'NTP verra' sistemato l'ora tornera' indietro di colpo, e un orologio che va all'indietro non deve far scadere
ne' eliminare nulla.

**Stabilita' dei file (E-N4).** Un file nuovo o cambiato e' "stabile" solo se visto due volte uguale a distanza di
almeno 2 minuti di **ora del NAS** (la data di modifica e' scritta dal NAS): mai con l'ora del telefono, che oggi e'
~55 s indietro e farebbe sembrare "appena scritto" un file finito.

Istanti plausibili: dal 2020 al 2100; fuori, il campo e' malformato.

### 6.2 Prenotazioni

Servono a non fare due volte lo stesso lavoro (E-I1); non proteggono nulla: se due dispositivi lavorano
insieme, il risultato resta coerente.

- **Generazioni con `FILE_CREATE`**: prendere una risorsa libera = pubblicare la generazione successiva
  alla piu' alta; se il nome esiste gia', ha vinto un altro (`Lost`). Rilascio = generazione con durata 0.
  Nessun file altrui viene mai toccato.
- **Gruppi di 256 generazioni** (revisione M6): si elencano i gruppi (al massimo 1024) e poi solo il gruppo
  piu' alto (al massimo 512 nomi). Prima, con una cartella sola, dopo 257 usi della stessa risorsa restava
  solo `Contended`; ora una risorsa resta prenotabile per oltre 260 000 generazioni.
- **Durata massima: 1 ora** (si rinnova pubblicando una generazione nuova). Una prenotazione con durata
  oltre il tetto, o che comincia piu' di **2 minuti** nel futuro rispetto all'ora del NAS, e' **ignorata**
  (la risorsa e' libera), non accorciata (E-I16). Un dispositivo che muore libera la risorsa entro l'ora
  (E-I2).
- Valgono solo le prenotazioni firmate da dispositivi approvati e trovate nel posto giusto; tutto il resto
  (spazzatura, revocati, copie) conta come "libera".
- Cartelle anomale (elenchi oltre i tetti, generazione al tetto di 10^9): si lavora senza prenotazione
  (`Contended`) e la Diagnostica lo dice. E' un danno solo di efficienza.

---

## 7. Tetti della lettura difensiva

| Che cosa | Tetto |
|---|---|
| Contenuto firmato per tipo | §5.1 (file intero: + 174 byte) |
| Segmento | 16 MiB |
| Kit di recupero | 1 KiB (PKCS#8 dentro: 512 byte) |
| JSON: profondita' | 8 livelli |
| JSON: stringa | 4096 caratteri |
| JSON: chiave | 32 caratteri ASCII |
| JSON: campi per oggetto | 32 |
| JSON: elementi per array | 8192 |
| Versioni, generazioni, gruppi | da 1 a 10^9 |
| Dispositivi nel registro | 256 (le voci non si tolgono) |
| Teste congelate per dispositivo | 64 |
| Segmenti per testa | 8192 |
| Testi da mostrare (nomi, modello) | 64 caratteri |
| Account del NAS | 64 caratteri |
| Nome del NAS | 253 caratteri |
| Chiave di una prenotazione | 256 caratteri |
| Percorso | 4096 byte UTF-8, 64 livelli |
| Album condiviso (Fase 7, A4): corpo di una voce | 4 KiB |
| Album condiviso: voci per contributo / per segmento | 20.000 / 8.192 |
| Album condiviso: titolo | 64 caratteri (§8.4) |
| Album condiviso: percorso di un elemento | 1.024 byte, relativo alla share comune, mai dentro `.mycloudgallery` (confronto NFKC) |
| Album condiviso: storia dei livelli | 32 cambi |
| Album condiviso (P2): segmento / segmenti per testa / contributo (somma dichiarata dalla testa) | 4 MiB / 64 / 32 MiB (oltre: la testa si rifiuta prima di scaricare) |
| Album condiviso (P2): posizione di un elemento | da 0 a 2^53 |
| Album condiviso (P2): ora di una voce o di una testa | al piu' 2 minuti nel futuro (oltre: il contributo aspetta); una voce nuova al piu' 2 minuti prima della testa precedente dello stesso dispositivo (oltre: retrodatata, scartata, §5.8) |
| Replica degli album (P2), per giro: cartelle in `albums/` / letture per nome delle teste / byte di segmenti | 1.024 / 4.096 / 64 MiB (il resto al giro dopo, a rotazione) |
| Replica degli album (P2), per dispositivo: voci / album nella replica | 100.000 / 512 |
| Componente di percorso | 255 byte UTF-8 |
| Nome di share | 80 caratteri |
| Configurazione: prefissi ULA / esclusioni | 4 / 256 (ogni esclusione 512 byte UTF-8) |
| Versione dell'app nello stato di un dispositivo | 32 caratteri |
| Stato di un dispositivo: pubblicazioni | al massimo una al giorno (ora del NAS), salvo cambi |
| Pubblicazione del registro: nomi letti dopo quello libero | 32 per nome, poi indizi con 32 file non validi di fila al massimo |
| Letture per nome di una serie in una sincronizzazione | 1024 |
| Nomi non validi di fila scavalcati | 32 |
| Elenco del registro (solo indizio) | 1024 nomi |
| Elenco delle richieste | 64 nomi (oltre: lettura per id) |
| Elenco delle teste di un dispositivo | 256 nomi |
| Prenotazioni: gruppi / generazioni per gruppo | 1024 / 512 nomi |
| Prenotazione | 1 ora; inizio al massimo 2 minuti nel futuro |
| Richiesta di registrazione approvabile | 15 minuti da quando l'admin la vede |
| Permanenza nel cestino | 30 giorni (ora del NAS) |
| Pulizia del cestino per giro | 200 file e 20 GiB (il primo file del giro passa comunque) |
| Immagini dall'indice | 4096 × 4096 pixel, 16384 per lato |
| Voci per segmento (`MAX_SEGMENT_ENTRIES`) | 4096 |
| Lapidi per segmento `meta` (`MAX_SEGMENT_TOMBSTONES`) | 4096 (tutte le voci) |
| Record `meta`, JSON canonico (`MAX_META_RECORD_BYTES`) | 4096 byte (4 KiB), 32 campi |
| Blob d'immagine (`MAX_BLOB_BYTES`) | 1048576 byte (1 MiB), solo JPEG o WebP |
| Corpo `frames` di un media (`MAX_FRAMES_BODY_BYTES`) | 2097152 byte (2 MiB) |
| Fotogrammi per media (`MAX_FRAMES_PER_MEDIA`) | 9 (copertina + 8) |
| `seq` di un record (`MAX_RECORD_SEQ`) | 1000000000 (10^9) |
| Record vivi per dispositivo e share (`MAX_RECORDS_PER_DEVICE_SHARE`) | 262144 |
| ThumbHash nel record, base32 (`MAX_THUMBHASH_BASE32_CHARS`) | 58 caratteri |
| Chiusura di un segmento (`SEGMENT_TARGET_BYTES`) | 12582912 byte (12 MiB) |
| Dispositivi letti per share e per giro (`MAX_INDEX_DEVICES_PER_SHARE`) | 256 |
| Letture per nome delle teste, per share e per giro (`MAX_HEAD_READS_PER_SHARE`) | 1024 |
| Teste nuove per share e tipo (`HEAD_MIN_INTERVAL_MILLIS`) | 300000 ms (una ogni 5 minuti), salvo la chiusura di un'unita' |
| Media per unita' di lavoro (`WORK_UNIT_MAX_ITEMS`) | 200 |
| Rinnovo di una prenotazione di lavoro (`LEASE_RENEW_MILLIS`) | 1200000 ms (20 minuti) |
| Teste proprie tenute dalla pulizia (`OWN_FILES_KEEP_HEADS`) | 3 |
| Grazia dei propri segmenti non elencati (`OWN_FILES_GRACE_MILLIS`) | 604800000 ms (7 giorni, ora del NAS) |
| Propri file tolti per giro (`MAX_OWN_FILES_PER_ROUND`) | 500 |
| Salto dell'ora del NAS che sospende (`NAS_CLOCK_JUMP_MILLIS`) | 300000 ms (5 minuti) |
| Ora stabile per riprendere dopo un salto (`NAS_CLOCK_STABLE_RESUME_MILLIS`) | 86400000 ms (24 ore) |
| Corpo `vec` di una voce (`MAX_VEC_BODY_BYTES`, Fase 6, A6) | 14336 byte (14 KiB): da 1 a 9 vettori da 768 componenti float16, lunghezza esatta (il corpo piu' grande, 9 vettori con gli istanti, e' di 13868 byte) |
| Vettori per corpo `vec` (`MAX_VEC_VECTORS`, Fase 6, A6) | 9 (copertina + 8 fotogrammi); esattamente 1 per le sorgenti d'immagine |
| Vettore `vec` (Fase 6, A6) | componenti tutte finite; norma ricalcolata in float32 fra 0,99 e 1,01 |
| Corpo `ocr` di una voce (`MAX_OCR_BODY_BYTES`, Fase 6, A6) | 65536 byte (64 KiB) |
| Righe per corpo `ocr` (`MAX_OCR_LINES`, Fase 6, A6) | 400 (0 = "analizzata, nessun testo") |
| Testo di una riga `ocr` (`MAX_OCR_LINE_BYTES`, Fase 6, A6) | 1024 byte UTF-8 al massimo (almeno 1) |
| Parole di una riga `ocr` (`MAX_OCR_WORDS_PER_LINE`, Fase 6, A6) | 64 al massimo (almeno 1), con inclinazione ed estremi delle parole |
| Teste `ai` (Fase 6) | come le altre teste: 8192 segmenti, 4096 voci per segmento, segmenti chiusi a 12 MiB, una testa ogni 5 minuti per share salvo la chiusura di un'unita' |
| Copia di un modello sul NAS (Fase 6) | la dimensione fissata nell'app per quel modello (oggi 164626432 e 387710976 byte), mai un byte in piu' |
| Testa dei volti (`MAX_FACES_HEAD_PAYLOAD`, Fase 8, A6) | 1048576 byte (1 MiB) |
| Segmento dei volti (`MAX_FACES_SEGMENT_BYTES`, Fase 8) | 12582912 byte (12 MiB) |
| Corpo `faces` di una voce (`MAX_FACES_BODY_BYTES`, Fase 8) | 20480 byte (20 KiB): il corpo piu' grande possibile e' di 18630 byte |
| Volti per corpo `faces` (`MAX_FACES_PER_BODY`, Fase 8) | 64 (0 = "analizzato, nessun volto") |
| Voce `cluster` (`MAX_CLUSTER_ENTRY_BYTES`, Fase 8) | 8192 byte (8 KiB) |
| Persone per istantanea (`MAX_SNAPSHOT_PERSONS`, Fase 8) | 10000 |
| Assegnazioni per istantanea (`MAX_SNAPSHOT_ASSIGNMENTS`, Fase 8) | 200000 |
| Voce `people`, JSON canonico (`MAX_PEOPLE_ENTRY_BYTES`, Fase 8) | 4096 byte (4 KiB) |
| Nome di una persona (`MAX_PERSON_NAME_CHARS`, Fase 8) | 64 caratteri (almeno 1, regole di `TextRules`) |
| Localizzatore di un volto (`MAX_FACE_LOCATOR_CHARS`, Fase 8) | 128 caratteri |
| Voci per testa dei volti (`MAX_FACES_HEAD_ENTRIES`, Fase 8) | 100000 |
| Segmenti dei volti letti per giro (`MAX_FACES_BYTES_PER_ROUND`, Fase 8) | 67108864 byte (64 MiB), il resto al giro dopo |
| Cartelle di dispositivi per generazione e per giro (`MAX_FACES_DEVICES_PER_GENERATION`, Fase 8) | 32 |
| Generazioni elencate in `faces/` per giro (`MAX_LIST_FACES_GENERATIONS`, Fase 8) | 64 |
| Vettore di un volto (Fase 8) | 128 componenti float16, tutte finite; norma ricalcolata in float32 fra 0,99 e 1,01 |

Le righe della Fase 6 e della Fase 8 con una costante tra apici inversi la nominano come sta in `TrustLimits` (P2 della Fase 6, P0
della Fase 8): `IndexSegmentTypesTest` le confronta come le altre. Ricetta (1..255), norma e inclinazione sono intervalli del codec (`AiSegments`, `AiLimits`).

Superare un tetto vuol dire rifiutare il dato (o l'elenco), contarlo in Diagnostica e proseguire con cio'
che si ha. **Mai un crash** (E-I14): ogni eccezione di un parser diventa un rifiuto.

---

## 8. Percorsi, nomi e testi letti dal NAS

### 8.1 Percorsi relativi alla share (`SharePath`)

Un percorso letto dal NAS arriva a un'operazione sui file solo come `SharePath`, e solo se e' **gia'** nella
forma sicura (nessuna normalizzazione silenziosa, cosi' due dispositivi non possono interpretarlo in due
modi). Si rifiutano:

- percorso vuoto, assoluto (`/x`), UNC (`\\server\x`), con unita' (`C:`), con `\` in qualunque posizione;
- componenti vuoti (`a//b`, `/` finale), `.` e `..`;
- oltre 4096 byte o 64 livelli; componenti oltre 255 byte;
- caratteri vietati da Windows: `\ / : * ? " < > |`; punto o spazio finale di un componente;
- nomi riservati di Windows, anche con estensione e senza distinzione di maiuscole: `CON`, `PRN`, `AUX`,
  `NUL`, `COM0`…`COM9`, `LPT0`…`LPT9`, `COM¹ ² ³`, `LPT¹ ² ³`, `CONIN$`, `CONOUT$`, `CLOCK$`;
- caratteri di controllo e invisibili (elenco esplicito, uguale su tutti i dispositivi qualunque versione di
  Unicode abbiano): C0, DEL, C1, trattino morbido, combining grapheme joiner, controlli bidirezionali
  (U+061C, U+200E, U+200F, U+202A–U+202E, U+2066–U+2069), U+200B, U+200C, U+2060–U+206F, BOM, separatori di
  riga e paragrafo, annotazioni interlineari, non-caratteri, tag invisibili, surrogati spaiati, e i caratteri
  che **sembrano vuoti** (riempitivi Hangul U+115F, U+1160, U+3164, U+FFA0; vocali khmer U+17B4, U+17B5;
  selettori mongoli U+180B–U+180F; braille vuoto U+2800; controlli musicali U+1D173–U+1D17A) e i selettori di
  variante U+FE00–U+FE0D e U+E0100–U+E01EF (revisione B2). Restano ammessi lo ZWJ (U+200D) e i selettori
  U+FE0E/U+FE0F, che servono alle emoji composte (E-N6); i nomi che differiscono solo per questi si scoprono
  con `LookAlike` (§4.5);
- **Unicode di confusione**: si ripetono i controlli sulla forma NFKC di ogni componente, cosi' `．．`,
  `／`, `＼`, `：`, `ＣＯＮ` (caratteri a larghezza piena che qualche livello potrebbe trasformare in `..`,
  separatori o nomi riservati) sono rifiutati.

I percorsi di **media** (cestino, album, voci dell'indice) in piu' non possono entrare nella cartella dell'app:
il primo componente si confronta **nella forma NFKC e senza maiuscole** con `.mycloudgallery`, quindi anche
`．mycloudgallery` (punto a larghezza piena) e' rifiutato (revisione B1). Un dato remoto non puo' indicare
come "foto da ripristinare" un file del registro.

Accenti, emoji, spazi interni, nomi lunghi fino ai tetti: ammessi.

### 8.2 Nomi nelle cartelle dell'app

Validati con la loro forma esatta (§2.4); il resto si ignora.

### 8.3 Nomi dei file locali

Cache, miniature e replica locale usano nomi **derivati da un hash** (`LocalNames`: `m` + 52 caratteri
base32 di SHA-256 delle parti, ciascuna preceduta dalla sua lunghezza), mai testo letto dal NAS.

### 8.4 Testi da mostrare

Nomi di dispositivi, modelli, (in futuro) nomi d'album: non vuoti, senza spazi ai bordi, al massimo 64
caratteri, senza i caratteri vietati del §8.1 (niente "fattura‹RLO›fdp.exe", niente nomi fatti solo di
riempitivi). Gli account del NAS: ASCII `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`, confrontati senza
maiuscole/minuscole come fa Samba. Il nome del NAS: etichette DNS (lettere, cifre, trattini non ai bordi; vale
anche un IPv4) oppure un IPv6 tra parentesi quadre.

---

## 9. Nessuna azione distruttiva da dati remoti

Regola del piano (§4, §7 contromisura 4, E-S11, E-D4): **cancellazioni definitive, pulizie e spostamenti
partono solo da azioni locali confermate**, con tetti per giro. Come la garantisce il formato:

1. **`core-trust` non puo' cancellare ne' spostare nulla**: l'interfaccia del NAS che usa ha solo `list`,
   `read` e `publishExclusive`. Un test (`TrustFormatTest`) fallisce se qualcuno aggiunge un'operazione.
   L'unica scrittura fatta "per conto di un dato letto" e' la **ripubblicazione** di una versione del
   registro gia' fidata e sparita (§4.4): crea, non cancella.
2. **I tipi letti dal NAS sono solo informativi**: registro, teste, prenotazioni, record del cestino, album
   producono viste e decisioni di fiducia, mai operazioni. L'unione di un ramo del registro e' un'azione
   esplicita dell'admin.
3. **La pulizia del cestino** (`TrashPurgePolicy`, E-D4) ha come ingressi solo:
   - la voce del **diario locale** di questo dispositivo, che esiste solo se qui l'utente ha confermato
     l'eliminazione. E' una **convenzione** del codice che la usera' (Fase 4.5): `core-trust` non offre
     alcuna funzione che costruisca la voce da un record remoto (revisione B7);
   - lo stato del file nel **proprio** cestino (`trash/<io>/files/<id>`: il percorso lo deriva la regola,
     non chi chiama);
   - l'ora del NAS.

   Si elimina solo se il file c'e' ancora con la stessa dimensione, sono passati 30 giorni (ora del NAS)
   dall'ora annotata **localmente**, e il giro non ha superato 200 file o 20 GiB (il primo file di un giro
   passa comunque, altrimenti un file da solo oltre il tetto non si eliminerebbe mai); un'ora locale nel
   futuro blocca tutto. Un record remoto, vero o finto, non compare tra gli ingressi: un "finto cestino" non
   fa eliminare nulla. Gli elementi cestinati da un dispositivo che non c'e' piu' non si eliminano mai in
   automatico.

   **Come e' fatto (4B, P12).** La pulizia e' `NasTrashService.purgeExpired` di `core-nasops`:
   - gli ingressi sono `nas_trash_entry` (voci `TRASHED` dell'account in uso), il percorso derivato da
     `TrashPurgePolicy.itemPath` (deve coincidere con quello del diario, altrimenti la voce resta) e l'ora del
     NAS; i record letti dal NAS non sono nemmeno visibili al codice della pulizia (test sul sorgente);
   - una voce citata da un'operazione non chiusa (un ripristino o un "elimina subito" in corso) non si tocca;
   - ferma con congelamento, salto dell'ora (D32, `GateStatus.CLOCK_JUMP`), emergenza o ora non ancorata;
   - **un solo handle** (`NasFileOps.deleteIfUnchanged`): apertura **esclusiva** con `DELETE` (nessun altro
     client SMB puo' aprire il file finche' l'handle e' aperto), dimensione letta con una `QUERY_INFO` su
     quell'handle, poi `FileDispositionInformation` (cancellazione alla chiusura) sullo stesso. Una cartella
     cestinata (E-D10) si svuota dal fondo: ogni file con la dimensione appena vista, poi le cartelle vuote;
     un collegamento dentro non si segue e ferma l'eliminazione (la voce resta);
   - "Elimina subito" (Q8) usa lo stesso percorso, solo per le voci del diario locale, con due conferme.

   **Campi nuovi del record (M5, §5.7)**, scritti dalla 4B: `reason` (`delete` o `move`), `folder` e, solo per
   le cartelle, `count`. Sono **obbligatori** (nessun record era mai stato scritto): un record senza `reason` o
   `folder`, con un `reason` sconosciuto, con `count` per un file o senza `count` per una cartella e' rifiutato
   (`TrashRecord.verify`, `TrashTest`). Per una cartella che non si e' potuta contare (oltre 100.000 file, un
   errore non di rete) `count` vale 0. Il record si pubblica **prima** della rinomina nel cestino, insieme alla
   voce del diario locale; senza identita' per firmare (famiglia non pronta) non si scrive e l'elemento compare
   agli altri "senza record valido". I record propri restano anche dopo la pulizia del file (innocui: un record
   senza file non si mostra).
4. **Spostamenti verso l'area comune** (album condivisi, Fase 7): partono solo dalla conferma locale
   "N foto verranno spostate…"; una definizione d'album letta dal NAS non sposta nulla. **Fase 7 (A5)**: una definizione d'album, vera
   o falsa, non sposta, non copia, non cancella e non scarica nulla; gli elementi si mostrano solo se il catalogo di chi legge vede il
   file nell'area comune (D9). Lo spostamento nasce solo da una `ShareConfirmation` (costruttore `internal`, emessa dal foglio) e dalla
   `UserConfirmation` di `core-nasops` per lo stesso piano, con il diario della condivisione scritto prima (C26);
   `Phase7ArchitectureTest` vieta a `core-albums` di vedere `core-nasops`, `core-backup` e `core-nas`.
5. **Ripristino dal cestino**: azione locale confermata, percorso validato (§8.1), mai sovrascrivendo.
6. **L'unica conseguenza distruttiva voluta di un dato remoto** e' l'auto-azzeramento di un dispositivo
   segnato `lost` in un registro firmato **e accettato**, oppure segnalato da un dispositivo approvato dello
   stesso account con una copia nella propria area privata (§5.9, decisione 3(a)): tocca solo i dati locali
   dell'app su quel dispositivo, prima le chiavi e poi i dati (`docs/famiglia-dispositivi.md` §3). Con
   l'anti-regressione (§4.4) e i kit per chiave (§4.8) non si puo' ottenere con una base vecchia o con un kit
   della chiave ruotata lasciato in giro; un registro non firmato dalla chiave attesa, un rollback,
   un'equivocazione o una regressione non diventano mai lo stato accettato, quindi non fanno azzerare (test
   `NoFalseWipeTest` di `core-family`).

6-bis. **I dati dei volti: l'altra eccezione dichiarata (Fase 8, A7; `docs/fase8-progetto.md` D29-D31).** Oltre all'auto-azzeramento,
   le sole cancellazioni guidate da un dato remoto sono di **dati dei volti**, solo sotto `faces/` dell'**area privata dell'account** e
   solo per: (a) una **generazione** che la voce `faces` firmata dell'account (§5.5-bis) dice non corrente; (b) la cartella
   `faces/<G>/<id>/` di un dispositivo **dell'account** revocato o perso nel registro accettato. Mai un media, mai un file fuori da
   `faces/`, mai nell'area comune. Le esegue solo `core-nasops` (`FacesNasEraser`) con percorsi **derivati dalla regola** (`NasLayout`,
   mai letti da un dato), con un tetto per giro (`FacesEraseLimits.MAX_FILES_PER_ROUND`, 20.000 file) e con i cancelli del §6.1
   (congelamento, salto dell'ora, sola lettura, emergenza: si aspetta e si riprende); `core-faces` non vede `core-nasops`
   (`Phase8ArchitectureTest`). Il danno peggiore di un dato falso (una finta voce `off`, una finta revoca) e' un ricalcolo dei volti
   (L99 di `docs/sicurezza.md`), mai una perdita di foto. **"Disattiva e cancella"** e' invece un'azione **locale confermata**
   (`FacesEraseConfirmation`, costruita solo dal foglio di `feature-people`): toglie l'intera `faces/<G>/` (cartelle di tutti i
   dispositivi dell'account e prenotazioni), dopo aver distrutto per prima la chiave locale (D14).

7. **Un solo modulo con operazioni distruttive (Fase 4, M10).** `core-nasops` (4B) e' l'**unico** modulo con
   operazioni distruttive sui media e sui file dell'app sul NAS (rinomina, spostamento, cestino, eliminazione con
   handle, pulizia del cestino e dei propri file), oltre ai propri temporanei di `core-nas` (`NasHousekeeping`). Le
   primitive di scrittura stanno in `core-nas/ops` dietro il contratto `NasFileOps`, che solo `core-nasops` vede.
   `core-nasops` entra **solo** da un `UserConfirmation` costruito dal foglio di conferma della UI o dal **diario
   locale** (riprese dopo un'interruzione); nessun dato letto dal NAS (record del cestino, teste, segmenti, prenotazioni,
   dati personali) crea voci del diario. Lo garantiscono: il test di architettura (`Phase4ArchitectureTest` di
   `core-common`: `NasFileOps` solo in `core-nas` e `core-nasops`; chi legge il NAS o mostra la Libreria non vede
   `core-nasops`) e, per ogni operazione, un test "dato remoto ostile → nessuna azione" (P12).

   **Come e' fatto (4B, P12).**
   - Le primitive (`SmbNasFileOps`, `core-nas/ops`) le consegna **solo** `NasFileOpsFactory` a `core-nasops`
     (`NasOpsWiring`); `NasSessionManager` non le espone (test per riflessione). Ogni modifica avviene sullo
     **stesso handle** su cui si e' letto lo stato (`SmbShare.openForChange`): timbro (dimensione, data,
     `fileId`) controllato prima della rinomina, dimensione prima della cancellazione. Nessuna primitiva di
     scrittura si ripete da sola dopo una caduta: la risposta persa la riconosce il diario.
   - Il diario nasce solo da `NasOpsEngine.start` dopo `ConfirmationGate.accept` e `PlanRules`; le voci del
     cestino solo dall'esecuzione di un elemento del diario. I soli percorsi usati dalle azioni sono quelli del
     piano confermato e quelli derivati dall'app (temporanei `tmp/<io>-op-<op>-<n>`, cestino `trash/<io>/files/<id>`,
     nome temporaneo delle maiuscole, nomi alternativi scritti nel diario). Unica lettura che diventa elementi: il
     contenuto di una cartella che l'utente sposta tra share (figli validi della cartella scelta, collegamenti
     esclusi), mostrato nel riepilogo e confermato con il piano.
   - La pulizia dei propri file (`OwnFilesCleaner`, M6) toglie solo cio' che e' proprio per **firma e posto**
     (teste e prenotazioni verificate con il registro; segmenti giudicati con le ultime 3 teste valide e il diario
     dei segmenti; temporanei propri non citati dal diario), sempre con `deleteIfUnchanged`.
   - Prove: un guasto a ogni chiamata al NAS (letture comprese) prima e dopo l'applicazione e la morte del processo
     a ogni passo per ogni macchina a stati (`EveryCallTest`, `RoomOpsStoreTest`), un NAS ostile attraversato da
     tutti gli ingressi automatici senza cambiare un byte (`NoRemoteDataToActionTest`), il finto cestino e i record
     falsi o altrui (`TrashPurgeTest`).

8. **Il backup scrive, ma non toglie (Fase 5, N3, D2).** `core-backup` e' un **secondo** modulo che scrive i media sul NAS,
   ma con poteri minori di `core-nasops`: gira da solo ogni notte, partendo da dati locali e senza una conferma per file,
   quindi deve essere **incapace** di togliere o sostituire un file per costruzione. Vede il NAS **solo** attraverso
   `NasBackupOps`, una vista in sola aggiunta di `SmbNasFileOps` (`core-nas/ops/backup/`) con un marcatore suo
   (`@AppendOnlyNasAccess`, `@RequiresOptIn` di livello errore).
   - **Cosa puo' fare**: creare le cartelle che mancano (`mkdirs`); creare e scrivere un **proprio** temporaneo
     (`openTemp`) e riaprirlo per riprendere un video grande (`reopenTemp`: solo un `-bk-` proprio, aperto senza crearlo e
     accorciato al punto di ripresa salvato nel diario); copiare lato server in un proprio temporaneo (`copyToTemp`, per il guardiano delle copie
     private); **pubblicare senza sostituire** (`publishTemp`: rinomina che fallisce se il nome esiste); togliere i
     **propri** temporanei (`deleteOwnTemp`); chiedere lo spazio (`space`). Nella 5B anche **rinominare verso e da
     `Versioni/`** (`archiveVersion`, `restoreVersion`), nella stessa share, senza sostituire e con il timbro (dimensione,
     data, `fileId`) obbligatorio e controllato sullo stesso handle prima della rinomina. Una delle due estremita' sta sempre
     sotto `Versioni/` (la cartella di destinazione si crea prima con `mkdirs`) e l'altra e' un percorso di media, mai un'area dell'app
     (`VersionPaths`, P0-B 19/10).
   - **Cosa non puo' fare**: nessuna di queste azioni toglie byte di un media (il solo file che si toglie e' un proprio
     temporaneo, mai un media). Niente cestino, niente rinomina libera, niente `deleteIfUnchanged`. `core-backup` non vede
     `NasFileOps`, `DestructiveNasAccess`, `ConfirmationIssuer` ne' `core-nasops`. Cio' che toglie un file dal NAS
     (l'originale di un video modificato, le versioni intermedie) passa dal foglio di conferma di `feature-nas` e da
     `core-nasops`, con la sua `UserConfirmation`.
   - **Ingressi**: il catalogo locale, le impostazioni del backup e le conferme della UI (nella 5B, `PhoneConfirmation`,
     costruita solo dalla UI dopo un tocco). **Mai** un dato letto dal NAS: chi legge il NAS o mostra la Libreria non vede
     `core-backup` (un dato remoto diventa un'azione del backup solo passando dal catalogo locale o dalla UI).
   - **Prove**: il compilatore (nessuno, fuori da `core-nas` e `core-backup`, passa l'argomento che permette di usare
     `NasBackupOps`); `Phase4ArchitectureTest` esteso (`core-backup` che nomina `NasFileOps` o `core-nasops`, `NasBackupOps`
     fuori da `core-nas` e `core-backup`, un modulo che legge il NAS e vede `core-backup`, `feature-backup` che vede
     `core-nas`: ognuno fallisce reintroducendo la violazione); la prova delle 500 interruzioni del backup (`docs/prova-500.md`,
     invarianti 1–11 del §5.4 di `docs/fase5-progetto.md`, piu' 12–14 nella 5B).

9. **I dati AI e le copie dei modelli non decidono nessuna azione (Fase 6, A8): questo paragrafo non cambia.** Le teste e i segmenti
   `ai` mostrano (cosa si trova cercando) e coordinano (chi calcola cosa): come l'indice, non spostano e non cancellano nessun
   file, e un dato `ai` falso o ostile costa al piu' un risultato di ricerca sbagliato (L45 di `docs/sicurezza.md`). Le copie dei
   modelli sul NAS (§2.1, §2.2) le legge chiunque, ma un dispositivo toglie **solo le proprie**, e **solo nella propria cartella**
   `devices/<io>/models/`, quando il suo catalogo non elenca piu' quel modello (mai per un dato letto dal NAS); la copia di un altro
   dispositivo non si tocca. `core-trust` resta con le tre operazioni del §2.3.

Le operazioni distruttive vere vivranno in moduli e interfacce separate (Fasi 2.4, 4.5, 5, 7), ciascuna con
il suo diario locale e i suoi test "dato remoto ostile → nessuna azione".

---

## 10. Versione del formato e compatibilita' (E-I3)

- La versione sta nel quinto byte di ogni file firmato, del kit e dei segmenti. Questa build scrive la **1**
  (`CURRENT_VERSION`) e legge l'intervallo `OLDEST_READABLE_VERSION..CURRENT_VERSION` (1..1) per tutto cio' che non e' `ai`; **dalla
  Fase 6** scrive e legge la **2** (`AI_VERSION`) solo per i file `ai`: le teste accettano 1..2 con la versione legata al tipo, i segmenti
  `vec` e `ocr` solo la 2 (vedi sotto).
- **Fase 6 (A7, F6-D6): la versione 2 esiste solo per i file `ai`.** Teste e segmenti `ai` (§2.2, §5.5, §5.5-bis) portano la
  **2**; registro, richieste, teste `index` e `personal`, prenotazioni, cestino, stato e kit restano alla **1**, e una build della
  Fase 6 li scrive e li legge come prima. Chi legge accetta **1..2**. Un'app senza la Fase 6 trova `ai/` come una cartella
  qualunque che non apre mai (non la elenca e non la legge: E-I3, E-I22), non rifiuta le altre teste e non perde niente; se per
  caso aprisse un file `ai`, una versione piu' nuova della sua e' `NEWER_FORMAT` ("aggiorna l'app"), mai un errore del dato.
- **Fase 7 (A6, D6): la versione 3 esiste solo per i file `albums/`.** Teste di tipo 6 e segmenti `album` portano la **3**; teste
  `index` e `personal` restano alla 1, i file `ai` alla 2. Chi legge accetta **1..3** con la versione legata al tipo (una testa
  d'album alla 1 o alla 2, o un file d'altro tipo alla 3, e' rifiutato). Un'app della Fase 6 non apre `albums/` (non la elenca); se
  per caso aprisse un file d'album, la 3 e' `NEWER_FORMAT`.
- **Fase 8 (A8, D12): la versione 4 esiste solo per i file `faces/`.** Teste di tipo 9 e segmenti delle classi `faces`, `cluster` e
  `people` portano la **4**; tutto il resto resta com'era. Chi legge accetta **1..4** con la versione legata al tipo (una testa dei volti
  a una versione diversa dalla 4, o un file d'altro tipo alla 4, e' rifiutato). Un'app senza la Fase 8 non apre `faces/` (non la elenca,
  E-F11) e conserva la voce `faces` dei dati personali senza capirla (un campo nuovo, non un tipo); se per caso aprisse un file dei volti,
  la 4 e' `NEWER_FORMAT`.
- Un file con versione **piu' nuova** non si interpreta: `NEWER_FORMAT` → "aggiorna l'app". Non e' un
  errore del dato e non si cancella nulla.
- **Un'app vecchia non rompe i dati di una nuova**: ogni dispositivo scrive solo i propri file; un admin con
  l'app vecchia che trova un registro di un'app piu' nuova non firma nulla (`CommitResult.NeedsAppUpdate`,
  `RegistrySync.needsAppUpdate`) e non lo scavalca; i dati che non capisce li ignora senza toccarli.
- Si aumenta la versione per **qualunque** cambiamento del contenuto (anche un campo in piu': nella stessa
  versione i campi sconosciuti sono errori). Le app nuove continuano a leggere le versioni vecchie per
  almeno tutto il periodo in cui in famiglia possono esserci app vecchie; quando la lettura di una versione
  viene tolta, se ne alza `OLDEST_READABLE_VERSION` e i dati vecchi vanno riscritti dai loro autori.
- **Finche' l'app non e' rilasciata e nessun dato e' stato scritto sul NAS, la versione 1 puo' ancora
  cambiare** (le Fasi 2.2, 4.1, 4.7 e 7 aggiungono campi alla v1; la revisione 1.4 l'ha gia' cambiata:
  firme canoniche, codice di approvazione a 80 bit, prenotazioni a gruppi, kit per chiave; la Fase 4 l'ha
  completata con M1 (classi nelle teste), M2 (contenuto dei segmenti), M5 (campi del cestino) e M7 (dati
  personali)). Dal primo dato scritto sull'area di prova in poi, ogni modifica segue le regole sopra.

---

## 11. Analisi delle minacce del formato

### 11.1 Chi ha solo accesso SMB in scrittura, senza chiavi

Un PC con un ransomware, un apparecchio della rete finche' `NAS` e `Public` sono "pubbliche" (`sicurezza.md`
§1, azione 1), una password del NAS rubata.

| Puo' | Non puo' (grazie al formato) |
|---|---|
| Cancellare, alterare o cifrare **qualunque** file a cui ha accesso, compresi i media e `.mycloudgallery/` (L2, L3). | Far accettare a un dispositivo un dato non firmato da una chiave approvata. |
| Rimettere file vecchi validi (rollback), cancellare le versioni nuove. | Far tornare indietro un dispositivo che ha gia' visto una versione nuova, o un dispositivo nuovo sotto la versione del suo QR; far sparire una versione per chi l'ha vista (la ripubblica). |
| Riempire le cartelle dell'app di spazzatura, nomi strani, file enormi, JSON profondissimi. | Nascondere una versione nuova (si legge per nome), far allocare memoria oltre i tetti, far cadere l'app, far interpretare un nome o un percorso ostile. |
| Occupare i nomi delle versioni successive del registro. | Bloccare una revoca con pochi file: si scavalcano; oltre 32 di fila l'admin riceve l'elenco esatto dei file da cancellare. |
| Fabbricare una seconda firma valida di un file (malleabilita' di ECDSA). | Farla accettare (solo firme canoniche) o farla passare per un'equivocazione (identita' sul contenuto firmato). |
| Copiare un file firmato valido in un altro posto (altra share, altro nome, altra risorsa). | Farlo valere li': ogni contenuto dichiara il suo posto e deve coincidere. |
| Scrivere richieste di registrazione con nomi rassicuranti, anche sosia. | Farle approvare: serve la scansione della chiave o il codice di 80 bit digitato dall'admin entro 15 minuti; i sosia sono segnalati. |
| Rallentare il lavoro (prenotazioni spazzatura, cartelle inondate): solo efficienza. | Far cancellare o spostare qualcosa all'app (§9). |

Effetto peggiore sul formato: **negazione del servizio** (i dispositivi restano con le repliche locali e lo
dicono in Diagnostica) e, per un dispositivo configurato **senza** QR, un registro vecchio ma autentico.

### 11.2 Un dispositivo approvato ma compromesso

Malware con i privilegi dell'app (o root) su un telefono di famiglia (S5).

| Puo' | Non puo' |
|---|---|
| Tutto quello del §11.1, nei limiti dei permessi del suo account sul NAS. In particolare **ritardare la pubblicazione della propria revoca** riempiendo i nomi del registro di spazzatura (33 file bastano) o cancellandoli, e riscrivendoli appena vengono tolti, finche' la password NAS del suo account non viene cambiata: con la scrittura nell'area comune nessun nome prevedibile si puo' proteggere, e i nomi imprevedibili andrebbero cercati nell'elenco, che si puo' inondare. Per questo, quando la pubblicazione risponde `Blocked`, la guida fa cambiare **prima** la password NAS dell'account sospetto e solo dopo cancellare i file elencati e ripubblicare. | Firmare a nome di un altro dispositivo o della famiglia; approvare dispositivi; cambiare la configurazione. |
| Finche' non e' revocato, **firmare dati falsi con la sua chiave**: voci dell'indice sbagliate (miniature, testo, vettori → ricerche ingannevoli), finti record del cestino (solo viste), finte definizioni di album (solo viste). | Far eliminare o spostare file tramite l'app degli altri (§9). |
| Tenere occupate risorse con prenotazioni (al massimo un'ora alla volta, rinnovabili): rallenta l'indicizzazione. | Influire sui dati personali degli altri account (validi solo dallo stesso account). |
| Alterare i dati personali del **suo** account. Se si conferma la decisione 3 del §13, far azzerare gli altri dispositivi del suo account (nessun file perso, solo una nuova approvazione). | Portare via la chiave dal Keystore: dopo la revoca non vale piu' nulla di cio' che ha firmato, nemmeno se retrodatato. |
| Dichiarare un `registryVersion` alto per far comparire falsi avvisi "registro indietro". | Far dimenticare a un dispositivo una revoca gia' vista, o farla annullare da un admin su una base vecchia (anti-regressione). |

Risposta: revoca nel registro (o "perso"), poi cambio della password del NAS e revoca del peer WireGuard
(nell'ordine della guida, piano 2.4); il congelamento per modifiche di massa (4.2) limita i danni ai file.

### 11.3 Dispositivo admin compromesso o chiave di famiglia rubata (S12)

Chi ha la chiave di famiglia (dispositivo admin compromesso mentre e' sbloccato, oppure foglio del codice
**e** file del kit) controlla la fiducia: approva i suoi dispositivi, revoca gli altri, cambia la
configurazione. La nuova configurazione non cambia comunque da sola l'indirizzo del NAS usato dai dispositivi
(E-R7). Se pubblica una versione che contraddice quelle viste, i dispositivi la rifiutano come ramo e
l'admin la vede (§4.4); se pubblica prima dell'admin una versione "compatibile" (che aggiunge solo i suoi
dispositivi), viene accettata (B10, §12.11). Rimedio: **reset della famiglia** (§4.10) e cambio delle
password del NAS. Per questo il kit e' diviso in due pezzi in due posti, la cassaforte si apre solo a
telefono sbloccato, e la decisione 2 del §13 propone di chiedere la credenziale del telefono a ogni uso.

### 11.4 Il NAS stesso

Il NAS (o chi ne controlla il firmware o l'account amministratore) vede e modifica tutto, e fornisce l'ora di
riferimento. Puo' fare tutto il §11.1, e spostando l'orologio puo' far scadere prima le prenotazioni (lavoro
doppio) o anticipare la pulizia del cestino, che pero' riguarda solo file che l'utente ha gia' scelto di
eliminare su quel dispositivo. Un orologio all'indietro non fa eliminare nulla.

---

## 12. Limiti dichiarati

1. **Una firma dice chi ha scritto, non che sia vero** (L3). Un dispositivo approvato puo' scrivere dati
   falsi finche' non e' revocato.
2. **Freschezza**: un dispositivo che non riesce a leggere il registro nuovo (offline, o nomi cancellati
   prima che qualcuno li ripubblichi) continua a fidarsi di un dispositivo revocato finche' non lo legge. La
   versione nel QR, la lettura per nome, la ripubblicazione e `registryVersion` nelle teste riducono il
   problema; non lo eliminano.
3. **Configurazione senza QR**: senza la versione minima, un dispositivo nuovo accetta qualunque registro
   autentico, anche vecchio, finche' non vede quello corrente. Lo stesso vale per il recupero dell'admin
   senza un QR di stato.
4. **Nessuna attestazione delle chiavi**: l'admin non ha una prova crittografica che la chiave di un
   dispositivo nuovo stia nell'hardware; si fida della prova di presenza e della persona (decisione 6).
5. **Chiavi dei dispositivi usabili senza sblocco**: un malware installato puo' far firmare la chiave finche'
   resta installato (non puo' portarla via).
6. **La chiave di famiglia e' estraibile per costruzione** (kit, secondo admin) ed e' in memoria durante le
   operazioni dell'admin; il dispositivo admin e' il punto piu' delicato.
7. **Revocare costa lavoro**: tutti i dati del dispositivo revocato si scartano e si ricalcolano.
8. **Il NAS e' fidato per l'ora** (§11.4) e chiunque scriva nelle share puo' sempre fare negazione del
   servizio, compreso ritardare la pubblicazione di una revoca finche' non gli si toglie l'accesso (§11.2:
   prima la password NAS, poi la revoca), o sostituire una versione appena pubblicata con spazzatura e
   ritardarne l'arrivo fino alla ripubblicazione dell'admin (§4.4).
9. **Riservatezza**: l'indice non e' cifrato sul NAS; lo protegge solo il permesso della share (come i media).
   Il registro, nell'area comune, mostra a tutti i dispositivi della famiglia (L11).
10. **Account dichiarato**: l'account del NAS di un dispositivo e' quello che dichiara e che l'admin conferma
    (B6). Vincolo: la prova di possesso deve esistere **prima** di qualunque potere legato all'account. Per le
    segnalazioni "perso" della decisione 3(a) c'e' (Fase 2, §5.9: copia nell'area privata dell'account); per
    l'unione dei dati personali della Fase 4.7 va ripresa la stessa idea. Vale finche' le aree private sono
    davvero private: una share privata "pubblica" (`sicurezza.md` §1) toglie la prova.
11. **Chiave di famiglia rubata**: una versione del ladro che *aggiunge* soltanto (nessuna regressione) e
    arriva prima di quelle dell'admin viene accettata senza allarme; l'admin la vede nell'elenco dei
    dispositivi. Un allarme dedicato (l'app dell'admin ricorda le impronte delle versioni che ha firmato e
    segnala quelle che non riconosce) e' rimandato alla Fase 2.3 (B10).
12. **I file nel cestino conservano i byte dei media** (§2.1, I6).
13. **La protezione della cassaforte a telefono bloccato** e' verificata solo come politica della chiave
    (non bloccando davvero il telefono nei test), e non protegge dal malware che agisce a telefono sbloccato
    (decisione 2).

---

## 13. Decisioni che spettano all'utente

| # | Decisione | Opzioni (la prima e' quella consigliata e gia' scritta nel codice, salvo dove indicato) |
|---|---|---|
| 1 | Dove tenere il file cifrato del kit | **(a)** area privata dell'admin sul NAS + foglio con codice, id e impronta: servono entrambi. (b) In piu', il file stampato come QR su un secondo foglio tenuto altrove: sopravvive alla perdita dell'area privata, ma chi trova i due fogli insieme ha la chiave. (c) File e codice sullo stesso foglio in cassaforte: un solo pezzo da proteggere. |
| 2 | Credenziale del telefono per usare la chiave di famiglia | **(a)** impronta/PIN del telefono a ogni operazione dell'admin (approvare, revocare, ruotare, unire): le operazioni sono sempre interattive; si realizza in Fase 2.2 con la cassaforte che chiede l'autenticazione. (b) Nessuna richiesta oltre al telefono sbloccato con blocco schermo sicuro obbligatorio (come il codice di oggi, dopo la revisione M3 e la sua verifica). |
| 3 | Segnalazione "perso" fatta dal proprietario senza l'admin | **(a)** come dice il piano ("l'admin o il proprietario"): un altro dispositivo dello stesso account puo' far azzerare il dispositivo perso appena si ricollega; un dispositivo compromesso dello stesso account potrebbe pero' far azzerare i suoi "fratelli" (nessun file perso, nuova approvazione). (b) Solo l'admin. |
| 4 | Dati di un dispositivo revocato | **(a)** tutti scartati e ricalcolati; con la *dismissione* si congelano le teste gia' verificate. (b) Tenere quelli "firmati prima della revoca": sconsigliato, la data la scrive il dispositivo stesso. |
| 5 | Durata massima di una prenotazione | **(a)** 1 ora con rinnovo. (b) Piu' corta (15 min: meno attesa se un dispositivo muore, piu' scritture) o piu' lunga. |
| 6 | Approvazione di un dispositivo | **(a)** scansione della chiave quando si e' insieme, altrimenti codice di 16 caratteri digitato entro 15 minuti. (b) Aggiungere l'attestazione hardware del Keystore come requisito (piu' codice, nessun dispositivo di famiglia ne sarebbe escluso). |
| 7 | Runner del test sul dispositivo | **(a)** aggiungere `androidx.test:runner` a `versions-app.gradle` (standard; tocca file fuori da questo modulo). (b) Tenere il runner minimo di `core-trust` (oggi; esegue l'elenco `TEST_CLASSES` e rispetta `-e class`). |

**Applicate per default nella Fase 2 (P3)**, finche' l'utente non conferma: 1(a), 3(a) **con la prova di
possesso dell'account** (§5.9), 4(a), 5(a). La 2 **non** e' applicata: la cassaforte chiede il telefono sbloccato
con blocco schermo sicuro, non la credenziale a ogni uso (residuo M3, `docs/review/1.4-core-trust.md`).

Nota per `docs/sicurezza.md` (fuori dal perimetro di questa fase): C7, C8 (codice), C9, C10 (lato formato)
e C23 hanno ora codice e test; L11 e i limiti del §12 vanno riportati li' alla chiusura della Fase 1.

---

## 14. Mappa: specifica → codice → test

| Sezione | Codice (`core-trust/src/main/kotlin/.../core/trust/`) | Test |
|---|---|---|
| §2.3, §9 | `nas/NasFileSystem.kt`, `nas/NasLayout.kt` | `TrustFormatTest` (nessuna operazione distruttiva, nessun orologio del dispositivo) |
| §3.1 | `codec/Base32.kt` | `Base32AndTextTest` |
| §3.2 | `codec/CanonicalJson.kt`, `codec/Fields.kt` | `CanonicalJsonTest` |
| §4.1–4.3 | `crypto/Keys.kt` (`EcdsaDer`, `P256`), `crypto/FamilyKey.kt`, `crypto/AndroidKeystore.kt` | `EnvelopeTest`, `ReviewFixesTest` (A3, B3); sul dispositivo `KeystoreDeviceTest` (androidTest) |
| §4.4–4.7, §4.10 | `registry/*.kt` | `RegistryTest`, `ReviewFixesTest` (A1, A2, M1, M2, B4, B5, I9), `ReviewRound2Test` (N1–N5, M7), `ReviewRound3Test` (N2, R2, R3, R4), `Phase2FormatTest` (rifondazione) |
| §5.3 (configurazione) | `registry/FamilyRegistry.kt` (`FamilyConfig`, `ConfigDefaults`), `registry/ConfigRules.kt`, `codec/TextRules.kt` (`ipLiteral`) | `Phase2FormatTest` |
| §5.9 | `registry/LostReport.kt` (`LostReport`, `LostReports`) | `Phase2FormatTest`; in `core-family`: `NoFalseWipeTest`, `LifecycleTest` |
| §5.10 | `data/DeviceStatusReport.kt` (`DeviceStatusReport`, `DeviceStatusVerifier`, `DeviceStatusSeries`) | `Phase2FormatTest`; in `core-family`: `StatusAndDiagnosticsTest` |
| §4.8–4.9 | `crypto/RecoveryKit.kt` (`RecoveryKitFiles`) | `RecoveryKitTest`, `ReviewFixesTest` (M4, M7), `ReviewRound2Test` (M4, M3: cassaforte) |
| §5.1–5.2 | `envelope/Envelope.kt`, `registry/DeviceTrust.kt` | `EnvelopeTest`, `DeviceHeadTest`, `ReviewFixesTest` (A3) |
| §5.5 | `data/DeviceHead.kt` | `DeviceHeadTest` |
| §5.6, §6 | `data/Leases.kt` | `LeaseTest`, `ReviewFixesTest` (M6) |
| §5.7, §9 | `data/Trash.kt` | `TrashTest`, `ReviewFixesTest` (B7) |
| §7 | `TrustFormat.kt` (`TrustLimits`) | in tutti i test sopra |
| §8 | `nas/SharePath.kt`, `codec/TextRules.kt`, `nas/NasLayout.kt` (`LocalNames`, `ImageBounds`), `registry/Registration.kt` (`LookAlike`) | `SharePathTest`, `Base32AndTextTest`, `ReviewFixesTest` (B1, B2, I7) |
| §5.5 (M1, M3, M7), §5.5-bis (M2) | `data/IndexSegment.kt` (tipi, P0: `SegmentClass`, `ClassedSegment`, `MetaFile`, `MetaRecord`, `SegmentContent`; codec `IndexSegments` e `MetaRecords`, P3), `data/DeviceHead.kt` (teste con le classi, P3), `data/HeadSeries.kt` (lettura per nome, P3); `ContentKey` in `core-model` | `IndexSegmentTypesTest` (P0: classi, invarianti, §7 contro `TrustLimits`); P3: `IndexSegmentCodecTest`, `HostileSegmentTest`, `HeadSeriesTest` |
| §2.3 (M6) | `data/OwnFiles.kt` (`OwnFilesPolicy`, P3; esecuzione in `core-nasops`, 4B) | P3: `OwnFilesPolicyTest` |
| §5.6 (M4) | `data/Leases.kt`; chiave delle unita' in `core-index` (`WorkUnitKey`) | `LeaseTest`; `IndexContractTest` (P0) |
| §5.7 (M5) | `data/Trash.kt` (campi nuovi nella 4B, P12) | `TrashTest` |
| §6.1 (M8) | `TrustLimits.NAS_CLOCK_JUMP_MILLIS`; `SessionNasClock` in `core-nas` | `IndexSegmentTypesTest` (§7); in `core-nas` i test dell'orologio |
| §9 (M10) | `core-nasops` (4B), `core-nas/ops` (`NasFileOps`, 4B) | `Phase4ArchitectureTest` (`core-common`, P0); P12: test "dato remoto ostile" |
| §2.2, §5.5 (testa `ai`), §5.5-bis (`vec`, `ocr`, attestazione e fusione), §5.6 (unita' `ai`), §7, §10 (Fase 6, **da scrivere**) | `data/AiSegments.kt` (P2, da scrivere; piu' le righe minime in `data/IndexSegment.kt`, `data/DeviceHead.kt`, `nas/NasLayout.kt`, `TrustFormat.kt`); fusione e pubblicazione in `core-index/.../ai/` | P2: test ostili per ogni campo dei corpi, un lettore della versione 1 che ignora `ai/`, attestazione, fusione, proprieta' M3; `IndexSegmentTypesTest` per i tetti nominati |
| §2.2, §4.6 (A7), §5.8, §7, §10 (Fase 7, P2: album condivisi) | `data/AlbumDocs.kt` (`AlbumLimits`, `AlbumIds`, `AlbumField`, `AlbumKey`, `AlbumValue`, `AlbumEntry`, `AlbumHead`, codec `AlbumDocs`), righe minime in `TrustFormat.kt` (`ALBUM_VERSION`), `envelope/Envelope.kt`, `nas/NasLayout.kt` (`albumHead`, `albumSegment`), `data/IndexSegment.kt` (classe 17); serie, replica, fusione, permessi e pubblicatore in `core-albums/.../shared/` | `AlbumHeadTest`, `AlbumHostileTest`, `AlbumSegmentHostileTest`, `IndexSegmentTypesTest`; in `core-albums`: `AlbumPermissionsLogicTest`, `AlbumFusionPropertyTest`, `SharedAlbumSyncTest`, `SharedAlbumIsolationTest`, `NasAlbumPublisherTest`, `SharedAlbumBenchTest`, `Real*ContractTest` |
| §2.2, §2.3, §4.6, §5.5-bis (`faces`, `cluster`, `people`, campo `faces`), §5.6 (prenotazioni dei volti), §5.11, §7, §9 punto 6-bis, §10 (Fase 8, P0: firme; il codec e' di P3) | `TrustFormat.kt` (`FACES_VERSION`, tetti dei volti in `TrustLimits`), `envelope/Envelope.kt` (`DocType.FACES_HEAD`), `data/IndexSegment.kt` (classi 18-20, `isFaces`, `FACES_CLASSES`), `nas/NasLayout.kt` (`faces*`); `core-model` (`FaceGeneration`, `PersonId`, `FaceLocator`, `FaceRef`, `FacesSetting`); `core-personal` (`FacesSettingCodec`); `core-nasops` (`FacesNasEraser`, contratto); codec `FacesDocs` (P3) | `FacesSignaturesTest`, `IndexSegmentTypesTest`, `Phase8TypesTest`, `Phase8FacesContractTest`, `FacesNasEraserContract`, `FacesRemoteContract`, `Phase8ArchitectureTest` |

I test JVM usano un NAS finto volutamente scomodo: elenchi in ordine casuale e nomi senza distinzione di
maiuscole (I3).

Comandi: `gradlew.bat :core-trust:testDebugUnitTest :core-trust:lintDebug`; sul telefono
`set ANDROID_SERIAL=<seriale>` e `gradlew.bat :core-trust:connectedDebugAndroidTest`.
