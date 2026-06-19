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

    private val encoderParameter: OpenLREncoderParameter
    private val decoderParameterCache = ConcurrentHashMap<String, OpenLRDecoderParameter>()

    init {
        encoderParameter = OpenLREncoderParameter.Builder()
            .with(mapDatabase)
            .with(listOf<PhysicalEncoder>(binaryEncoder))
            .buildParameter()

        logger.info("OpenLR encoder/decoder initialized")
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

        // Build WKT representation
        val wkt = "LINESTRING(" + allCoordinates.joinToString(", ") { "${it[0]} ${it[1]}" } + ")"

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
     * Encode a path (list of line IDs) as an OpenLR location reference.
     */
    fun encode(
        lineIds: List<Long>,
        positiveOffset: Int = 0,
        negativeOffset: Int = 0,
        propertiesName: String = "default"
    ): EncodeResponse {
        return try {
            // Fetch lines from database
            val lines = lineIds.mapNotNull { mapDatabase.getLine(it) }

            if (lines.size != lineIds.size) {
                return EncodeResponse(
                    success = false,
                    openLrCode = null,
                    error = "Some line IDs not found in database"
                )
            }

            // Create a line location from the lines
            val location = LocationFactory.createLineLocationWithOffsets(
                "encoded_path",
                lines,
                positiveOffset,
                negativeOffset
            )

            // Encode the location
            val locationRefHolder = encoder.encodeLocation(encoderParameter, location)

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

    /**
     * Reload decoder/encoder properties by evicting the parameter cache.
     * The next decode call for each profile will re-read its .properties file from disk.
     */
    fun reloadProperties() {
        decoderParameterCache.clear()
        logger.info("Decoder properties cache cleared; files will be reloaded on next request")
    }
}
