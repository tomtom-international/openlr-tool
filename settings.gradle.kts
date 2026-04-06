plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.5.0"
}
rootProject.name = "openlr-tool"
include("main")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            url = uri("https://repo.osgeo.org/repository/release")
        }
    }
    versionCatalogs {
        create("libs") {
            // Open-source OpenLR library from Maven Central
            library("openlr-data","org.openlr:data:1.4.3")
            library("openlr-map","org.openlr:map:1.4.3")
            library("openlr-binary","org.openlr:binary:1.4.3")
            library("openlr-xml","org.openlr:xml:1.4.3")
            library("openlr-decoder","org.openlr:decoder:1.4.3")
            library("openlr-encoder","org.openlr:encoder:1.4.3")

            // Geospatial libraries
            library("jts-core","org.locationtech.jts:jts-core:1.19.0")

            // Testing
            library("junit-jupiter-engine","org.junit.jupiter:junit-jupiter-engine:5.10.0")
            library("junit-vintage-engine","org.junit.vintage:junit-vintage-engine:5.10.0")
            library("junit-jupiter-api","org.junit.jupiter:junit-jupiter-api:5.10.0")
            library("junit-platform-launcher","org.junit.platform:junit-platform-launcher:1.10.0")
        }
    }
}
