package com.jkbms.gateway.server

import com.google.gson.GsonBuilder
import com.jkbms.gateway.ble.BleManager
import com.jkbms.gateway.ble.BmsRepository
import com.jkbms.gateway.ble.JkBmsDecoder
import fi.iki.elonen.NanoHTTPD

class BmsHttpServer(port: Int, private val bleManager: BleManager) : NanoHTTPD(port) {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    override fun serve(session: IHTTPSession): Response {
        val resp = when {
            session.uri == "/api"                     -> handleApi()
            session.uri == "/api/status"              -> handleStatus()
            session.uri == "/api/charge/on"           -> handleControl(JkBmsDecoder.CMD_CHG_ON,  "charge_on")
            session.uri == "/api/charge/off"          -> handleControl(JkBmsDecoder.CMD_CHG_OFF, "charge_off")
            session.uri == "/api/discharge/on"        -> handleControl(JkBmsDecoder.CMD_DSG_ON,  "discharge_on")
            session.uri == "/api/discharge/off"       -> handleControl(JkBmsDecoder.CMD_DSG_OFF, "discharge_off")
            session.uri == "/health"                  -> json("""{"status":"ok"}""")
            session.uri == "/" || session.uri == "/index.html" -> dashboard()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Cache-Control", "no-cache")
        return resp
    }

    private fun handleApi() = json(gson.toJson(BmsRepository.getCurrent()))

    private fun handleStatus(): Response {
        val d = BmsRepository.getCurrent()
        val s = mapOf(
            "connection_status" to d.connectionStatus,
            "device_name"       to d.deviceName,
            "device_address"    to d.deviceAddress,
            "soc"              to d.soc,
            "alarms"           to d.alarms,
            "charge_mosfet"    to d.chargeMosfet,
            "discharge_mosfet" to d.dischargeMosfet
        )
        return json(gson.toJson(s))
    }

    private fun handleControl(cmd: ByteArray, action: String): Response {
        return if (BmsRepository.getCurrent().connectionStatus == "CONNECTED") {
            bleManager.sendCommand(cmd)
            json("""{"success":true,"action":"$action"}""")
        } else {
            json("""{"success":false,"error":"BMS not connected"}""")
        }
    }

    private fun json(body: String) =
        newFixedLengthResponse(Response.Status.OK, "application/json", body)

    private fun dashboard() = newFixedLengthResponse(Response.Status.OK, "text/html", html())

