import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
	id("org.springframework.boot") version "3.2.7"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("jvm") version "1.9.25"
	kotlin("plugin.spring") version "1.9.25"
}

group = "com.tomtom.openlr"
version = "0.0.1-SNAPSHOT"

java.sourceCompatibility = JavaVersion.VERSION_17

dependencies {
	// Spring Boot
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")

	// Kotlin
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

	// OpenLR libraries
	implementation(libs.openlr.map)
	implementation(libs.openlr.binary)
	implementation(libs.openlr.decoder)
	implementation(libs.openlr.encoder)

	// Geospatial
	implementation(libs.jts.core)

	// Database
	runtimeOnly("org.postgresql:postgresql")

	// Testing
	testImplementation("org.springframework.boot:spring-boot-starter-test") {
		exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
	}
	testImplementation("io.mockk:mockk:1.13.8")
	testImplementation("com.ninja-squad:springmockk:4.0.2")
}

tasks.withType<KotlinCompile> {
	kotlinOptions {
		freeCompilerArgs = listOf("-Xjsr305=strict")
		jvmTarget = "17"
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// Docker build task - hybrid approach using existing Dockerfile
tasks.register("dockerBuild") {
	group = "docker"
	description = "Build JAR and Docker image using existing Dockerfile"
	dependsOn(tasks.bootJar)

	doLast {
		exec {
			workingDir = project.rootDir
			commandLine("docker", "build", "-f", "docker/Dockerfile", "-t", "openlr-webtool:${version}", "-t", "openlr-webtool:latest", ".")
		}
		println("✅ Docker image built successfully: openlr-webtool:${version}")
	}
}
