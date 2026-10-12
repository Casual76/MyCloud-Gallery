# Sicurezza di MyCloud Gallery: threat model e baseline

| | |
|---|---|
| **Stato** | Bozza della Fase 0.6, 12/10/2026. Si aggiorna alla chiusura di ogni fase (§7). |
| **Come leggerlo** | Il §1 è per tutta la famiglia, senza tecnicismi. Dal §2 in poi è per chi sviluppa e per chi vuole verificare. |
| **Cosa è già vero** | Siamo in Fase 0 e l'app non esiste ancora. Le contromisure del §4 sono impegni di progetto: diventano fatti solo quando la fase indicata è chiusa e la verifica è passata. Sono già misurati solo i fatti del §5. |
| **Sigle** | `S1`…`S12` = scenari del §3. `C1`…`C23` = contromisure del §4. `L1`…`L17` = limiti del §6. `E-xx` = casi limite del piano. `0.1`…`10.5` = sottofasi del piano. `T-xx` = prove elencate in `android.md`. Il segno † indica una verifica aggiunta da questo documento, da inserire nel piano. |

---

## 1. In breve, per chi non è tecnico

**Che cosa protegge.** Le foto e i video della famiglia, sul NAS di casa e sui telefoni; le password per entrarci; le informazioni che l'app ricava dalle foto (testo, luoghi e, solo se attivati, i volti, che sono dati biometrici); la privacy di ciascun familiare verso gli altri.

**Da chi.** Da chi trova o ruba un telefono o il tablet; da un apparecchio "smart" o un ospite sulla rete di casa (oggi decine di dispositivi condividono la rete con il NAS); da una rete sconosciuta, come un albergo; da un telefono di famiglia infettato da un virus; e da errori umani e guasti.

**Che cosa fa l'app.**
- Il NAS non è raggiungibile da Internet. Fuori casa si entra solo con la VPN WireGuard del FRITZ!Box (un tunnel cifrato verso casa). L'app parla con il NAS solo dopo aver riconosciuto la rete di casa o la VPN: su una rete sconosciuta non invia nemmeno la password.
- Il collegamento al NAS è sempre cifrato. Se non può esserlo, l'app non si collega.
- Le password stanno nella cassaforte hardware del telefono (Keystore), mai nei file di registro, nei backup o nei QR. Si può aggiungere il blocco con impronta o PIN del telefono.
- Niente cloud di terzi e niente statistiche d'uso: ricerca e riconoscimento avvengono sul dispositivo. Un dubbio su una libreria di Google è ancora da chiarire (§8).
- Ogni telefono o tablet nuovo deve essere approvato da chi amministra, e tutto ciò che l'app scrive sul NAS è firmato: un dispositivo non approvato non può ingannare gli altri.
- L'app non cancella mai da sola in modo definitivo: ogni rimozione passa da un cestino di 30 giorni. "Libera spazio" parte solo su richiesta, dopo aver ricontrollato che sul NAS esista una copia integra.
- Un dispositivo perso si segnala: appena si ricollega, azzera chiavi e dati dell'app.

**Che cosa NON può fare.**
- Non è un backup. I due dischi del NAS si copiano a vicenda (RAID 1): un file cancellato o cifrato da un virus lo è su entrambi. Oggi non esiste una copia esterna.
- Non può chiudere le falle della rete di casa: `NAS` e `Public` sono impostate come "pubbliche" e restano aperte finché non si cambia l'impostazione sul NAS.
- Non ferma un virus che ha già la password di un familiare, né una cancellazione fatta dal PC.
- Su un dispositivo perso l'azzeramento funziona solo se si ricollega: la VPN va revocata a mano dal FRITZ!Box e la password del NAS cambiata.
- Fuori casa funziona dove c'è IPv6: di norma sulla rete mobile sì, in molti alberghi no.

**Le 5 azioni consigliate alla famiglia** (rete di casa, per rischio decrescente):

| # | Azione | Perché | Livello | Stato |
|---|---|---|---|---|
| 1 | Togliere "Pubblico" a `NAS` e `Public`: accesso solo con gli account di famiglia. Prima controllare cosa le usa senza password (TV con Plex/DLNA, stampante, altri PC). | Oggi, con ogni probabilità, qualunque dispositivo in rete potrebbe leggere e cancellare 266 GB di dati. | Critico | Da fare |
| 2 | Copia esterna: un disco USB sul NAS con l'app WD "USB Backups", poi staccato e tenuto altrove, a rotazione. | Unica difesa da cancellazione, virus, guasto e furto. Oggi non è previsto, per scelta. | Alto | Da fare |
| 3 | Wi-Fi: WPA2+WPA3 e WPS spento. | Rende più difficile entrare in rete da fuori. | Medio | Da fare |
| 4 | Apparecchi "smart" nella rete ospiti del FRITZ!Box (oggi spenta). | Li separa dal NAS; quasi tutti funzionano con la sola Internet. | Medio | Da fare |
| 5 | Spegnere l'accesso cloud WD (oggi attivo). | L'app non lo usa ed è un percorso in più verso il NAS. | Basso | Da fare |

L'ordine segue il rischio per questa famiglia (§3). La copia esterna è seconda, e non ultima come nell'inventario, perché è l'unica difesa dalla perdita totale dei dati.

---

## 2. Beni da proteggere

| Bene | Perché conta | Dove sta | Minacce |
|---|---|---|---|
| Foto e video della famiglia | Spesso irripetibili. Il NAS è l'unico archivio e non ha copie esterne. | NAS (aree private e area comune), telefoni | S2, S3, S5, S6, S9, S10 |
| Integrità e disponibilità dei file | Nessun file perso, sovrascritto o lasciato a metà; accesso quando serve. | NAS e flussi di copia dell'app | S5, S6, S9, S10 |
| Credenziali del NAS | Danno accesso a tutto ciò che l'utente vede. L'account admin non si usa mai nell'app. | Keystore del dispositivo; password personali dei familiari | S1, S4, S5 |
| Varco VPN (configurazione e QR di WireGuard) | Equivale a una password: un peer raggiunge tutta la rete di casa (L6). | App WireGuard sul dispositivo | S1, S12 |
| Dati derivati: miniature, anteprime, vettori di ricerca, testo letto, luoghi, "casa" dedotta | Rivelano contenuti, abitudini e dove si abita. | Indice nella share del NAS e replica locale su ogni dispositivo | S1, S11 |
| Volti e persone (dati biometrici) | Categoria di dati più delicata (L14). | Area privata di chi attiva la funzione; locale | S1, S11 |
| Posizione GPS nelle foto | Rivela luoghi e abitudini. Resta nelle condivisioni verso altre app salvo impostazione. | Dentro i file | S11, S1 |
| Privacy tra familiari | Ognuno ha un'area privata; l'area comune è di tutti. | Permessi del NAS e regole dell'app | S11 |
| Fiducia tra i dispositivi | Se cade, un dispositivo ostile può far accettare dati falsi. | Chiave di famiglia (admin), registro firmato sul NAS, kit di recupero offline | S5, S12 |
| Catena di rilascio | Chi la controlla può far installare codice: chiave di firma dell'app, account di pubblicazione, modelli AI, manifest remoto. | Repository privato, keystore di rilascio, store proprio | S7 |

