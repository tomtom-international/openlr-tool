package com.tomtom.openlr.tool.controller

import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Maps server-side failures to server-side status codes.
 *
 * Decoding used to catch every exception and turn it into a decode failure, which
 * the controller answered with `400 Bad Request`. A database outage therefore told
 * the caller their OpenLR code was malformed:
 *
 *     HTTP 400 {"msg":"Failed to decode CwQ1...","reason":"Decoding error:
 *               Failed to obtain JDBC Connection"}
 *
 * That is wrong on its own terms and it also breaks the front end's diagnostics,
 * whose purpose is to separate a bad code from a bad map from a backend that is
 * down. A 400 now means the request was at fault; anything else does not.
 */
@RestControllerAdvice
class ApiExceptionHandler {
    private val logger = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(DataAccessException::class)
    fun handleDatabaseFailure(e: DataAccessException): ResponseEntity<Map<String, String>> {
        logger.error("Database access failed", e)
        return ResponseEntity(
            mapOf(
                "error" to "Service Unavailable",
                "reason" to "The map database is unavailable: ${e.message}"
            ),
            HttpStatus.SERVICE_UNAVAILABLE
        )
    }
}
