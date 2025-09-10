package com.example.castingsystem

import io.github.g0dkar.qrcode.QRCode
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.html.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader

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
 * Room model.
 */
@Serializable
data class Room(val id: Int, val name: String, val deviceMac: String?)

object Rooms : IntIdTable() {
    val name = varchar("name", 255)
    val deviceMac = varchar("device_mac", 255).nullable()
}

/**
 * Executes a shell command and returns its output.
 */
fun executeCommand(command: String): String? {
    return try {
        val process = ProcessBuilder(*command.split(" ").toTypedArray())
            .redirectErrorStream(true)
            .start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = reader.readText()
        process.waitFor()
        output
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

/**
 * Gets the MAC address for a given IP address by querying the ARP table.
 */
fun getMacAddressFromIp(ip: String): String? {
    val os = System.getProperty("os.name").toLowerCase()
    val command = when {
        "win" in os -> "arp -a $ip"
        "nix" in os || "nux" in os || "aix" in os -> "arp -n $ip"
        "mac" in os -> "arp -n $ip"
        else -> null
    }

    if (command != null) {
        val output = executeCommand(command)
        if (output != null) {
            val pattern = "([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})".toRegex()
            return pattern.find(output)?.value
        }
    }
    return null
}


fun main() {
    val dbUrl = System.getenv("POSTGRES_URL")
    val dbUser = System.getenv("POSTGRES_USER") ?: ""
    val dbPass = System.getenv("POSTGRES_PASSWORD") ?: ""
    if (dbUrl != null) {
        Database.connect(url = dbUrl, driver = "org.postgresql.Driver", user = dbUser, password = dbPass)
        transaction {
            SchemaUtils.createMissingTablesAndColumns(Rooms)
        }
    }

    val rustServerBase = System.getenv("RUST_SERVER_BASE") ?: "http://localhost:3000"

    val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port) {
        install(ForwardedHeaders)
        install(XForwardedHeaders)

        routing {
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

            get("/pairings") {
                val pairings: List<Pairing> = try {
                    client.get("$rustServerBase/api/pairings").body()
                } catch (e: Exception) {
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
                                +"PostgreSQL connection not configured. Set POSTGRES_URL, POSTGRES_USER and POSTGRES_PASSWORD to enable room management."
                            }
                        }
                        table {
                            tr {
                                th { +"ID" }
                                th { +"Name" }
                                th { +"Device MAC" }
                                th { +"Pairing QR Code" }
                                th { +"Actions" }
                            }
                            rooms.forEach { room ->
                                tr {
                                    td { +room.id.toString() }
                                    td { +room.name }
                                    td { +(room.deviceMac ?: "") }
                                    td {
                                        if (room.deviceMac != null) {
                                            img(src = "/qr-code/${room.id}")
                                        }
                                    }
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

            get("/qr-code/{roomId}") {
                val roomId = call.parameters["roomId"]?.toIntOrNull()
                if (roomId != null) {
                    val room = transaction {
                        Rooms.select { Rooms.id eq roomId }.map {
                            Room(it[Rooms.id].value, it[Rooms.name], it[Rooms.deviceMac])
                        }.singleOrNull()
                    }

                    if (room?.deviceMac != null) {
                        val pairingUrl = "http://${call.request.local.host}:${call.request.local.port}/pair-room/${room.id}"

                        val qrCodeImage = QRCode(pairingUrl).render().getBytes()

                        call.respondBytes(qrCodeImage, ContentType.Image.PNG)
                    }
                }
            }

            get("/pair-room/{roomId}") {
                val roomId = call.parameters["roomId"]?.toIntOrNull()
                val clientIp = call.request.origin.remoteHost

                if (roomId == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid Room ID")
                    return@get
                }

                val room = transaction {
                    Rooms.select { Rooms.id eq roomId }.map {
                        Room(it[Rooms.id].value, it[Rooms.name], it[Rooms.deviceMac])
                    }.singleOrNull()
                }

                if (room?.deviceMac == null) {
                    call.respond(HttpStatusCode.NotFound, "Room not found or no device MAC configured")
                    return@get
                }

                val clientMac = getMacAddressFromIp(clientIp)

                if (clientMac == null) {
                    call.respond(HttpStatusCode.InternalServerError, "Could not determine client MAC address from IP: $clientIp. This can happen if the device is on a different network subnet.")
                    return@get
                }

                client.post("$rustServerBase/api/pairings") {
                    contentType(ContentType.Application.Json)
                    setBody(Pairing(clientMac, room.deviceMac))
                }

                call.respondHtml {
                    head { title { +"Pairing Successful" } }
                    body {
                        h1 { +"Pairing Successful!" }
                        p { +"Your device ($clientMac) has been paired with ${room.name}." }
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

