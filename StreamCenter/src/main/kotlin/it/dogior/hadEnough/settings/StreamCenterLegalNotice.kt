package it.dogior.hadEnough.settings

internal object StreamCenterLegalNotice {
    data class Section(val title: String, val points: List<String>)

    const val TITLE = "Note legali"

    val sections = listOf(
        Section(
            "Cos'è StreamCenter",
            listOf(
                "StreamCenter è un'estensione per CloudStream 3. Organizza schede, episodi e liste usando metadati pubblici (TMDB, AniList, MyAnimeList, Kitsu, Simkl) e cerca collegamenti già disponibili su Internet, come farebbe un motore di ricerca.",
                "StreamCenter non ha server propri e non ospita, carica, archivia o distribuisce file video.",
                "StreamCenter non è affiliato a TMDB, AniList, CloudStream o ai siti, add-on ed estensioni di terze parti che usa, né è approvato da loro. Tutti i marchi appartengono ai rispettivi proprietari.",
            ),
        ),
        Section(
            "Contenuti di terze parti",
            listOf(
                "I collegamenti arrivano da siti, add-on Stremio ed estensioni CloudStream di terze parti, sui quali StreamCenter non ha alcun controllo. Disponibilità, qualità e liceità dei contenuti dipendono solo da chi li pubblica.",
                "StreamCenter non può rimuovere contenuti che non ospita: per segnalare una violazione del diritto d'autore bisogna rivolgersi al sito o al servizio che pubblica il contenuto.",
            ),
        ),
        Section(
            "Responsabilità di chi lo usa",
            listOf(
                "Chi usa StreamCenter è l'unico responsabile di cosa cerca, guarda e scarica, e deve rispettare le leggi del proprio Paese, comprese quelle sul diritto d'autore (in Italia la legge 633/1941).",
                "Usa StreamCenter solo per contenuti di pubblico dominio, con licenza libera o che hai il diritto di guardare.",
                "Torrent: con il peer-to-peer, mentre scarichi condividi parti del file con altri utenti, cioè lo distribuisci anche tu. Attivali solo per contenuti che hai il diritto di condividere.",
                "Nelle schede anime, quando sono disponibili, StreamCenter mostra le piattaforme ufficiali dove guardarle (dati JustWatch): sono la scelta consigliata.",
            ),
        ),
        Section(
            "Dati e garanzie",
            listOf(
                "Impostazioni, cache e liste restano sul dispositivo; la chiave API TMDB non finisce in backup né in sincronizzazioni.",
                "StreamCenter è fornito così com'è, senza garanzie di funzionamento, disponibilità o idoneità a qualsiasi scopo.",
            ),
        ),
    )
}
