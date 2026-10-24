package com.swmansion.kmpmaps.core

private const val HTTP_OK = 200
private const val HTTP_NO_CONTENT = 204
private const val HTTP_NOT_FOUND = 404
private const val HTTP_GONE = 410

/** What a map SDK should do with the answer of a tile server. */
internal enum class TileResponseKind {
    /** The response carries a tile image. */
    TILE,

    /** The server definitively has no tile here, so the SDK may cache that and never ask again. */
    NO_TILE,

    /** The request failed in a way that may pass, so the SDK should ask again later. */
    RETRY,
}

/**
 * Decides what an HTTP answer for one tile means.
 *
 * A tile outside a server's coverage is answered with 404 or 204, and treating that as a failure
 * would make the SDK request it again on every pan. Every other failure, including rate limits and
 * server errors, is worth retrying.
 *
 * @param statusCode HTTP status of the response
 * @param bodyIsEmpty Whether a 200 response carried no bytes
 * @return How the response should be treated
 */
internal fun classifyTileResponse(statusCode: Int, bodyIsEmpty: Boolean): TileResponseKind =
    when (statusCode) {
        HTTP_OK -> if (bodyIsEmpty) TileResponseKind.NO_TILE else TileResponseKind.TILE
        HTTP_NO_CONTENT,
        HTTP_NOT_FOUND,
        HTTP_GONE -> TileResponseKind.NO_TILE
        else -> TileResponseKind.RETRY
    }
