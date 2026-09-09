package com.tomtom.openlr.tool.model

/**
 * Request to decode an OpenLR location reference.
 */
data class DecodeRequest(
    val openLrCode: String,
    val props: String = "default"
)

/**
 * Response from encoding operation.
 */
data class EncodeResponse(
    val success: Boolean,
    val openLrCode: String?,
    val error: String? = null
)