    private fun html() = """<!DOCTYPE html>
<html lang="pl">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>JK BMS Gateway</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{background:#0d1117;color:#e6edf3;font-family:system-ui,monospace;padding:16px}
h1{color:#58a6ff;margin-bottom:4px}
.sub{color:#8b949e;font-size:12px;margin-bottom:16px}
.badge{display:inline-block;padding:3px 12px;border-radius:20px;font-size:12px;font-weight:700;margin-bottom:16px}
.CONNECTED{background:#1a3a1a;color:#3fb950;border:1px solid #3fb950}
.DISCONNECTED{background:#3a1a1a;color:#f85149;border:1px solid #f85149}
.CONNECTING,.CONNECTED_DISCOVERING{background:#2a2a1a;color:#d29922;border:1px solid #d29922}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:10px;margin-bottom:16px}
.card{background:#161b22;border:1px solid #30363d;border-radius:8px;padding:12px}
.lbl{color:#8b949e;font-size:10px;text-transform:uppercase;letter-spacing:.08em;margin-bottom:4px}
.val{font-size:1.5rem;font-weight:700;font-variant-numeric:tabular-nums}
.green{color:#3fb950}.blue{color:#58a6ff}.yellow{color:#d29922}.red{color:#f85149}
.cells{display:grid;grid-template-columns:repeat(auto-fill,minmax(80px,1fr));gap:6px;margin-bottom:16px}
.cell{background:#161b22;border:1px solid #30363d;border-radius:6px;padding:8px;text-align:center}
.cn{color:#8b949e;font-size:10px}.cv{font-size:.95rem;font-weight:600}
.btns{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:16px}
.btn{padding:12px;border:none;border-radius:8px;font-size:14px;font-weight:600;cursor:pointer}
.btn-on{background:#238636;color:#fff}.btn-off{background:#3a1a1a;color:#f85149;border:1px solid #f85149}
.alarm{background:#3a1a1a;border:1px solid #f85149;color:#f85149;border-radius:6px;padding:8px 12px;margin-bottom:6px;font-size:13px}
.no-alarm{color:#3fb950;font-size:13px;margin-bottom:16px}
h2{font-size:11px;color:#8b949e;text-transform:uppercase;letter-spacing:.08em;margin-bottom:8px;padding-bottom:4px;border-bottom:1px solid #30363d}
.api-box{background:#161b22;border:1px solid #30363d;border-radius:8px;padding:12px;margin-bottom:16px;font-size:12px;color:#8b949e}
.api-box a{color:#58a6ff;text-decoration:none}
</style>
</head>
<body>
<h1>⚡ JK BMS Gateway</h1>
<p class="sub">Auto-odświeżanie co 2s</p>
<div id="badge" class="badge DISCONNECTED">Ładowanie...</div>
<div id="device" style="color:#8b949e;font-size:12px;margin-bottom:16px">–</div>

<div class="grid">
  <div class="card"><div class="lbl">SOC</div><div class="val green" id="soc">–%</div></div>
  <div class="card"><div class="lbl">Napięcie</div><div class="val blue" id="volt">–V</div></div>
  <div class="card"><div class="lbl">Prąd</div><div class="val" id="curr">–A</div></div>
  <div class="card"><div class="lbl">Moc</div><div class="val yellow" id="pow">–W</div></div>
  <div class="card"><div class="lbl">Temp MOS</div><div class="val yellow" id="tmos">–°C</div></div>
  <div class="card"><div class="lbl">Temp S1</div><div class="val yellow" id="ts1">–°C</div></div>
  <div class="card"><div class="lbl">Pojemność</div><div class="val blue" id="cap">–Ah</div></div>
  <div class="card"><div class="lbl">Różnica cel</div><div class="val" id="diff">–mV</div></div>
</div>

<h2>Napięcia cel</h2>
<div class="cells" id="cells"></div>

<h2>Sterowanie MOSFET</h2>
<div class="btns">
  <button class="btn btn-on" onclick="ctrl('charge/on')">🔋 CHG ON</button>
  <button class="btn btn-off" onclick="ctrl('charge/off')">✖ CHG OFF</button>
  <button class="btn btn-on" onclick="ctrl('discharge/on')">⚡ DSG ON</button>
  <button class="btn btn-off" onclick="ctrl('discharge/off')">✖ DSG OFF</button>
</div>
<div id="ctrl-status" style="color:#8b949e;font-size:12px;margin-bottom:16px"></div>

<h2>Alarmy</h2>
<div id="alarms"></div>

<h2>API</h2>
<div class="api-box">
  <div><a href="/api">/api</a> — pełne dane JSON</div>
  <div><a href="/api/status">/api/status</a> — status połączenia</div>
  <div><a href="/api/charge/on">/api/charge/on</a> — włącz ładowanie</div>
  <div><a href="/api/charge/off">/api/charge/off</a> — wyłącz ładowanie</div>
  <div><a href="/api/discharge/on">/api/discharge/on</a> — włącz rozładowanie</div>
  <div><a href="/api/discharge/off">/api/discharge/off</a> — wyłącz rozładowanie</div>
</div>

<script>
const f=(v,d=2)=>typeof v==='number'?v.toFixed(d):'–';
async function refresh(){
  try{
    const d=await fetch('/api').then(r=>r.json());
    const badge=document.getElementById('badge');
    badge.textContent=d.connection_status;
    badge.className='badge '+(d.connection_status||'DISCONNECTED');
    document.getElementById('device').textContent=(d.device_name||'–')+' ['+( d.device_address||'–')+']';
    document.getElementById('soc').textContent=d.soc+'%';
    document.getElementById('volt').textContent=f(d.total_voltage)+'V';
    document.getElementById('curr').textContent=f(d.current)+'A';
    document.getElementById('pow').textContent=f(d.power,0)+'W';
    document.getElementById('tmos').textContent=f(d.temperature_mos,1)+'°C';
    document.getElementById('ts1').textContent=f(d.temperature_sensor1,1)+'°C';
    document.getElementById('cap').textContent=f(d.remaining_capacity)+'/'+f(d.nominal_capacity)+'Ah';
    document.getElementById('diff').textContent=f(d.cell_voltage_diff,1)+'mV';
    const mn=Math.min(...(d.cell_voltages||[0])),mx=Math.max(...(d.cell_voltages||[0]));
    document.getElementById('cells').innerHTML=(d.cell_voltages||[]).map((v,i)=>{
      const cls=v<=mn?'red':v>=mx?'yellow':'green';
      return'<div class="cell"><div class="cn">C'+(i+1)+'</div><div class="cv '+cls+'">'+v.toFixed(3)+'</div></div>';
    }).join('');
    const al=document.getElementById('alarms');
    al.innerHTML=d.alarms&&d.alarms.length?d.alarms.map(a=>'<div class="alarm">⚠ '+a+'</div>').join(''):'<p class="no-alarm">✓ Brak alarmów</p>';
  }catch(e){console.error(e)}
}
async function ctrl(action){
  try{
    const r=await fetch('/api/'+action).then(r=>r.json());
    document.getElementById('ctrl-status').textContent=r.success?'✓ Komenda wysłana: '+action:'✖ Błąd: '+r.error;
  }catch(e){document.getElementById('ctrl-status').textContent='✖ Błąd połączenia';}
}
refresh();setInterval(refresh,2000);
</script>
</body>
</html>""".trimIndent()
}
