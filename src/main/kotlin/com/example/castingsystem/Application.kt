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
import java.io.InputStreamReader

@Serializable
data class Pairing(val client_mac: String, val device_mac: String)

@Serializable
data class ModeStatus(val mode: String)

@Serializable
data class ModeChange(val mode: String)

@Serializable
data class Room(val id: Int, val name: String, val deviceMac: String?)

object Rooms : IntIdTable() {
    val name = varchar("name", 255)
    val deviceMac = varchar("device_mac", 255).nullable()
}

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

fun getMacAddressFromIp(ip: String): String? {
    val os = System.getProperty("os.name").lowercase()
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

// Reusable CoreUI layout (with fixed modal event binding)
suspend fun ApplicationCall.respondCoreUI(pageTitle: String, activePage: String, block: FlowContent.() -> Unit) {
    respondHtml {
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1.0, shrink-to-fit=no")
            title { +"Casting System - $pageTitle" }
            link(rel = "stylesheet", href = "https://cdn.jsdelivr.net/npm/@coreui/coreui@3.4.0/dist/css/coreui.min.css")
            link(rel = "stylesheet", href = "https://cdn.jsdelivr.net/npm/@coreui/icons@2.0.0/css/all.min.css")
            style {
                unsafe {
                    raw("""
                        .c-sidebar-nav-link.active, .c-sidebar-nav-link:hover { background: rgba(255,255,255,.05) !important; }
                        #theme-switcher { cursor: pointer; }
                        .c-sidebar-brand { font-weight: bold; }
                        .c-header-toggler:focus, .c-header-nav-link.btn:focus { outline: none !important; box-shadow: none !important; }
                    """.trimIndent())
                }
            }
        }
        body(classes = "c-app") {
            // Sidebar
            div(classes = "c-sidebar c-sidebar-dark c-sidebar-fixed") {
                id = "sidebar"
                div(classes = "c-sidebar-brand d-lg-down-none") { +"Casting System" }
                ul(classes = "c-sidebar-nav") {
                    li(classes = "c-sidebar-nav-item") {
                        a(classes = "c-sidebar-nav-link" + if (activePage == "dashboard") " active" else "", href = "/") {
                            i(classes = "c-sidebar-nav-icon cil-speedometer")
                            +"Dashboard"
                        }
                    }
                    li(classes = "c-sidebar-nav-title") { +"Management" }
                    li(classes = "c-sidebar-nav-item") {
                        a(classes = "c-sidebar-nav-link" + if (activePage == "rooms") " active" else "", href = "/rooms") {
                            i(classes = "c-sidebar-nav-icon cil-room")
                            +"Manage Rooms"
                        }
                    }
                    li(classes = "c-sidebar-nav-item") {
                        a(classes = "c-sidebar-nav-link" + if (activePage == "pairings") " active" else "", href = "/pairings") {
                            i(classes = "c-sidebar-nav-icon cil-link")
                            +"Manage Pairings"
                        }
                    }
                    li(classes = "c-sidebar-nav-item") {
                        a(classes = "c-sidebar-nav-link" + if (activePage == "mode") " active" else "", href = "/mode") {
                            i(classes = "c-sidebar-nav-icon cil-settings")
                            +"System Mode"
                        }
                    }
                }
            }
            // Wrapper
            div(classes = "c-wrapper") {
                header(classes = "c-header c-header-fixed") {
                    button(classes = "c-header-toggler c-class-toggler d-lg-none mfe-auto", type = ButtonType.button) {
                        attributes["data-target"] = "#sidebar"
                        attributes["data-class"] = "c-sidebar-show"
                        span(classes = "c-header-toggler-icon")
                    }
                    button(classes = "c-header-toggler c-class-toggler mfs-3 d-md-down-none", type = ButtonType.button) {
                        attributes["data-target"] = "#sidebar"
                        attributes["data-class"] = "c-sidebar-lg-show"
                        attributes["responsive"] = "true"
                        span(classes = "c-header-toggler-icon")
                    }
                    ul(classes="c-header-nav ml-auto mr-4") {
                        li(classes="c-header-nav-item") {
                            button(classes="c-header-nav-link btn") {
                                id = "theme-switcher"
                                attributes["aria-label"] = "Toggle light/dark theme"
                            }
                        }
                    }
                }
                div(classes = "c-body") {
                    main(classes = "c-main") {
                        div(classes = "container-fluid") {
                            div(classes = "fade-in") { block() }
                        }
                    }
                }
                footer(classes = "c-footer") {
                    div { +"Casting System Control Panel" }
                    div(classes = "ml-auto") { +"Powered by Ktor & CoreUI" }
                }
            }

            // Scripts
            script(src = "https://code.jquery.com/jquery-3.5.1.min.js") {}
            script(src = "https://cdn.jsdelivr.net/npm/@coreui/coreui@3.4.0/dist/js/coreui.bundle.min.js") {}
            script {
                unsafe {
                    raw("""
                        $(function() {
                            // Theme switcher
                            const themeSwitcher = $('#theme-switcher');
                            const body = $('body');
                            const moonIcon = '<i class="cil-moon" style="font-size: 1.2rem;"></i>';
                            const sunIcon  = '<i class="cil-sun"  style="font-size: 1.2rem;"></i>';
                            function applyTheme(theme) {
                                if (theme === 'dark') { body.addClass('c-dark-theme'); themeSwitcher.html(sunIcon); }
                                else { body.removeClass('c-dark-theme'); themeSwitcher.html(moonIcon); }
                            }
                            applyTheme(localStorage.getItem('theme') || 'light');
                            themeSwitcher.on('click', () => {
                                const t = body.hasClass('c-dark-theme') ? 'light' : 'dark';
                                localStorage.setItem('theme', t); applyTheme(t);
                            });

                            // QR modal: fire for both CoreUI + Bootstrap event namespaces
                            $('#qrModal').on('show.coreui.modal show.bs.modal', function (event) {
                                var button = $(event.relatedTarget);
                                var roomId = button.attr('data-roomid');
                                var roomName = button.attr('data-roomname');
                                var modal = $(this);
                                modal.find('.modal-title').text('Pairing QR Code for ' + roomName);
                                modal.find('#qrCodeImg').attr('src', '/qr-code/' + roomId + '?t=' + Date.now());
                            });
                        });
                    """.trimIndent())
                }
            }
        }
    }
}

