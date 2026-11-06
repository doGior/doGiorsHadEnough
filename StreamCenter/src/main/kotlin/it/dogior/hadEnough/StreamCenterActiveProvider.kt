package it.dogior.hadEnough

internal fun activeMainProvider(): StreamCenter? = synchronized(StreamCenter.activeInstances) {
    StreamCenter.activeInstances.firstOrNull {
        it.catalogDefinition == null && it.searchSection == StreamCenter.SEARCH_SECTION_MAIN
    }
}
