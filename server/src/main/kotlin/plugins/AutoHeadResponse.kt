package ch.nokillswit.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.response.*

fun Application.configureAutoHeadResponse() {
    install(AutoHeadResponse)
}
