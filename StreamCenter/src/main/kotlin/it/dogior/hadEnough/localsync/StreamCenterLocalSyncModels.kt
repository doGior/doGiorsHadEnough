package it.dogior.hadEnough.localsync

internal enum class StreamCenterLocalSyncPayloadType(
    val wireValue: String,
    val title: String,
) {
    ALL("all", "Tutto"),
    CLOUDSTREAM("cloudstream", "Configurazione CloudStream"),
    LIBRARY("library", "Libreria locale"),
    STREAMCENTER("streamcenter", "Configurazione StreamCenter"),
    ;

    companion object {
        fun fromWireValue(value: String?): StreamCenterLocalSyncPayloadType? =
            entries.firstOrNull { type -> type.wireValue == value }
    }
}

internal data class StreamCenterLocalSyncPayload(
    val type: StreamCenterLocalSyncPayloadType,
    val compressedBytes: ByteArray,
    val uncompressedSize: Int,
    val entryCount: Int,
    val libraryItemCount: Int,
    val progressCount: Int,
    val sourceAccount: String?,
)

internal data class StreamCenterLocalSyncOffer(
    val sessionId: String,
    val type: StreamCenterLocalSyncPayloadType,
    val senderName: String,
    val senderAddress: String,
    val senderPublicKey: String,
    val tcpPort: Int,
    val compressedSize: Int,
    val uncompressedSize: Int,
    val entryCount: Int,
    val libraryItemCount: Int,
    val progressCount: Int,
)

internal enum class StreamCenterLocalSyncState {
    IDLE,
    PREPARING,
    ADVERTISING,
    DISCOVERING,
    AUTHENTICATING,
    TRANSFERRING,
    APPLYING,
    COMPLETED,
    ERROR,
    CANCELLED,
}

internal data class StreamCenterLocalSyncEvent(
    val message: String,
    val detail: String? = null,
    val progress: Int? = null,
)

internal data class StreamCenterLocalSyncResult(
    val type: StreamCenterLocalSyncPayloadType,
    val sent: Boolean,
    val entryCount: Int,
    val libraryItemCount: Int,
    val progressCount: Int,
    val peerName: String,
    val restartRequired: Boolean,
)

internal interface StreamCenterLocalSyncListener {
    fun onStateChanged(state: StreamCenterLocalSyncState, message: String)
    fun onEvent(event: StreamCenterLocalSyncEvent)
    fun onOfferFound(offer: StreamCenterLocalSyncOffer)
    fun onPairingCodeReady(code: String)
    fun onCompleted(result: StreamCenterLocalSyncResult)
    fun onError(message: String, error: Throwable? = null)
}