---

## 3. Attaccanti e scenari

Probabilità e impatto sono giudizi qualitativi degli autori, da rivedere con la famiglia. Sono valutati **sullo stato di oggi (§5), prima delle contromisure dell'app**. "Residuo, solo app" è il rischio che resta quando l'app ha realizzato il §4. "Residuo, con le 5 azioni" è quello che resta se si fanno anche le azioni del §1.

### 3.1 Quadro

| ID | Scenario | Probabilità | Impatto | Residuo, solo app | Residuo, con le 5 azioni |
|---|---|---|---|---|---|
| S1 | Ladro o chi trova un telefono o il tablet | Media | Alto | Medio | Medio |
| S2 | Dispositivo "smart" compromesso, o ospite, sulla rete di casa | Media | Alto | Alto | Basso |
| S3 | Intruso nel Wi-Fi di casa | Bassa | Alto | Alto | Basso |
| S4 | Rete estranea (albergo, amici) con host ostile | Media | Medio | Basso | Basso |
| S5 | Telefono o tablet di un familiare con malware | Bassa | Alto | Alto | Medio |
| S6 | Ransomware (su un PC di casa o su un dispositivo con accesso al NAS) | Bassa | Alto | Alto | Medio |
| S7 | Manomissione di modelli, aggiornamenti, librerie | Bassa | Alto | Basso | Basso |
| S8 | Attacco da Internet a FRITZ!Box, NAS o VPN | Bassa | Alto | Medio | Basso |
| S9 | Errore umano: cancellazioni, spostamenti, password | Alta | Medio | Medio | Basso |
| S10 | Guasto dei dischi, furto o incendio del NAS | Bassa | Alto | Alto | Medio |
| S11 | Privacy tra familiari | Media | Medio | Medio | Medio |
| S12 | Compromissione dell'admin o della chiave di famiglia | Bassa | Alto | Medio | Medio |

### 3.2 Perché, per questa famiglia

- **S1.** Il telefono esce di casa; il tablet resta in casa ma è condiviso. Chi lo ha in mano sbloccato, o ha visto il PIN, vede la libreria e può usare le credenziali già salvate: restano utilizzabili senza sblocco perché il backup notturno lo richiede (L4). Se sul dispositivo c'è la VPN WireGuard, ha un varco verso tutta la rete di casa fino alla revoca del peer. Un dispositivo bloccato è invece protetto dalla cifratura di Android.
- **S2.** Oggi decine di dispositivi, molti "smart", stanno nella stessa rete del NAS, il Wi-Fi permette ai dispositivi di parlarsi, la rete ospiti è spenta, `NAS` e `Public` sono "pubbliche" (con ogni probabilità senza password) e un account di prova ha scrittura su entrambe. Un apparecchio compromesso potrebbe leggere o cancellare la cartella comune senza passare dall'app. L'app non può rimediare: lo fanno le azioni 1 e 4 del §1.
- **S3.** WPA2 con WPS acceso è il punto più debole del Wi-Fi. Chi entra si trova nella situazione di S2.
- **S4.** La famiglia viaggia e usa Wi-Fi altrui. Molti FRITZ!Box hanno la stessa rete di fabbrica della nostra: per questo gateway e indirizzi, da soli, non bastano a riconoscere casa (E-S10). Un finto NAS potrebbe ottenere una risposta di password craccabile offline (L7).
- **S5.** Il dispositivo ha le credenziali e l'app non distingue l'uso legittimo da quello del malware. Il danno è limitato a ciò che quell'utente può fare sul NAS: la sua area privata e l'area comune. Firme e approvazioni evitano che gli altri dispositivi si fidino di dati falsi, non che dei file vengano cancellati (L3).
- **S6.** I PC con le cartelle del NAS collegate sono il bersaglio tipico. Sul NAS il cestino è spento sulle aree private e su `Public`: una cancellazione dal PC è definitiva, e il RAID 1 replica il danno. L'app può solo accorgersene (modifiche di massa), fermare le proprie scritture e rifiutarsi di liberare spazio sul telefono se la copia sul NAS non coincide.
- **S7.** L'app dipende da librerie di terzi (tra cui quella SMB, L9), da modelli AI scaricati e da aggiornamenti. Il punto più sensibile è la chiave di firma dell'app: chi la possiede può far installare una falsa versione.
- **S8.** Nessun IPv4 pubblico (CGNAT), NAS senza IPv6, FRITZ!Box senza inoltri di porte. Quando si attiverà WireGuard su IPv6 si aprirà una porta UDP del FRITZ!Box verso Internet; WireGuard non risponde a chi non ha una chiave valida. Restano l'accesso cloud WD, un percorso in più, e il firmware del NAS da tenere aggiornato.
- **S9.** Succede prima o poi: cancellare la cartella sbagliata, liberare spazio senza accorgersi che la copia sul NAS non c'è, far bloccare un account con tentativi di password automatici. Le contromisure sono di processo (conferme, cestini, verifiche), ma sul NAS il cestino delle aree private è spento.
- **S10.** Il RAID 1 protegge dal guasto di un disco, non dalla perdita dell'apparecchio. Non c'è alcuna copia fuori dal NAS e l'app non può farla.
- **S11.** L'indice sta nella share con gli stessi permessi, i volti nell'area privata di chi li attiva, ma l'area comune la vedono tutti. "Nascosti" nasconde solo dentro l'app (L2). Il registro dei dispositivi mostra a tutti quali dispositivi esistono (L11). La famiglia è di soli adulti: il rischio è più curiosità ed errore che abuso.
- **S12.** L'admin approva i dispositivi e firma la configurazione. Se il suo dispositivo è compromesso, o il kit di recupero finisce in mani sbagliate, la fiducia cade. Se lo perde senza kit serve il reset guidato della famiglia.

---

## 4. Contromisure dell'app

Ogni riga: minaccia, contromisura, dove si realizza nel piano, come si verifica. Fino alla chiusura della fase indicata la contromisura è un impegno, non un fatto.

