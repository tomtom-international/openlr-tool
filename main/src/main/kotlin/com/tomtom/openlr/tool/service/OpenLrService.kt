package com.tomtom.openlr.tool.service

import com.tomtom.openlr.tool.model.*
import openlr.*
import openlr.binary.ByteArray
import openlr.binary.OpenLRBinaryDecoder
import openlr.binary.OpenLRBinaryEncoder
import openlr.binary.impl.LocationReferenceBinaryImpl
import openlr.decoder.OpenLRDecoder
import openlr.decoder.OpenLRDecoderParameter
import openlr.encoder.OpenLREncoder
import openlr.encoder.OpenLREncoderParameter
import openlr.location.LocationFactory
import openlr.map.Line
import openlr.properties.OpenLRPropertiesReader
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for OpenLR encoding and decoding operations.
 *
 * Manages the OpenLR library encoders/decoders and provides a simplified API
 * for converting between OpenLR references and map locations.
 */
@Service
class OpenLrService(
    private val mapDatabase: OpenLrMapDatabaseAdapter,
    @Value("\${decoder_properties_dir}") private val decoderPropsDir: String,
    @Value("\${encoding_properties_dir}") private val encoderPropsDir: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val encoder = OpenLREncoder()
    private val decoder = OpenLRDecoder()

    private val binaryEncoder = OpenLRBinaryEncoder()
    private val binaryDecoder = OpenLRBinaryDecoder()

    private val decoderParameterCache = ConcurrentHashMap<String, OpenLRDecoderParameter>()
    private val encoderParameterCache = ConcurrentHashMap<String, OpenLREncoderParameter>()

    init {
        logger.info("OpenLR encoder/decoder initialized")
    }

    /**
     * Profile names are restricted to this character set.
     *
     * `props` is interpolated into a file path, so without this a value like
     * `../application` read a properties file from outside the profile directory.
     * It also bounds [decoderParameterCache], which is keyed on the name: any
     * accepted value adds a permanent entry, so arbitrary input could grow the heap.
     */
    private val profileNamePattern = Regex("^[A-Za-z0-9_-]{1,64}$")

    /** Profile names that have a `.properties` file in [dir]. */
    private fun profilesIn(dir: String): List<String> =
        File(dir).listFiles { f -> f.isFile && f.name.endsWith(".properties") }
            ?.map { it.name.removeSuffix(".properties") }
            ?.sorted()
            ?: emptyList()

    /** Decoder profiles available to `props` on `/decode`. */
    fun availableProfiles(): List<String> = profilesIn(decoderPropsDir)

    /** Encoder profiles available to `props` on `/encode`. */
    fun availableEncoderProfiles(): List<String> = profilesIn(encoderPropsDir)

    /**
     * Validate profile names for either role, or `null` when they are all usable.
     *
     * Applies to encoding as well as decoding: `/encode` used to accept `props` and
     * ignore it outright -- the encoder parameters were built once at startup with no
     * properties file, so `encoding_properties/` was never read and any value was
     * silently accepted.
     */
    private fun profileError(names: List<String>, role: String, dir: String): String? {
        val malformed = names.filter { !profileNamePattern.matches(it) }
        if (malformed.isNotEmpty()) {
            return "Invalid $role profile name(s): ${malformed.joinToString(", ")}"
        }
        val available = profilesIn(dir)
        val unknown = names.filter { it !in available }
        if (unknown.isNotEmpty()) {
            // Previously a missing file fell back to the OpenLR library defaults with
            // only a warning, and meta.propertySet echoed the bogus name -- a typo
            // produced a plausible result computed with different parameters.
            return "Unknown $role profile(s): ${unknown.joinToString(", ")}. " +
                "Available: ${available.joinToString(", ")}"
        }
        return null
    }

    /** Encoder parameters for [propSet], built on first use and then cached. */
    private fun getOrLoadEncoderParameter(propSet: String): OpenLREncoderParameter {
        return encoderParameterCache.getOrPut(propSet) {
            val configFile = File("$encoderPropsDir/$propSet.properties")
            val builder = OpenLREncoderParameter.Builder()
                .with(mapDatabase)
                .with(listOf<PhysicalEncoder>(binaryEncoder))
            if (configFile.exists()) {
                builder.with(OpenLRPropertiesReader.loadPropertiesFromFile(configFile))
                logger.info("Loaded encoder properties: ${configFile.absolutePath}")
            } else {
                logger.warn("Encoder properties not found: ${configFile.absolutePath}")
            }
            builder.buildParameter()
        }
    }

    private fun getOrLoadDecoderParameter(propSet: String): OpenLRDecoderParameter {
        return decoderParameterCache.getOrPut(propSet) {
            val configFile = File("$decoderPropsDir/$propSet.properties")
            val builder = OpenLRDecoderParameter.Builder()
                .with(mapDatabase)
                .with(listOf<PhysicalDecoder>(binaryDecoder))
            if (configFile.exists()) {
                builder.with(OpenLRPropertiesReader.loadPropertiesFromFile(configFile))
                logger.info("Loaded decoder properties: ${configFile.absolutePath}")
            } else {
                logger.warn("Properties file not found: ${configFile.absolutePath}, using library defaults")
            }
            builder.buildParameter()
        }
    }

    /**
     * Decode an OpenLR location reference (base64 string) to a GeoJSON FeatureCollection.
     * Tries multiple property sets (comma-separated) in order until one succeeds.
     */
    fun decode(openLrCode: String, propertiesName: String = "default"): DecodingResponse {
        val startTime = System.nanoTime()
        return try {
            // Parse comma-separated property set names
            val propertySets = propertiesName.split(",").map { it.trim() }

            profileError(propertySets, "decoder", decoderPropsDir)?.let {
                return DecodingFailureResponse(openLrCode, it)
            }

            // Create ByteArray from base64 string
            val byteArray = ByteArray(openLrCode)

            // Create LocationReference from binary data
            val locationReference = LocationReferenceBinaryImpl("decoded", byteArray)

            // Try each property set in order until one succeeds
            var successfulPropertySet: String? = null
            var location: openlr.location.Location? = null

            for (propSet in propertySets) {
                logger.debug("Attempting decode with property set: $propSet")
                location = decoder.decode(getOrLoadDecoderParameter(propSet), locationReference)

                if (location.isValid) {
                    successfulPropertySet = propSet
                    logger.info("Successfully decoded with property set: $propSet")
                    break
                } else {
                    logger.debug("Decode failed with property set $propSet: ${location.returnCode}")
                }
            }

            if (location == null || !location.isValid || successfulPropertySet == null) {
                return DecodingFailureResponse(
                    openLrCode,
                    "Decoding failed with all property sets: ${propertySets.joinToString(", ")}"
                )
            }

            // Build GeoJSON response with the successful property set
            buildGeoJsonResponse(location, openLrCode, successfulPropertySet, System.nanoTime() - startTime)
        } catch (e: DataAccessException) {
            // A database problem is not a bad location reference. Rethrow so the
            // handler can answer 503 -- swallowing it here reported "failed to
            // decode" with a 400, blaming the caller's code for an outage and
            // corrupting the front end's diagnostics.
            logger.error("Database unavailable while decoding", e)
            throw e
        } catch (e: Exception) {
            logger.error("Error decoding OpenLR code", e)
            DecodingFailureResponse(openLrCode, "Decoding error: ${e.message}")
        }
    }

    private fun buildGeoJsonResponse(
        location: openlr.location.Location,
        openLrCode: String,
        propertiesName: String,
        elapsed: Long
    ): DecodingResponse {
        return when (location.locationType) {
            LocationType.LINE_LOCATION -> buildLineLocationGeoJson(
                location as openlr.location.LineLocation,
                openLrCode,
                propertiesName,
                elapsed
            )
            else -> DecodingFailureResponse(
                openLrCode,
                "Unsupported location type: ${location.locationType}"
            )
        }
    }

    private fun buildLineLocationGeoJson(
        lineLocation: openlr.location.LineLocation,
        openLrCode: String,
        propertiesName: String,
        elapsed: Long
    ): FeatureCollection {
        val lines = lineLocation.locationLines.toList()
        val features = mutableListOf<Feature>()
        val allCoordinates = mutableListOf<List<Double>>()

        for (line in lines) {
            val mapLine = mapDatabase.getLine(line.id)
            if (mapLine != null) {
                val coordinates = mapLine.shapeCoordinates.map { coord ->
                    listOf(coord.longitudeDeg, coord.latitudeDeg)
                }
                allCoordinates.addAll(coordinates)

                val road = mapDatabase.mapDatabaseService.getRoad(Math.abs(line.id))
                features.add(
                    Feature(
                        properties = LineLocationFeatureProperties(
                            meta = road?.meta ?: "",
                            direction = line.id > 0,
                            fow = line.fow.name,
                            frc = line.frc.name,
                            lengthMeters = mapLine.lineLength
                        ),
                        geometry = LineStringFeatureGeometry(coordinates)
                    )
                )
            }
        }

        // Consecutive segments share an endpoint, so the concatenation repeats points;
        // drop the duplicates. With no coordinates at all the previous expression
        // produced "LINESTRING()", which is not valid WKT.
        val deduplicated = allCoordinates.filterIndexed { index, coordinate ->
            index == 0 || coordinate != allCoordinates[index - 1]
        }
        val wkt = if (deduplicated.isEmpty()) "LINESTRING EMPTY"
                  else "LINESTRING(" + deduplicated.joinToString(", ") { "${it[0]} ${it[1]}" } + ")"

        return FeatureCollection(
            features = features,
            meta = LineLocationMetadata(
                posOff = lineLocation.positiveOffset,
                negOff = lineLocation.negativeOffset,
                openLR = openLrCode,
                propertySet = propertiesName,
                decodingElapsedNanos = elapsed,
                wkt = wkt
            )
        )
    }

    /**
     * Encode a path as an OpenLR location reference.
     *
     * The path is given as `local.roads.meta` values -- the source-network
     * references that decoding returns -- so a decoded path can be re-encoded
     * without translation. `local.roads.id` is an opaque internal key required by
     * the OpenLR library and is never part of the API.
     *
     * A leading `-` reverses traversal of that segment, e.g. `-390249024` travels
     * the segment against its digitised direction. Only a leading `-` is
     * significant; hyphens inside a meta value (UUIDs, for instance) are kept.
     *
     * Encoding requires `meta` to identify exactly one segment. A value matching
     * several is rejected rather than resolved arbitrarily -- see the unique index
     * on `local.roads (meta)`.
     */
    fun encode(
        pathMetas: List<String>,
        positiveOffset: Int = 0,
        negativeOffset: Int = 0,
        propertiesName: String = "default"
    ): EncodeResponse {
        return try {
            profileError(listOf(propertiesName), "encoder", encoderPropsDir)?.let {
                return EncodeResponse(success = false, openLrCode = null, error = it)
            }

            val lines = mutableListOf<Line>()
            for (element in pathMetas) {
                val reverse = element.startsWith("-")
                val meta = if (reverse) element.substring(1) else element

                if (meta.isBlank()) {
                    return EncodeResponse(
                        success = false,
                        openLrCode = null,
                        error = "Empty path element"
                    )
                }

                val matches = mapDatabase.mapDatabaseService.getRoadsByMeta(meta)
                if (matches.isEmpty()) {
                    return EncodeResponse(
                        success = false,
                        openLrCode = null,
                        error = "No segment found with meta '$meta'"
                    )
                }
                if (matches.size > 1) {
                    return EncodeResponse(
                        success = false,
                        openLrCode = null,
                        error = "meta '$meta' matches ${matches.size} segments; " +
                            "encoding requires it to identify exactly one"
                    )
                }

                val road = matches.first()
                val lineId = if (reverse) -road.id else road.id
                val line = mapDatabase.getLine(lineId)
                    ?: return EncodeResponse(
                        success = false,
                        openLrCode = null,
                        error = "Segment '$meta' cannot be traversed " +
                            (if (reverse) "against" else "with") +
                            " its digitised direction (flowdir=${road.flowDirection})"
                    )
                lines.add(line)
            }

            // Create a line location from the lines
            val location = LocationFactory.createLineLocationWithOffsets(
                "encoded_path",
                lines,
                positiveOffset,
                negativeOffset
            )

            // Encode the location
            val locationRefHolder =
                encoder.encodeLocation(getOrLoadEncoderParameter(propertiesName), location)

            if (!locationRefHolder.isValid) {
                return EncodeResponse(
                    success = false,
                    openLrCode = null,
                    error = "Encoding failed: ${locationRefHolder.returnCode}"
                )
            }

            // Get binary reference and convert to base64
            val binaryRef = locationRefHolder.getLocationReference("binary")
            val binaryData = binaryRef.locationReferenceData as? ByteArray

            if (binaryData == null) {
                return EncodeResponse(
                    success = false,
                    openLrCode = null,
                    error = "Failed to get binary data from location reference"
                )
            }

            EncodeResponse(
                success = true,
                openLrCode = binaryData.base64Data
            )
        } catch (e: DataAccessException) {
            logger.error("Database unavailable while encoding", e)
            throw e
        } catch (e: Exception) {
            logger.error("Error encoding path", e)
            EncodeResponse(
                success = false,
                openLrCode = null,
                error = "Encoding error: ${e.message}"
            )
        }
    }

    /**
     * Clear all caches in map database and reload properties.
     */
    fun clearCaches() {
        mapDatabase.clearCaches()
        logger.info("Caches cleared")
    }

    /** Occupancy and hit rates for every cache in the map layer. */
    fun cacheStats(): Map<String, LruCache.Stats> = mapDatabase.cacheStats()

    /**
     * Reload decoder/encoder properties by evicting the parameter cache.
     * The next decode call for each profile will re-read its .properties file from disk.
     */
    fun reloadProperties() {
        decoderParameterCache.clear()
        encoderParameterCache.clear()
        logger.info("Decoder and encoder properties caches cleared; files will be " +
            "reloaded on next request")
    }
}
