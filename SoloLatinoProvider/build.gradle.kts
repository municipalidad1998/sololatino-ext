// use an integer for version numbers
version = 5

cloudstream {
    language = "mx"

    description = "Peliculas, series, animes y doramas en Español Latino"
    authors = listOf("municipalidad1998")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "Cartoon",
    )

    iconUrl = "https://sololatino.net/wp-content/uploads/2020/10/cropped-logo-final-192x192.png"
}
