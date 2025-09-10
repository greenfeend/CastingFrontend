package com.example.castingsystem

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.html.*
import io.ktor.http.*
import kotlinx.html.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

/**
 * Data classes used for (de)serialising responses from the Rust backend.
 */
@Serializable
data class Pairing(val client_mac: String, val device_mac: String)

@Serializable
data class ModeStatus(val mode: String)

@Serializable
data class ModeChange(val mode: String)

/**
 * Room model.  This table is stored in Postgres via Exposed and is
 * separate from the Rust sled database.  Rooms can optionally be
 * associated with a device MAC address (e.g. a Chromecast).  You could
 * extend this model to store additional metadata such as the
 * geographical location or capacity of the room.
 */
@Serializable
data class Room(val id: Int, val name: String, val deviceMac: String?)

object Rooms : IntIdTable() {
    val name = varchar("name", 255)
    val deviceMac = varchar("device_mac", 255).nullable()
}

fun main() {
    // Optionally connect to PostgreSQL if credentials are provided.
    val dbUrl = System.getenv("POSTGRES_URL")
    val dbUser = System.getenv("POSTGRES_USER") ?: ""
    val dbPass = System.getenv("POSTGRES_PASSWORD") ?: ""
    if (dbUrl != null) {
        Database.connect(url = dbUrl, driver = "org.postgresql.Driver", user = dbUser, password = dbPass)
        // Create the table on first run if it does not exist.
        transaction {
            SchemaUtils.createMissingTablesAndColumns(Rooms)
        }
    }

    // Determine where the Rust API is hosted.  The default assumes
    // rustycast is running on localhost port 3000.
    val rustServerBase = System.getenv("RUST_SERVER_BASE") ?: "http://localhost:3000"

    // Build a Ktor HTTP client for talking to the Rust API.
    val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    // Spin up the Ktor server on the configured port.  The lambda
    // defines all routes in a single place for clarity.  Routes are
    // kept simple and synchronous; in a production deployment you
    // should add proper error handling, logging and authentication.
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port) {
        routing {
            // Home page: simple index listing navigation options.
            get("/") {
                call.respondHtml {
                    head {
                        title { +"Casting System Dashboard" }
                    }
                    body {
                        h1 { +"Casting System Dashboard" }
                        ul {
                            li { a("/pairings") { +"Manage Pairings" } }
                            li { a("/mode") { +"Current Mode" } }
                            li { a("/rooms") { +"Manage Rooms" } }
                        }
                    }
                }
            }

            /**
             * Pairings page: lists all current client/device pairings and
             * provides forms to add or remove a pairing.  All operations
             * delegate to the RustyCast HTTP API.  Upon completion we
             * redirect back to this page.
             */
            get("/pairings") {
                // Fetch existing pairings from the Rust server.
                val pairings: List<Pairing> = try {
                    client.get("$rustServerBase/api/pairings").body()
                } catch (e: Exception) {
                    // If the Rust API is unreachable, display an empty list.
                    emptyList()
                }
                call.respondHtml {
                    head { title { +"Pairings" } }
                    body {
                        h1 { +"Pairings" }
                        if (pairings.isEmpty()) {
                            p { +"No pairings found.  Ensure the Rust server is running and reachable." }
                        } else {
                            table {
                                tr {
                                    th { +"Client MAC" }
                                    th { +"Device MAC" }
                                    th { +"Actions" }
                                }
                                for (p in pairings) {
                                    tr {
                                        td { +p.client_mac }
                                        td { +p.device_mac }
                                        td {
                                            form(action = "/pairings/delete", method = FormMethod.post) {
                                                input(type = InputType.hidden, name = "client_mac") { value = p.client_mac }
                                                input(type = InputType.hidden, name = "device_mac") { value = p.device_mac }
                                                submitInput { value = "Delete" }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        h2 { +"Add Pairing" }
                        form(action = "/pairings/add", method = FormMethod.post) {
                            label {
                                +"Client MAC: "
                                textInput(name = "client_mac") { required = true }
                            }
                            br {}
                            label {
                                +"Device MAC: "
                                textInput(name = "device_mac") { required = true }
                            }
                            br {}
                            submitInput { value = "Add Pairing" }
                        }
                        p { a("/") { +"Back" } }
                    }
                }
            }
            post("/pairings/add") {
                val params = call.receiveParameters()
                val clientMac = params["client_mac"]
                val deviceMac = params["device_mac"]
                if (clientMac != null && deviceMac != null) {
                    client.post("$rustServerBase/api/pairings") {
                        contentType(ContentType.Application.Json)
                        setBody(Pairing(clientMac, deviceMac))
                    }
                }
                call.respondRedirect("/pairings")
            }
            post("/pairings/delete") {
                val params = call.receiveParameters()
                val clientMac = params["client_mac"]
                val deviceMac = params["device_mac"]
                if (clientMac != null && deviceMac != null) {
                    client.delete("$rustServerBase/api/pairings") {
                        contentType(ContentType.Application.Json)
                        setBody(Pairing(clientMac, deviceMac))
                    }
                }
                call.respondRedirect("/pairings")
            }

            /**
             * Mode page: displays the current forwarding mode and allows
             * administrators to change it.  Underlying values mirror the
             * `ForwardMode` enum in the Rust code.  The list here is kept
             * simple; if you add new modes in Rust you should extend
             * this list accordingly.
             */
            get("/mode") {
                val status: ModeStatus = try {
                    client.get("$rustServerBase/api/mode").body()
                } catch (e: Exception) {
                    ModeStatus("Unknown")
                }
                call.respondHtml {
                    head { title { +"Forwarding Mode" } }
                    body {
                        h1 { +"Forwarding Mode" }
                        p { +"Current Mode: ${status.mode}" }
                        form(action = "/mode/set", method = FormMethod.post) {
                            label { +"Select Mode: " }
                            select {
                                name = "mode"
                                option { value = "Auto"; +"Auto" }
                                option { value = "Userspace"; +"Userspace" }
                                option { value = "Xdp"; +"Xdp" }
                            }
                            submitInput { value = "Update Mode" }
                        }
                        p { a("/") { +"Back" } }
                    }
                }
            }
            post("/mode/set") {
                val params = call.receiveParameters()
                val mode = params["mode"]
                if (mode != null) {
                    client.post("$rustServerBase/api/mode") {
                        contentType(ContentType.Application.Json)
                        setBody(ModeChange(mode))
                    }
                }
                call.respondRedirect("/mode")
            }

            /**
             * Rooms page: demonstrates how you might persist additional
             * metadata in a relational database alongside the sled store.
             * If the POSTGRES_* environment variables are not set the
             * rooms list will remain empty.  CRUD operations are
             * intentionally minimal.
             */
            get("/rooms") {
                val dbPresent = dbUrl != null
                val rooms: List<Room> = if (dbPresent) {
                    transaction {
                        Rooms.selectAll().map {
                            Room(it[Rooms.id].value, it[Rooms.name], it[Rooms.deviceMac])
                        }
                    }
                } else emptyList()
                call.respondHtml {
                    head { title { +"Rooms" } }
                    body {
                        h1 { +"Rooms" }
                        if (!dbPresent) {
                            p {
                                +"PostgreSQL connection not configured.  Set POSTGRES_URL, POSTGRES_USER and POSTGRES_PASSWORD to enable room management."
                            }
                        }
                        table {
                            tr {
                                th { +"ID" }
                                th { +"Name" }
                                th { +"Device MAC" }
                                th { +"Actions" }
                            }
                            rooms.forEach { room ->
                                tr {
                                    td { +room.id.toString() }
                                    td { +room.name }
                                    td { +(room.deviceMac ?: "") }
                                    td {
                                        form(action = "/rooms/delete", method = FormMethod.post) {
                                            input(type = InputType.hidden, name = "id") { value = room.id.toString() }
                                            submitInput { value = "Delete" }
                                        }
                                    }
                                }
                            }
                        }
                        h2 { +"Create Room" }
                        form(action = "/rooms/create", method = FormMethod.post) {
                            label {
                                +"Name: "
                                textInput(name = "name") { required = true }
                            }
                            br {}
                            label {
                                +"Device MAC: (optional) "
                                textInput(name = "device_mac")
                            }
                            br {}
                            submitInput { value = "Create" }
                        }
                        p { a("/") { +"Back" } }
                    }
                }
            }
            post("/rooms/create") {
                val params = call.receiveParameters()
                val name = params["name"]
                val deviceMac = params["device_mac"]
                if (name != null && dbUrl != null) {
                    transaction {
                        Rooms.insert {
                            it[Rooms.name] = name
                            it[Rooms.deviceMac] = deviceMac
                        }
                    }
                }
                call.respondRedirect("/rooms")
            }
            post("/rooms/delete") {
                val params = call.receiveParameters()
                val id = params["id"]?.toIntOrNull()
                if (id != null && dbUrl != null) {
                    transaction {
                        Rooms.deleteWhere { Rooms.id eq id }
                    }
                }
                call.respondRedirect("/rooms")
            }
        }
    }.start(wait = true)
}