| ID | Minacce | Contromisura | Dove nel piano | Come si verifica |
|---|---|---|---|---|
| **C1** | S8, S2 | NAS mai esposto a Internet: solo LAN e WireGuard del FRITZ!Box, un peer per dispositivo o profilo. Nessun inoltro di porte, nessun UPnP verso il NAS, accesso remoto spento, nessuna credenziale del FRITZ!Box nell'app. | 0.1, 2.5, 2.6 | Controllo manuale di FRITZ!Box e NAS (0.1). † Prova da rete mobile: nessuna porta della LAN risponde, tranne la UDP di WireGuard. |
| **C2** | S4, S2, S3 | SMB solo sulla rete di casa riconosciuta o dentro la VPN. Riconoscimento con più indizi (prefisso IPv6 locale del FRITZ!Box, gateway, dominio, identità del NAS) e nessuna autenticazione altrove. Socket legato alla rete scelta. | 2.1; E-S10 | "Fatto quando" di 2.1: su una rete estranea con un host allo stesso IP non parte nessuna autenticazione. Ripetuto in 10.4. |
| **C3** | S2, S3, S4 | SMB solo 3.1.1, firma richiesta, cifratura sempre attiva (AES-128-GCM). A ogni sessione si controllano dialetto, cifratura, assenza di guest e anonimo; altrimenti la connessione si chiude. | Decisioni 0.2; 1.4; 2.1 | Test sulla configurazione e contro un NAS finto che non cifra. Diagnostica mostra "cifrata: sì/no". Misura del 12/10 già positiva (§5.5). |
| **C4** | S4 | Indirizzo del NAS mai cambiato in automatico: si propone il nuovo e si chiede conferma, verificando la configurazione firmata. | 2.1; E-R7 | Test con cambio di indirizzo simulato. |
| **C5** | S2, S3, S9 | Interruttore di sicurezza al primo errore di autenticazione, nessun nuovo tentativo automatico. Account admin del NAS mai usato nell'app. Password lunghe per ogni familiare. | 0.1, 2.1; E-R8, E-S12 | Test: con password errata il NAS riceve un solo tentativo, anche dai lavori in background. |
| **C6** | S1, S12 | Guida alla VPN: il QR di WireGuard va trattato come una password; il permesso di controllo di WireGuard è spiegato; ordine "prima segnalare, poi revocare". | 2.2 (passo 11), 2.3, 2.4, 2.5; E-S15 | Revisione della guida. † Prova di revoca di un peer di prova. |
| **C7** | S5, S12 | Chiave di famiglia dell'admin e chiave per dispositivo nel Keystore. Ogni dispositivo è approvato dall'admin; registro firmato; dati di dispositivi non approvati o revocati ignorati; fino all'approvazione solo lettura. | 1.4, 2.3, 2.4; E-I15, E-S14 | Test che rifiutano dati non firmati o di dispositivi revocati (1.4). Un account di prova non approvato non fa accettare dati (2.3). |
| **C8** | S12 | Kit di recupero della chiave (da stampare o conservare offline) e secondo dispositivo admin. Il QR dell'app porta l'impronta della chiave di famiglia e mai password; un QR di un'altra famiglia è rifiutato. | 2.2 (passo 12), 2.3; E-S13, E-O5, E-S8 | Recupero dell'admin con il kit provato sull'area di prova (2.4). |
| **C9** | S5, S2, S3 | Lettura difensiva di tutto ciò che arriva dal NAS: limiti di dimensione per file, record e campo; percorsi relativi normalizzati (no `..`, assoluti, UNC); nomi locali derivati da hash; immagini con tetto di pixel. | 1.4; E-I14 | Test con segmenti ostili (1.4) e prova pratica (10.4). |
| **C10** | S5, S2, S6 | Nessuna azione distruttiva da dati remoti: cancellazioni definitive, pulizie e spostamenti verso l'area comune partono solo da azioni locali confermate. Pulizia del cestino con record firmato, ora del NAS e tetto per giro. | Piano §7; 7.4; E-S11, E-D4 | Test: un finto cestino o un finto album sul NAS non provoca alcuna azione. |
| **C11** | S6 | Rilevamento di cambiamenti anomali di massa: si congelano scritture automatiche, pulizie e compattazione e si avvisa l'utente. | 4.2; 10.4 | Simulazione di modifiche di massa che fa scattare il congelamento. |
| **C12** | S5, S6, S9, S10 | Integrità: caricamento con nome temporaneo, hash completo, poi nome definitivo; mai sovrascrivere; versioni conservate; "al sicuro" solo con una copia privata verificata; "Libera spazio" su richiesta, con verifica dell'hash sul momento e file nel cestino Android per 30 giorni. | 5.2, 5.3, 5.4, 5.5; E-B3, E-B8, E-B14 | Prova delle 500 interruzioni (piano §4). Simulazione di copia corrotta o mancante (5.5). |
| **C13** | S9, S10 | Avviso permanente sull'assenza di backup esterno e avviso forte prima di "Libera spazio". Conferme con riepilogo e conteggio. Cestino del NAS di 30 giorni. | 2.2 (passo 13), 4.5, 5.5; E-D7 | Revisione degli screenshot; prove sull'area di prova. |
| **C14** | S1 | Credenziali cifrate con chiave hardware (StrongBox dove c'è). Blocco app opzionale con la credenziale di sistema. `FLAG_SECURE` su Nascosti, password e QR; anteprima nei recenti oscurata. Avviso se il telefono non ha blocco schermo. L'app non disattiva il Riavvio per inattività di One UI, che è una difesa. | 1.5; E-S3, E-S4, E-S5, E-S9 | Checklist di Fase 1 (§7). Ispezione di manifest e APK di release. |
| **C15** | S1 | Dispositivo perso: segnalazione firmata. Alla riconnessione il dispositivo distrugge prima la chiave, poi credenziali, replica, miniature e cache. La guida indica di segnalare, poi revocare il peer WireGuard e cambiare la password del NAS, e rimanda a Samsung Find. | 2.4; E-S1, E-S2 | Segnare un dispositivo di prova come perso e controllare l'azzeramento (2.4, 10.4). |
| **C16** | S1, S5 | Backup Android e trasferimento da dispositivo a dispositivo esclusi (`allowBackup=false` più esclusione esplicita in `<device-transfer>`). Un telefono nuovo ha nuova chiave e nuova approvazione. | 1.5; E-O7, E-O8; T-25 | Prova con Smart Switch tra tablet e telefono (T-25). |
| **C17** | S5 | Componenti non esportati salvo launcher e integrazioni volute. FileProvider ristretto, con permessi revocati dopo l'uso. PendingIntent immutabili. Nessun deep link che esegue azioni. Protezione da sovrapposizioni, tastiera senza apprendimento e appunti marcati sensibili sui campi password. | 1.5 | Ispezione di manifest e APK di release (1.5). |
| **C18** | S5, S2 | Contenuti in arrivo da altre app trattati come non fidati: limiti di dimensione, tipo verificato dai byte, rifiuto di URI che puntano a file dell'app stessa, flussi copiati subito. GPS mantenuto per scelta nelle condivisioni, rimovibile da impostazione con indicatore. | 3.4, 5.7; E-X2, E-X5, E-S6 | Un file condiviso con "rimuovi posizione" non contiene GPS (E-X5). |
| **C19** | S11 | Indice dentro la share, quindi con gli stessi permessi. Dati personali e volti nell'area privata di ciascuno. "Casa" dedotta solo in locale. Spostamenti nell'area comune solo con consenso esplicito e conteggio. Ogni profilo Android del tablet è un dispositivo a sé. | 2.8, 4.1, 4.7, 7.4; proposta 13 | Prova 10.4: un account non legge dati privati altrui tramite l'indice. Due profili del tablet isolati (2.8). |
| **C20** | S11, S1 | Volti: attivazione per account, "Disattiva e cancella" che non lascia tracce su NAS e dispositivi, nessun dato esportato. † Nessuna inferenza di età, genere, etnia o emozioni. † Dati dei volti cifrati a riposo con chiave nel Keystore. | 8.1; E-F4, E-F6 | Verifica su NAS e dispositivo dopo la cancellazione (8.1). |
| **C21** | tutte (privacy) | Nessuna telemetria né crash reporting esterno. Nessun SDK che invii dati. Diagnostica locale con esportazione manuale, senza dati personali e con anteprima. Ricerche e cronologia solo locali. | 1.5, 2.7, 6.4; proposta 9 | † Cattura del traffico dell'app durante OCR e indicizzazione (0.4, ML Kit). Nessun traffico durante le ricerche (6.4). |
| **C22** | S7 | Dipendenze minime con checksum verificato. Modelli con hash fissato nell'app. Manifest remoto firmato con chiave fissata, solo HTTPS da host ammessi. Aggiornamenti solo con la stessa firma. Chiave di firma fuori dal repository con copia offline. Account di pubblicazione con 2FA. Release non debuggable e log delle librerie silenziati. | 1.1, 6.1, 10.5; E-A2, E-S7 | Un modello alterato di un byte è rifiutato (6.1). Un manifest manomesso è ignorato. Ispezione dell'APK. |
| **C23** | S5, S9 | Ora di riferimento = ora del NAS, non quella del dispositivo. Prenotazioni con durata massima; oltre il tetto sono ignorate. | 1.4; E-P9, E-I16 | Test in 1.4 con orologio del dispositivo sbagliato e prenotazioni assurde. |

### 4-bis. Permessi Android sensibili

| Permesso | Perché serve | Quando si chiede | Note |
|---|---|---|---|
| Lettura di foto e video (anche "foto selezionate") | Libreria del telefono e backup | Onboarding, passo 9 | Con "foto selezionate" l'app lavora in modalità limitata e non deduce cancellazioni (E-P1). Lo stato si rilegge ogni volta. |
| Accesso alla posizione dei media (`ACCESS_MEDIA_LOCATION`) | Copia fedele sul NAS: senza, il GPS viene oscurato e l'hash non coincide | Onboarding, passo 9 | Riguarda i metadati delle foto, non la posizione del telefono. |
| Rete locale (`ACCESS_LOCAL_NETWORK` da target 37; `NEARBY_WIFI_DEVICES`) | Parlare con il NAS (SMB) e Wake-on-LAN | Passo "Accesso alla rete locale" | Con target 36 su Android 17 è concesso implicitamente; la concessione è dichiarata temporanea. |
| Controllo di WireGuard (`CONTROL_TUNNELS`) | Spegnere la VPN in casa e riaccenderla fuori | Onboarding, passo 11 | Facoltativo. Permesso "pericoloso" di un'altra app: chi lo ha può accendere o spegnere qualsiasi tunnel. Va spiegato. |
| Batteria "Illimitata" | Lavori notturni lunghi | Onboarding, passo 10 | Facoltativo. Senza, l'app lavora a tappe brevi. |
| Notifiche | Ricordi, problemi, approvazione di un nuovo dispositivo | Onboarding | Facoltativo; contenuto oscurabile nella schermata di blocco. |
| Vibrazione (`VIBRATE`) | Feedback aptico dei componenti Fluid (`engine-ui`) | Nessuna richiesta (permesso normale) | Portato dall'engine; ammesso esplicitamente nel controllo del manifest (Fase 1.1). |
| **Non richiesti** | Posizione del dispositivo (casa riconosciuta con indizi di rete); accesso a tutti i file e `MANAGE_MEDIA` (si usano i dialoghi di sistema, seconda conferma gratuita); cestino di Samsung Gallery (richiederebbe l'accesso a tutti i file) | | Principio del minimo privilegio. |

---

## 5. Stato attuale della rete di casa

Fatti letti il 12/10/2026 in sola lettura da dashboard del NAS e del FRITZ!Box e misurati con le app di prova. Nessun identificativo (nomi di dispositivi, seriali, indirizzi, email, numeri). Esiti: **Bene**, **Da correggere**, **Da verificare**, **Da decidere**, **Informativo**.

### 5.1 NAS (WD My Cloud EX2 Ultra)

| Voce | Stato misurato | Esito |
|---|---|---|
| Software | My Cloud OS 5, firmware 5.33.102 | Da verificare: non abbiamo controllato se esista un firmware più recente; da ricontrollare a ogni fase |
| Dischi | RAID 1 (dichiarato dall'utente), stato integro, 2,42 TB liberi, circa 715 GB di dati | Bene contro il guasto di un disco; non è un backup (S10) |
| Rete | 1 Gbit/s, solo IPv4; IPv6 disattivato | Bene: nessuna esposizione IPv6 del NAS |
| Orologio | NTP attivo, fuso di Roma | Bene (ora di riferimento, C23) |
| Alimentazione | Acceso dalle 08:00 alle 22:00, spento di notte; l'orario potrebbe essere allungato | Informativo: di notte non è raggiungibile |

### 5.2 Account, cartelle e servizi del NAS

| Voce | Stato misurato | Esito |
|---|---|---|
| Cartelle | Cinque: due private (una per familiare, cestino del NAS spento), `NAS` pubblica (266,5 GB, in gran parte musica e video, cestino acceso), `Public` pubblica (vuota, cestino spento), `test` di prova | **Da correggere (critico):** le due cartelle pubbliche, con ogni probabilità senza password (da provare); cestino spento sulle private |
| Account | Due familiari, un account di prova `test`, l'amministratore; nessuna quota | Informativo |
| Account `test` | Lettura e scrittura su `NAS` e `Public` | **Da correggere (alto):** nega l'accesso ed eliminalo (§9) |
| Account admin | Collegato all'account cloud WD; non si usa nell'app | Da correggere: la password va cambiata (§9) |
| Accesso anonimo e blocco dell'account dopo tentativi falliti | Non visibili dalla dashboard; in Samba il blocco è spento di serie | Da verificare (L8) |
| SMB | Solo SMB 2 e 3 (SMB1 spento); autenticazione solo NTLMv2 | Bene |
| Firma SMB | Il NAS la abilita ma non la richiede; l'app la richiederà | Bene, a carico dell'app (C3) |
| Cifratura SMB | Supportata (AES-128-GCM) e provata | Bene |
| Altri servizi | SSH, FTP, NFS, SNMP e DNS dinamico spenti; accesso remoto alla dashboard spento; reindirizzamento HTTPS acceso; nessun inoltro di porte dal NAS | Bene |
| Accesso cloud WD | Attivo e connesso | **Da correggere (basso):** l'app non lo usa |
| Plex e Twonky (DLNA) | Installati; indicizzano i media e vedrebbero anche le cartelle nascoste dell'indice (E-I13) | Da verificare: potrebbero essere i client "senza password" delle cartelle pubbliche |

### 5.3 FRITZ!Box 7530 e connessione a Internet

| Voce | Stato misurato | Esito |
|---|---|---|
| Software | FRITZ!OS 8.25, aggiornato, aggiornamenti automatici | Bene |
| IPv4 | Nessun indirizzo pubblico: l'operatore usa CGNAT | Bene per la sicurezza (nessun accesso da Internet in IPv4); limita la VPN a IPv6 (L12) |
| IPv6 | Sì, con prefisso pubblico delegato e un prefisso locale stabile in LAN | Informativo (il prefisso locale serve a riconoscere casa, C2) |
| Porte aperte da Internet | Solo servizi del FRITZ!Box (telefonia, gestione del provider, manutenzione); nessuna verso dispositivi della LAN | Bene |
| MyFRITZ! | Registrato, nessuna abilitazione | Bene |
| Filtri | UPnP, NetBIOS, Teredo e WPAD risultano attivi | Da verificare: significato esatto e se UPnP serve |
| VPN | Nessuna connessione WireGuard configurata | Da fare (2.5) |
| Utenti del FRITZ!Box | Un amministratore e un utente con solo "Contenuti NAS" | Informativo |
| Rete di fabbrica | Quella predefinita, comune a quasi tutti i FRITZ!Box | Da decidere: con WireGuard i due capi devono stare in reti diverse (§9) |
| Indirizzo del NAS | Se sia prenotato nel DHCP non è visibile in sola lettura | Da verificare (E-R7) |

### 5.4 Wi-Fi e dispositivi

| Voce | Stato misurato | Esito |
|---|---|---|
| Cifratura Wi-Fi | WPA2, non WPA3 | **Da correggere (medio)** |
| WPS | Acceso | **Da correggere (medio)** |
| Comunicazione tra dispositivi Wi-Fi | Consentita | Da correggere insieme alla rete ospiti |
| Rete ospiti | Spenta | **Da correggere (medio)** |
| Dispositivi in rete | Decine, molti IoT (elettrodomestici, hub domotici, assistenti vocali, stampante, orologi, e-reader), sulla stessa rete del NAS | **Da correggere (medio)** |

### 5.5 Collegamento SMB provato con le app di prova

Un telefono (Android 17) e un tablet (Android 16), Wi-Fi a 5 GHz, con l'account di prova su una cartella di prova.

| Voce | Esito |
|---|---|
| Dialetto negoziato | SMB 3.1.1 su entrambi |
| Firma | La sessione risulta firmata |
| Cifratura AES-128-GCM | Funziona; costa circa il 25-28% in lettura e il 6% in scrittura. Il limite è la CPU del NAS, non il Wi-Fi |
| Copia lato server tra cartelle diverse | Funziona (circa 148 MB/s, hash verificato): spostamenti e condivisioni non fanno passare i byte dal telefono |
| Non ancora provato | Accesso anonimo alle cartelle pubbliche, blocco dell'account, Wake-on-LAN, VPN (velocità e raggiungibilità IPv6), traffico di ML Kit, Smart Switch |

---

## 6. Limiti dichiarati

Cose che questo progetto non risolve, o risolve solo in parte. Nessuna è nascosta.

- **L1. Maturità.** Siamo in Fase 0: le contromisure del §4 non sono ancora codice. Il piano è stato rivisto da due revisori indipendenti (casi limite e sicurezza), ma il codice non ha ancora avuto alcuna revisione esterna.
- **L2. Album condivisi e Nascosti sono protezioni dell'app.** Dal PC o con altre app, chi ha accesso all'area comune vede e può cancellare i file. Anche un familiare, o il suo dispositivo compromesso, con permesso di scrittura può distruggere l'area comune, e il RAID 1 copia il danno. Solo un backup esterno proteggerebbe: per questo "al sicuro" richiede una copia privata (C12).
- **L3. Le firme dicono chi ha scritto un dato, non che sia giusto,** e non impediscono le cancellazioni fatte dal PC.
- **L4. Credenziali usabili senza sblocco.** Servono al backup notturno. Un malware con privilegi di root sul telefono potrebbe ottenerle. La libreria SMB conserva inoltre la password in memoria per tutta la durata della sessione.
- **L5. Cancellazione remota.** Funziona solo se il dispositivo perso si ricollega. Le revoche vere sono quelle del FRITZ!Box e la password del NAS.
- **L6. Un peer VPN raggiunge tutta la rete di casa,** perché il FRITZ!Box non filtra per peer. Chi ha la configurazione o il QR di WireGuard ha lo stesso accesso.
- **L7. Identità del NAS.** L'autenticazione SMB non prova l'identità del NAS come farebbe un certificato, e riconoscere "casa" è un insieme di indizi, non una prova crittografica. Contro un finto NAS, la risposta alla sfida di password è craccabile offline. Per questo l'app usa SMB solo su reti riconosciute o in VPN, e servono password lunghe.
- **L8. Tentativi di password.** Il NAS potrebbe non limitarli: il blocco dell'account non si vede dalla dashboard e in Samba è spento di serie. Un dispositivo della rete potrebbe provarne molte. L'app fa un solo tentativo (C5); contro gli altri difendono solo password lunghe e uniche.
- **L9. Libreria SMB di terzi (smbj 0.15.0, 21/08/2026).** Versione giovane, con problemi aperti noti. Non valida la negoziazione per SMB 3.0 e 3.0.2 (per questo si usa solo 3.1.1) e accetta pacchetti fino a 16 MiB senza limite, quindi un host ostile potrebbe far allocare memoria (un motivo in più per C2).
- **L10. Cifratura.** Solo AES-128 (GCM o CCM); niente AES-256. Adeguato a un uso domestico, ma non è il livello massimo disponibile in SMB.
- **L11. Il registro dei dispositivi** mostra ai familiari quali dispositivi esistono e quando sono stati attivi.
- **L12. Fuori casa.** Con CGNAT la VPN funziona solo su IPv6: sulle reti Wi-Fi solo IPv4 il NAS non è raggiungibile e l'app lo spiega (E-R11). Non è una falla ma il prezzo di non esporre nulla.
- **L13. Telemetria e mappa.** Finché non si misura il traffico di ML Kit, "nessuna telemetria" non è garantita (§8). La mappa online chiede le tessere a un server esterno (OpenFreeMap, senza account): quel server vede la zona guardata e l'indirizzo IP. Le regioni offline evitano la richiesta.
- **L14. Volti.** Gli embedding facciali sono probabilmente dati biometrici ai sensi del GDPR (art. 9). L'uso esclusivamente domestico è fuori dal regolamento; se l'app venisse pubblicata servirebbe una valutazione legale. I modelli scelti in via provvisoria (YuNet MIT, SFace Apache-2.0) sono addestrati su dataset di ricerca di cui non abbiamo verificato i termini: rischio trascurabile in famiglia, da rivalutare prima di pubblicare. Questo documento non è consulenza legale.
- **L15. Ciò che l'app non vede.** Secure Folder, Spazio privato e app clonate non sono salvati (E-P7, E-P17): chi li usa non deve contare sul backup. Un dispositivo riavviato resta bloccato fino al primo sblocco e il backup si ferma (E-P13).
- **L16. L'app non può correggere la rete.** Non chiude le cartelle pubbliche, non separa i dispositivi, non fa il backup del NAS. Quelle sono le azioni del §1.
- **L17. Disponibilità.** Il NAS è spento dalle 22 alle 8 e il Wake-on-LAN, se funziona, è utilizzabile solo da casa (E-R5, E-R6). Di notte il lavoro dell'app può fermarsi.

---

## 7. Checklist di verifica per fase

### Per ogni fase, sempre
- [ ] Eseguire la skill `security-review` sulle modifiche della fase; risolvere o motivare per iscritto ogni segnalazione.
- [ ] Aggiornare questo documento: scenari (§3), stato delle contromisure (§4), limiti (§6), decisioni (§8).
- [ ] Nessun nuovo permesso Android, componente esportato, host di rete contattato o libreria con accesso a Internet senza una riga in §4/§4-bis e una motivazione.
- [ ] Prove distruttive solo sull'area di prova (0.7).
- [ ] Nessuna password, token, nome di file o coordinata personale in repository, log, report e screenshot condivisi.

### Fase 0: verifiche sul campo (in corso)
- [ ] Ogni controllo di 0.1 è "a posto" o ha un'azione indicata (§5, §9).
- [ ] Utente `test` eliminato; app di prova disinstallate o credenziali di prova rimosse da telefono e tablet.
- [ ] Password admin del NAS cambiata.
- [ ] Per ciascuna correzione di rete (§9) c'è una decisione: sì, no o quando.
- [ ] † Provato se le cartelle pubbliche si aprono senza account, e se il NAS blocca l'account dopo tentativi falliti (altrimenti L8 resta scritto).
- [ ] Traffico di ML Kit catturato (0.4) e scelta OCR/volti presa (§8).
- [ ] Raggiungibilità di WireGuard in IPv6 confermata con una prova reale (0.2, 2.5).
- [ ] Nessun dato personale nei file di `docs/` e `spike/results/`.
- [ ] Questo documento letto e discusso con tutta la famiglia.

### Fase 1: fondamenta
- [ ] Checksum di tutte le dipendenze verificati e versioni fissate (libreria SMB, BouncyCastle e le altre).
- [ ] Release non debuggable, R8 attivo, log delle librerie silenziati. † Nessuna password nei log di una sessione completa.
- [ ] Chiave di firma fuori dal repository, con password forte e copia offline; account di pubblicazione con 2FA.
- [ ] Manifest: `allowBackup=false`, esclusione in `<device-transfer>`, componenti non esportati, FileProvider ristretto, PendingIntent immutabili, nessun deep link che esegue azioni.
- [ ] Credenziali nel Keystore (StrongBox dove c'è); `FLAG_SECURE`; protezione da sovrapposizioni; blocco app opzionale.
- [ ] Test che rifiutano dati non firmati, di dispositivi revocati, malformati, enormi o con percorsi ostili (1.4).
- [ ] Nessun SDK di terze parti che invii dati (elenco delle dipendenze controllato).
- [ ] Ispezione dell'APK di release.

### Fase 2: NAS, onboarding, famiglia, dispositivi
- [ ] Sessione SMB: solo 3.1.1, firma richiesta, cifratura attiva verificata a ogni sessione, guest rifiutato.
- [ ] Rete estranea con un host allo stesso IP: nessuna autenticazione (test e prova reale).
- [ ] Password errata: un solo tentativo sul NAS, nessun nuovo tentativo dai lavori in background.
- [ ] Un cambio di indirizzo del NAS non viene mai accettato in automatico.
- [ ] Il QR dell'app non contiene password; un QR di un'altra famiglia è rifiutato; la guida tratta il QR di WireGuard come una password.
- [ ] Dispositivo non approvato: solo lettura, nessun suo dato accettato, notifica all'admin.
- [ ] Dispositivo segnato come perso: azzerato al ricollegamento. Recupero dell'admin con il kit provato.
- [ ] Nessuna credenziale del FRITZ!Box nell'app.
- [ ] Permesso di WireGuard e permesso di rete locale spiegati; stato "Rete locale non consentita" in Diagnostica.
- [ ] Due profili del tablet isolati. Prova con Smart Switch (T-25).

### Fase 3: la galleria del telefono
- [ ] Permessi minimi: nessuna posizione del dispositivo, nessun accesso a tutti i file.
- [ ] Nascosti locali: `FLAG_SECURE`, biometria, recenti oscurati, screenshot bloccati.
- [ ] † Nessun traffico di rete in tutta la fase (l'app lavora solo sul telefono): cattura del traffico.
- [ ] Nessun nome di file o coordinata nei log di release.
- [ ] Una foto sparita dal telefono non è mai trattata come cancellata (E-P11).

### Fase 4: il NAS nella Libreria
- [ ] Con un account di prova, l'indice non permette di leggere dati privati altrui.
- [ ] Segmenti ostili, non firmati o di dispositivi revocati: rifiutati senza crash e segnalati in Diagnostica.
- [ ] Prenotazioni oltre il tetto ignorate; scadenze calcolate sull'ora del NAS.
- [ ] L'indice non compare in DLNA, Plex, Twonky né in Foto di Windows (E-I13).
- [ ] Modifica di massa simulata: scritture congelate e avviso.
- [ ] Nessuna azione distruttiva parte da dati letti dal NAS (finti cestini, finti album).
- [ ] Prova delle 500 interruzioni per le operazioni di 4.5.

### Fase 5: backup, versioni, integrazioni
- [ ] Prova delle 500 interruzioni sul backup di foto e video grandi: zero file a metà, duplicati o perdite; hash verificato al 100%.
- [ ] "Libera spazio" con copia corrotta o mancante: l'elemento non viene liberato. Avviso forte sull'assenza di backup esterno visibile.
- [ ] Condivisioni in arrivo: limiti, tipo verificato dai byte, rifiuto di URI di file dell'app, flussi copiati subito.
- [ ] Condividere con "rimuovi posizione": il file non contiene GPS.
- [ ] FileProvider: permessi revocati dopo l'uso; flag di lettura espliciti sugli URI condivisi (Android 17 e 18).
- [ ] "Modifica con…": l'originale resta conservato, nessuna sovrascrittura.

### Fase 6: ricerca
- [ ] Modello alterato di un byte rifiutato; download interrotto che riprende.
- [ ] Manifest remoto manomesso: firma non valida, ignorato.
- [ ] Nessun traffico di rete durante le ricerche. † Nessun host imprevisto durante OCR e indicizzazione.
- [ ] Cronologia delle ricerche solo locale e cancellabile. Nascosti mai nei risultati.

### Fase 7: album e condivisione
- [ ] Un elemento nascosto non compare in nessuna schermata né nel widget di prova (test automatico).
- [ ] Condivisione di un album: conferma esplicita con conteggio; lo spostamento parte solo da quella azione locale.
- [ ] L'interfaccia dichiara che permessi degli album e Nascosti sono protezioni dell'app; avviso per un media nascosto aggiunto a un album condiviso (E-SH9).
- [ ] Permessi degli album rispettati a tutti e tre i livelli.

### Fase 8: persone
- [ ] Attivazione per account; dati nell'area privata; "Disattiva e cancella" senza tracce su NAS e dispositivi.
- [ ] † Nessuna inferenza di età, genere, etnia o emozioni; nessun ritaglio di volti salvato se evitabile; dati cifrati a riposo.
- [ ] Licenze e provenienza dei modelli documentate; nota GDPR riletta e rivalutata se l'app sarà pubblicata (L14).

### Fase 9: ricordi, notifiche, mappa
- [ ] Notifiche con contenuto oscurato nella schermata di blocco (impostabile).
- [ ] Mappa: nota sulla privacy visibile (L13); regioni offline come alternativa.
- [ ] Nascosti mai nei ricordi.

### Fase 10: rifinitura e rilascio
- [ ] Revisione finale 10.4 per intero: un account non legge dati privati altrui tramite l'indice; dati di dispositivi non approvati o revocati rifiutati; segmento ostile senza danni; dispositivo perso azzerato; backup Android e Smart Switch esclusi; nessun dato personale nei log di release; modello o manifest manomesso rifiutato; nessuna autenticazione SMB su rete estranea; modifica di massa che fa scattare il congelamento.
- [ ] Rilascio con manifest firmato (kill switch, versione minima), account di pubblicazione con 2FA, changelog.
- [ ] Widget: mai elementi nascosti, aggiornamento parsimonioso.
- [ ] Questo documento rivisto per intero e condiviso con la famiglia.

---

## 8. Registro delle decisioni di sicurezza

| Data | Decisione | Motivo | Stato |
|---|---|---|---|
| 12/10/2026 | **Cifratura SMB3 sempre attiva** (AES-128-GCM). A ogni sessione l'app verifica che sia davvero attiva; se il NAS non la concede, rifiuta la connessione. | Scelta dell'utente dopo le misure: costa circa il 25-28% in lettura e il 6% in scrittura. Copre anche il Wi-Fi e la rete di casa, dove la VPN non c'è. La libreria SMB "annuncia" la cifratura ma non la impone: se il server non la negozia ripiega sulla sola firma senza errori, da cui il controllo a ogni sessione. | Deciso |
| 12/10/2026 | **Solo SMB 3.1.1, con firma obbligatoria** lato client; guest e sessioni anonime rifiutati, anche con controllo esplicito. | Il NAS abilita la firma ma non la richiede. La libreria SMB non protegge dal downgrade per 3.0 e 3.0.2; il 3.1.1 ha la verifica pre-autenticazione. Con la cifratura attiva l'integrità dei messaggi è garantita dalla cifratura stessa (nella libreria cifratura e firma non si sommano): la firma obbligatoria resta in configurazione come seconda barriera, ma non è la protezione che lavora finché la cifratura è obbligatoria. NTLMv1 spento sul NAS. | Deciso |
| 12/10/2026 | **Accesso da fuori solo con WireGuard del FRITZ!Box, in IPv6.** Nessuna porta aperta, nessun IPv4 pubblico richiesto all'operatore. | Il collegamento è CGNAT (nessun IPv4 pubblico) e l'utente accetta il limite. Meno superficie d'attacco, al costo di non funzionare su Wi-Fi solo IPv4 (L12). | Deciso; da provare sul campo (0.2, 2.5) |
| ott. 2026 (piano) | **SMB solo su rete di casa riconosciuta o dentro la VPN;** nessuna autenticazione su reti estranee; indirizzo del NAS mai cambiato in automatico. | NTLMv2 non prova l'identità del NAS: un host qualunque allo stesso indirizzo potrebbe ottenere una risposta di password (E-S10, E-R7, L7). | Deciso |
| ott. 2026 (piano) | **Tutto ciò che si scrive sul NAS è firmato** (chiave per dispositivo nel Keystore). Ogni dispositivo è approvato dall'admin; registro firmato con revoca; chiave di famiglia con kit di recupero. | Il NAS è l'unico punto d'incontro e non può eseguire codice nostro: un dispositivo compromesso non deve poter ingannare gli altri. | Deciso; kit di recupero: proposta 15, vale se non contestata |
| ott. 2026 (piano) | **Nessuna azione distruttiva da dati letti dal NAS:** cancellazioni e spostamenti solo da azioni locali confermate. | Dati ostili o falsi sul NAS non devono poter cancellare nulla (E-S11). | Deciso |
| 12/10/2026 | **Nessuna telemetria,** nessun crash reporting esterno, nessun SDK che invii dati. **Dubbio su ML Kit (OCR):** anche nella versione integrata invia a Google metriche d'uso (modello del dispositivo, versione di Android, nome e versione dell'app, identificativi per installazione, tempi, configurazione, errori). Non contengono le immagini e non c'è un modo documentato per disattivarle. | "Nessuna telemetria" non è garantita da ML Kit. Verifica: catturare il traffico dell'app (0.4). Piano B: PaddleOCR (Apache-2.0) per l'OCR; YuNet con SFace per i volti. | **Aperto:** da misurare prima di chiudere la Fase 0 |
| 12/10/2026 | **Niente NPU e niente librerie Qualcomm** nell'app. Spazio dei vettori unico in virgola mobile: indicizzazione su GPU (ripiego CPU), query su CPU. | I vettori dell'NPU sono incompatibili con quelli degli altri dispositivi (stessi risultati solo nel 47-65% dei casi). In più nessuna libreria nativa proprietaria da fidarsi, APK più leggero, nessuna licenza Qualcomm. | Deciso; la NPU resta un'opzione solo con una variante compatibile |
| 12/10/2026 | **targetSdk 36 per ora,** con passo di onboarding "Accesso alla rete locale" e stato in Diagnostica già pronti per il target 37 (`ACCESS_LOCAL_NETWORK`). | Su Android 17 le app con target 37 non possono parlare con la rete locale (SMB, Wake-on-LAN) senza permesso esplicito; con target 36 la concessione è implicita ma dichiarata "temporanea". Il telefono è già su Android 17. | Deciso; passaggio a 37 da pianificare (T-19, T-24) |
| 12/10/2026 | **Backup Android e trasferimento da dispositivo a dispositivo esclusi:** `allowBackup=false` più esclusione esplicita in `<device-transfer>` (`dataExtractionRules`). Telefono nuovo = nuova chiave e nuova approvazione. | Con `allowBackup=false` il trasferimento diretto (per esempio Smart Switch) può comunque copiare i dati su alcuni produttori. Credenziali e chiavi non devono viaggiare (E-O8). | Deciso; da verificare con Smart Switch (T-25) |
| ott. 2026 (piano) | **Credenziali nel Keystore** (StrongBox dove c'è), utilizzabili in background senza sblocco. Blocco app opzionale con la credenziale di sistema, mai un PIN proprio. | Il backup notturno non può chiedere lo sblocco; un PIN proprio sarebbe più debole di quello del telefono. Limite in L4. | Deciso |
| ott. 2026 (piano) | **L'account admin del NAS non si usa mai nell'app.** Ogni familiare ha il suo account con password lunga. | Limita il danno se un dispositivo viene compromesso; il NAS potrebbe non bloccare i tentativi di password (L8). | Deciso |
| ott. 2026 (piano) | **Interruttore di sicurezza** al primo errore di autenticazione, nessun nuovo tentativo automatico. | Evitare che l'app faccia bloccare l'account dal NAS. | Deciso |
| ott. 2026 (piano) | **Wake-on-LAN solo dalla rete di casa,** funzione facoltativa. Nessuna credenziale del FRITZ!Box nell'app. | Meno segreti da custodire. Sull'EX2 Ultra i report sul Wake-on-LAN sono contrastanti. | WoL da provare |
| 12/10/2026 | **Lettura dei media con `ACCESS_MEDIA_LOCATION`** e lettura dell'originale; non si chiede `MANAGE_MEDIA` né l'accesso a tutti i file. | Senza quel permesso la posizione viene oscurata e l'hash non coincide. I dialoghi di sistema sono una seconda conferma gratuita (minimo privilegio). | Deciso |
| ott. 2026 (proposta 13) | **Dati dei volti** per account, nell'area privata di chi li attiva, anche per le foto dell'area comune. Cancellazione completa su richiesta. | Sono dati biometrici (L14). | Proposta, vale se non contestata |

---

## 9. Da fare prima della Fase 1

1. **Eliminare l'utente NAS `test` a fine Fase 0.** Nel frattempo negargli l'accesso a `NAS` e `Public` (oggi ha lettura e scrittura). Controllare che le copie di prova non siano rimaste in `Public`. Poi disinstallare le app di prova o rimuovere le sue credenziali da telefono e tablet. Le prove distruttive delle fasi successive richiedono un account di prova (0.7): va ricreato isolato, solo sull'area di prova e senza accesso a `NAS` e `Public`. (Nota di questo documento, da confermare.)
2. **Cambiare la password dell'account admin del NAS.** È stata condivisa in una conversazione di lavoro con l'assistente di sviluppo: va considerata nota. Nuova password lunga e unica, in un gestore di password. Controllare che nessuna password (admin, `test`, account dei familiari) sia scritta in file del repository, nei log o negli appunti delle app di prova; se c'è, cambiarla.
3. **Decidere le correzioni della rete.** Decide la famiglia, con questo documento davanti.

| Correzione | Cosa serve prima | Decisione |
|---|---|---|
| Togliere "Pubblico" a `NAS` e `Public` | Provare da un dispositivo senza account se oggi si entra. Elencare cosa le usa senza password (TV con Plex/DLNA, stampante, altri PC) e dargli un account o escluderlo. | ☐ Sì ☐ No ☐ Dopo |
| Copia esterna del NAS | Un disco USB e l'app WD "USB Backups"; decidere chi lo stacca e dove lo tiene. Se si sceglie di no, l'assenza di backup resta scritta come rischio accettato (S10). | ☐ Sì ☐ No ☐ Dopo |
| Wi-Fi WPA2+WPA3 e WPS spento | Controllare che i dispositivi più vecchi (IoT, e-reader, orologi) reggano la modalità mista. | ☐ Sì ☐ No ☐ Dopo |
| Rete ospiti per i dispositivi "smart" | Verificare quali funzionano con la sola Internet; stampante e apparecchi comandati dalla rete di casa potrebbero dover restare nella rete principale. | ☐ Sì ☐ No ☐ Dopo |
| Spegnere l'accesso cloud WD | Assicurarsi che nessuno usi l'app mobile WD. | ☐ Sì ☐ No ☐ Dopo |
| Indirizzo del NAS prenotato nel DHCP | Controllare nel FRITZ!Box; serve a E-R7. | ☐ Fatto ☐ Da fare |
| Rete di fabbrica del FRITZ!Box | Con WireGuard i due capi devono stare in reti diverse: decidere se cambiarla prima di creare i peer. Costa riconfigurare indirizzi fissi e prenotazioni. | ☐ Cambiare ☐ Lasciare |
| UPnP | Capire cosa significa "attivo" e se serve a qualcosa. | ☐ Spegnere ☐ Lasciare |

4. **Chiudere il dubbio ML Kit** con la cattura del traffico (0.4) e scrivere la scelta nel §8.
5. **Condividere questo documento con la famiglia** e raccogliere le domande prima di iniziare la Fase 1.

---

*Fonti: `inventario-nas.md`, `decisioni.md`, `android.md`, `research/smb-vpn.md`, `research/ocr-volti.md` e il piano di progetto (§0, §2, §3, §7, catalogo dei casi limite).*
