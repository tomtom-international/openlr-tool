package com.tomtom.openlr.tool

import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.ApplicationContext
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@SpringBootApplication
class WebtoolApplication {
}

@Component
@Order(1)
class CommandLine1(val ctx: ApplicationContext) : CommandLineRunner {
	override fun run(vararg args: String?) {
		// println("Hello from CommandLine1")
	}
}

fun main(args: Array<String>) {
	runApplication<WebtoolApplication>(*args)
}
