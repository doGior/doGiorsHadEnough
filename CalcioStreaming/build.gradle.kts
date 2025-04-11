// use an integer for version numbers
version = 4


cloudstream {
    language = "it"
    // All of these properties are optional, you can safely remove them

     description = "Live streams from the CalcioStreaming. Forked from the CalcioStreaming plugin in the ItalianProvider repo"
    authors = listOf("Gian-Fr","Adippe","doGior")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Live",
    )

    iconUrl = "https://www.calciostreaming.cool/templates/calciostreaming1/images/icons/apple-touch-icon.png"
}