private fun pairingSuccessHtml(roomName: String): String = """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <meta name="color-scheme" content="light dark" />
  <title>Pairing successful</title>
  <style>
    :root {
      --bg: #0f1220;
      --fg: #e7e9ee;
      --muted: #9aa3b2;
      --accent: #4ade80;   /* green */
      --accent-2: #22c55e; /* deeper green */
      --card: rgba(255,255,255,0.06);
      --shadow: 0 10px 30px rgba(0,0,0,0.25);
    }
    @media (prefers-color-scheme: light) {
      :root {
        --bg: #f6f8fb;
        --fg: #0f172a;
        --muted: #475569;
        --accent: #0ea5e9; /* blue */
        --accent-2:#06b6d4; /* cyan */
        --card: #ffffff;
        --shadow: 0 10px 30px rgba(2,8,23,.12);
      }
    }

    * { box-sizing: border-box }

    body {
      margin: 0;
      height: 100svh;
      display: grid;
      place-items: center;
      background:
        radial-gradient(1200px 600px at 10% 10%, rgba(255,255,255,.08), transparent),
        radial-gradient(1200px 600px at 90% 90%, rgba(0,0,0,.12), transparent),
        var(--bg);
      color: var(--fg);
      font-family: ui-sans-serif, system-ui, -apple-system, Segoe UI, Roboto, Ubuntu, Cantarell, Noto Sans, "Helvetica Neue", Arial, "Apple Color Emoji", "Segoe UI Emoji";
    }

    .card {
      position: relative;
      width: min(560px, 92vw);
      padding: 48px 40px;
      border-radius: 24px;
      background: var(--card);
      box-shadow: var(--shadow);
      backdrop-filter: blur(10px);
      border: 1px solid rgba(255,255,255,0.08);
      transition: background .45s ease, border-color .45s ease, box-shadow .45s ease, backdrop-filter .45s ease;
    }

    .card.ghost { background: transparent; border-color: transparent; box-shadow: none; backdrop-filter: none; }
    .card.reveal { animation: cardIn .4s cubic-bezier(.2,.8,.2,1) both; }
    @keyframes cardIn { from { transform: translateY(4px) scale(.985); } to { transform: translateY(0) scale(1); } }

    .center { display: grid; place-items: center; text-align: center; }
    h1 { font-size: clamp(24px, 3.5vw, 36px); margin: 14px 0 6px; letter-spacing: .2px; }
    p { margin: 8px 0 0; color: var(--muted); }

    .success-svg { width: 220px; height: 220px; display: block; }
    .circle { fill: none; stroke: var(--accent); stroke-width: 10; opacity: .25; }
    .check  { fill: none; stroke: var(--accent); stroke-linecap: round; stroke-linejoin: round; stroke-width: 10; stroke-dasharray: 100; stroke-dashoffset: 100; }

    .tick-wrap { position: fixed; inset: 0; display: grid; place-items: center; pointer-events: none; z-index: 10; }
    .tick-wrap.enter .check  { animation: draw 900ms ease-out forwards 200ms; }
    .tick-wrap.enter .circle { animation: pulse 900ms ease-in forwards; }
    .tick-wrap.exit { animation: fadeOut 500ms ease-in forwards 900ms; }

    @keyframes draw  { to { stroke-dashoffset: 0; } }
    @keyframes pulse { 0% { transform: scale(1); opacity:.25 } 50% { transform: scale(1.05); opacity:.6 } 100% { transform: scale(1); opacity:.25 } }
    @keyframes fadeOut { to { opacity: 0; transform: translateY(-6px) scale(.98); } }

    .msg { opacity: 0; transform: translateY(8px); }
    .msg.show { animation: fadeInUp .55s cubic-bezier(.2,.8,.2,1) forwards; }
    @keyframes fadeInUp { to { opacity: 1; transform: translateY(0); } }

    .badge { display:inline-block; padding:8px 12px; border-radius:999px; background: linear-gradient(90deg, var(--accent), var(--accent-2)); color: #fff; font-weight:700; font-size: .95rem; letter-spacing:.6px; text-transform: uppercase; box-shadow: 0 6px 20px rgba(16,185,129,.25) }
    .room { font-weight: 800; }
    .footer { margin-top: 16px; font-size:.9rem; }
    .hidden { display: none !important; }

    .confetti { position: absolute; width: 8px; height: 14px; top: -10px; opacity: 0; animation: drop linear forwards; }
    @keyframes drop { to { transform: translateY(140vh) rotate(540deg); opacity: 1 } }

    .logo { display:block; max-width: 200px; height:auto; margin: 0 auto 8px; opacity: 0; transform: translateY(8px); filter: drop-shadow(0 3px 10px rgba(0,0,0,.25)); }
    .logo.show { animation: logoIn .45s cubic-bezier(.2,.8,.2,1) both; }
    @keyframes logoIn { to { opacity: 1; transform: translateY(0); } }
  </style>
</head>
<body>
  <main class="card ghost center">
    <div id="tick" class="tick-wrap enter" aria-hidden="true">
      <svg class="success-svg" viewBox="0 0 120 120" role="img" aria-label="Success">
        <circle class="circle" cx="60" cy="60" r="46"></circle>
        <path class="check" d="M34 62 L54 82 L88 44"></path>
      </svg>
    </div>

    <div id="message" class="msg center">
      <img id="logo" class="logo" src="/static/mycamplogo.png" alt="" />
      <h1>Successfully paired with chromecast</h1>
      <p class="footer">Close this window and cast from your favourite app.</p>
    </div>
  </main>

  <!-- Inject room name safely for the script -->
  <script>window.__ROOM__=${"\""}${roomName.replace("\"", "\\\"")}${"\""};</script>

  <script>
    (function(){
      function getRoom() {
        const url = new URL(window.location.href);
        const qp = url.searchParams.get('room') || url.searchParams.get('r');
        const hash = url.hash ? decodeURIComponent(url.hash.slice(1)) : "";
        const fromGlobal = typeof window.__ROOM__ === 'string' ? window.__ROOM__ : "";
        const parts = url.pathname.split('/').filter(Boolean);
        const idx = parts.lastIndexOf('pair');
        const fromPair = idx >= 0 && parts[idx+1] ? parts[idx+1] : '';
        const fromPath = fromPair || (parts[parts.length-1] || '');
        return (qp || hash || fromGlobal || fromPath || '').trim() || 'your room';
      }

      function confettiBurst(container, count) {
        count = count || 80;
        const colors = ['#10b981', '#22c55e', '#84cc16', '#f59e0b', '#3b82f6'];
        const frag = document.createDocumentFragment();
        const rect = container.getBoundingClientRect();
        const width = rect.width;
        for (let i=0; i<count; i++) {
          const piece = document.createElement('div');
          piece.className = 'confetti';
          piece.style.left = (Math.random()*width) + 'px';
          piece.style.background = colors[i % colors.length];
          piece.style.transform = 'translateY(-20px) rotate(' + (Math.random()*360) + 'deg)';
          const duration = 1400 + Math.random()*1200;
          piece.style.animationDuration = duration + 'ms';
          piece.style.animationDelay = (Math.random()*200) + 'ms';
          piece.style.borderRadius = (Math.random() < 0.5 ? '2px' : '999px');
          frag.appendChild(piece);
          setTimeout(function(){ piece.remove(); }, duration + 800);
        }
        container.appendChild(frag);
      }

      // (Optional) Use room if you show it somewhere with id="roomName"
      var roomEl = document.getElementById('roomName');
      if (roomEl) roomEl.textContent = getRoom();

      var tick = document.getElementById('tick');
      var message = document.getElementById('message');
      var card = document.querySelector('.card');
      var logo = document.getElementById('logo');

      setTimeout(function(){
        tick.classList.add('exit');
        setTimeout(function(){
          tick.classList.add('hidden');
          card.classList.remove('ghost');
          card.classList.add('reveal');

          var onCardReveal = function () {
            card.removeEventListener('animationend', onCardReveal);
            if (logo) {
              logo.classList.add('show');
              logo.addEventListener('animationend', function () { message.classList.add('show'); }, { once: true });
              setTimeout(function(){ message.classList.add('show'); }, 600);
            } else {
              message.classList.add('show');
            }
            // Confetti after reveal
            confettiBurst(card, 90);
          };
          card.addEventListener('animationend', onCardReveal);
          setTimeout(onCardReveal, 500);
        }, 600);
      }, 1100);

      if (window.CLOSE_AFTER_MS) {
        setTimeout(function(){ if (window.close) window.close(); }, window.CLOSE_AFTER_MS);
      }
    })();
  </script>
</body>
</html>
""".trimIndent()



fun main() {
    val dbUrl = System.getenv("POSTGRES_URL")
    val dbUser = System.getenv("POSTGRES_USER") ?: ""
    val dbPass = System.getenv("POSTGRES_PASSWORD") ?: ""
    if (dbUrl != null) {
        Database.connect(url = dbUrl, driver = "org.postgresql.Driver", user = dbUser, password = dbPass)
        transaction {
            SchemaUtils.createMissingTablesAndColumns(Rooms)
            if (Rooms.selectAll().empty()) {
                Rooms.insert { it[name] = "Sample Living Room"; it[deviceMac] = "B8:7B:D4:DC:DB:A5" }
                Rooms.insert { it[name] = "Sample Bedroom";    it[deviceMac] = "1A:2B:3C:4D:5E:6F" }
                Rooms.insert { it[name] = "Office (No Device)"; it[deviceMac] = null }
            }
        }
    }

    val rustServerBase = System.getenv("RUST_SERVER_BASE") ?: "http://localhost:3000"

    val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8088
    embeddedServer(Netty, port = port) {
        install(ForwardedHeaders)
        install(XForwardedHeaders)

        routing {
            // Dashboard
            get("/") {
                call.respondCoreUI("Dashboard", "dashboard") {
                    div(classes = "row") {
                        div(classes = "col-sm-6 col-lg-4") {
                            div(classes = "card text-white bg-primary") {
                                div(classes = "card-body") {
                                    h4(classes="card-title") { +"Manage Rooms" }
                                    p(classes="card-text") { +"Add, remove, and view QR codes for device pairing." }
                                    a(classes="btn btn-light", href="/rooms") { +"Go to Rooms" }
                                }
                            }
                        }
                        div(classes = "col-sm-6 col-lg-4") {
                            div(classes = "card text-white bg-info") {
                                div(classes = "card-body") {
                                    h4(classes="card-title") { +"Manage Pairings" }
                                    p(classes="card-text") { +"View and manage all active client-device pairings." }
                                    a(classes="btn btn-light", href="/pairings") { +"Go to Pairings" }
                                }
                            }
                        }
                        div(classes = "col-sm-6 col-lg-4") {
                            div(classes = "card text-white bg-warning") {
                                div(classes = "card-body") {
                                    h4(classes="card-title") { +"System Mode" }
                                    p(classes="card-text") { +"View and set the current casting service forwarding mode." }
                                    a(classes="btn btn-light", href="/mode") { +"Go to Mode" }
                                }
                            }
                        }
                    }
                }
            }

            // Pairings
            get("/pairings") {
                val pairings: List<Pairing> = try {
                    client.get("$rustServerBase/api/pairings").body()
                } catch (_: Exception) {
                    emptyList()
                }
                call.respondCoreUI("Manage Pairings", "pairings") {
                    div(classes = "row") {
                        div(classes = "col-md-8") {
                            div(classes = "card") {
                                div(classes = "card-header") { h4 { +"Current Pairings" } }
                                div(classes = "card-body") {
                                    if (pairings.isEmpty()) {
                                        p { +"No pairings found. Ensure the Rust server is running and reachable." }
                                    } else {
                                        div(classes="table-responsive") {
                                            table(classes = "table table-striped table-hover mb-0") {
                                                thead {
                                                    tr {
                                                        th { +"Client MAC" }
                                                        th { +"Device MAC" }
                                                        th { +"Actions" }
                                                    }
                                                }
                                                tbody {
                                                    for (p in pairings) {
                                                        tr {
                                                            td { +p.client_mac }
                                                            td { +p.device_mac }
                                                            td {
                                                                form(action = "/pairings/delete", method = FormMethod.post) {
                                                                    input(type = InputType.hidden, name = "client_mac") { value = p.client_mac }
                                                                    input(type = InputType.hidden, name = "device_mac") { value = p.device_mac }
                                                                    button(type=ButtonType.submit, classes="btn btn-danger btn-sm") { +"Delete" }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        div(classes = "col-md-4") {
                            div(classes = "card") {
                                div(classes = "card-header") { h4 { +"Add Pairing Manually" } }
                                div(classes = "card-body") {
                                    form(action = "/pairings/add", method = FormMethod.post) {
                                        div(classes="form-group") {
                                            label { +"Client MAC:" }
                                            textInput(name = "client_mac", classes="form-control") { required = true }
                                        }
                                        div(classes="form-group") {
                                            label { +"Device MAC:" }
                                            textInput(name = "device_mac", classes="form-control") { required = true }
                                        }
                                        button(type=ButtonType.submit, classes="btn btn-primary btn-block") { +"Add Pairing" }
                                    }
                                }
                            }
                        }
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

            // Mode
            get("/mode") {
                val status: ModeStatus = try {
                    client.get("$rustServerBase/api/mode").body()
                } catch (_: Exception) {
                    ModeStatus("Unknown")
                }
                call.respondCoreUI("System Mode", "mode") {
                    div(classes = "row justify-content-center") {
                        div(classes="col-md-6") {
                            div(classes="card") {
                                div(classes="card-header") { h4 { +"Forwarding Mode" } }
                                div(classes="card-body") {
                                    p { strong { +"Current Mode: " }; +status.mode }
                                    hr {}
                                    form(action = "/mode/set", method = FormMethod.post) {
                                        div(classes="form-group") {
                                            label { +"Select New Mode: " }
                                            select(classes="form-control") {
                                                name = "mode"
                                                option { value = "Auto"; +"Auto" }
                                                option { value = "Userspace"; +"Userspace" }
                                                option { value = "Xdp"; +"Xdp" }
                                            }
                                        }
                                        button(type=ButtonType.submit, classes="btn btn-success btn-block") { +"Update Mode" }
                                    }
                                }
                            }
                        }
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

            // Rooms
            get("/rooms") {
                val dbPresent = dbUrl != null
                val rooms: List<Room> = if (dbPresent) {
                    transaction {
                        Rooms.selectAll().map {
                            Room(it[Rooms.id].value, it[Rooms.name], it[Rooms.deviceMac])
                        }
                    }
                } else emptyList()

                call.respondCoreUI("Manage Rooms", "rooms") {
                    div(classes = "row") {
                        div(classes = "col-md-12") {
                            div(classes = "card") {
                                div(classes = "card-header") {
                                    div(classes = "d-flex justify-content-between align-items-center") {
                                        h4(classes = "mb-0") { +"Configured Rooms" }
                                        button(classes = "btn btn-primary") {
                                            attributes["data-toggle"] = "modal"
                                            attributes["data-target"] = "#addRoomModal"
                                            i(classes="cil-plus mr-2")
                                            +"Add New Room"
                                        }
                                    }
                                }
                                div(classes = "card-body") {
                                    if (!dbPresent) {
                                        div(classes="alert alert-warning") { +"PostgreSQL connection not configured to enable room management." }
                                    }
                                    div(classes="table-responsive") {
                                        table(classes="table table-striped table-hover mb-0") {
                                            thead {
                                                tr {
                                                    th { +"ID" }
                                                    th { +"Name" }
                                                    th { +"Device MAC" }
                                                    th { +"Pairing" }
                                                    th { +"Actions" }
                                                }
                                            }
                                            tbody {
                                                rooms.forEach { room ->
                                                    tr {
                                                        td { +room.id.toString() }
                                                        td { +room.name }
                                                        td { +(room.deviceMac ?: "N/A") }
                                                        td {
                                                            if (!room.deviceMac.isNullOrBlank()) {
                                                                button(classes = "btn btn-info btn-sm") {
                                                                    attributes["data-toggle"] = "modal"
                                                                    attributes["data-target"] = "#qrModal"
                                                                    attributes["data-roomid"] = room.id.toString()
                                                                    attributes["data-roomname"] = room.name
                                                                    +"Show QR"
                                                                }
                                                            }
                                                        }
                                                        td {
                                                            form(action = "/rooms/delete", method = FormMethod.post) {
                                                                input(type = InputType.hidden, name = "id") { value = room.id.toString() }
                                                                button(type=ButtonType.submit, classes="btn btn-danger btn-sm") { +"Delete" }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // QR Code Modal
                    div(classes = "modal fade") {
                        id = "qrModal"
                        attributes["tabindex"] = "-1"
                        div(classes = "modal-dialog modal-dialog-centered") {
                            div(classes = "modal-content") {
                                div(classes = "modal-header") {
                                    h5(classes = "modal-title") { id = "qrModalLabel" }
                                    button(type = ButtonType.button, classes = "close") {
                                        attributes["data-dismiss"] = "modal"
                                        span { unsafe { raw("&times;") } }
                                    }
                                }
                                div(classes = "modal-body text-center") {
                                    img(src = "", alt = "Pairing QR Code") { id = "qrCodeImg"; style = "max-width: 100%;" }
                                }
                            }
                        }
                    }

                    // Add Room Modal
                    div(classes = "modal fade") {
                        id = "addRoomModal"
                        attributes["tabindex"] = "-1"
                        div(classes = "modal-dialog modal-dialog-centered") {
                            div(classes = "modal-content") {
                                form(action = "/rooms/create", method = FormMethod.post) {
                                    div(classes = "modal-header") {
                                        h5(classes = "modal-title") { +"Add New Room" }
                                        button(type = ButtonType.button, classes = "close") {
                                            attributes["data-dismiss"] = "modal"
                                            span { unsafe { raw("&times;") } }
                                        }
                                    }
                                    div(classes = "modal-body") {
                                        div(classes="form-group") {
                                            label { +"Name:" }
                                            textInput(name = "name", classes="form-control") { required = true }
                                        }
                                        div(classes="form-group") {
                                            label { +"Device MAC: (optional)" }
                                            textInput(name = "device_mac", classes="form-control")
                                        }
                                    }
                                    div(classes = "modal-footer") {
                                        button(type = ButtonType.button, classes="btn btn-secondary") {
                                            attributes["data-dismiss"] = "modal"
                                            +"Cancel"
                                        }
                                        button(type=ButtonType.submit, classes="btn btn-primary") { +"Create Room" }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // QR code image (uses origin host/scheme/port; omits default ports)
            get("/qr-code/{roomId}") {
                val roomId = call.parameters["roomId"]?.toIntOrNull()
                if (roomId != null) {
                    val room = transaction {
                        Rooms.select { Rooms.id eq roomId }.map {
                            Room(it[Rooms.id].value, it[Rooms.name], it[Rooms.deviceMac])
                        }.singleOrNull()
                    }

                    if (!room?.deviceMac.isNullOrBlank()) {
                        val origin = call.request.origin
                        val portPart =
                            if ((origin.scheme == "http" && origin.serverPort == 80) ||
                                (origin.scheme == "https" && origin.serverPort == 443)) ""
                            else ":${origin.serverPort}"
                        val pairingUrl = "${origin.scheme}://${origin.serverHost}$portPart/pair-room/${room.id}"

                        val qrBytes = QRCode(pairingUrl).render().getBytes()
                        call.respondBytes(qrBytes, ContentType.Image.PNG)
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                } else {
                    call.respond(HttpStatusCode.BadRequest)
                }
            }

            // Handle pairing click
            get("/pair-room/{roomId}") {
                val roomId = call.parameters["roomId"]?.toIntOrNull()
                val clientIp = call.request.local.remoteHost
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
                    call.respondCoreUI("Pairing Failed", "") {
                        div(classes="card text-white bg-danger") {
                            div(classes="card-body") {
                                h4(classes="card-title") { +"Pairing Failed" }
                                p { +"Could not determine client MAC address from IP: $clientIp." }
                                p { +"This can happen if your device is on a different network subnet from the server." }
                                a(href="/rooms", classes="btn btn-light") { +"Back to Rooms" }
                            }
                        }
                    }
                    return@get
                }

                client.post("$rustServerBase/api/pairings") {
                    contentType(ContentType.Application.Json)
                    setBody(Pairing(clientMac, room.deviceMac))
                }

                call.respondCoreUI("Pairing Successful", "") {
                    div(classes="card text-white bg-success") {
                        div(classes="card-body") {
                            h4(classes="card-title") { +"Pairing Successful!" }
                            p { +"Your device ($clientMac) has been paired with ${room.name}." }
                            a(href="/rooms", classes="btn btn-light") { +"Back to Rooms" }
                        }
                    }
                }
            }

            // Rooms: create/delete
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
                    transaction { Rooms.deleteWhere { Rooms.id eq id } }
                }
                call.respondRedirect("/rooms")
            }
        }
    }.start(wait = true)
}
