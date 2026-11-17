#!/usr/bin/env python3
"""Repeatable live end-to-end suite for SmartHomeAI (SIMULATED devices: no physical ESP32 involved).

Runs against a disposable stack (its own compose project and volumes), never a developer's own data.
Env: API (default http://localhost:18082), MQ_CONTAINER (mosquitto container name), OUT (json report path).
Devices are simulated with mosquitto_pub/mosquitto_sub run inside the broker container, using the real
credentials issued by the pairing flow, so broker authentication and per-device ACLs are exercised for real.
"""
import json, os, subprocess, sys, time, urllib.request, urllib.error, uuid, base64, hmac, hashlib

API = os.environ.get("API", "http://127.0.0.1:18082")
MQ = os.environ.get("MQ_CONTAINER", "shp2-mosquitto-1")
OUT = os.environ.get("OUT", "e2e_results.json")
RES = []


def rec(name, purpose, ok, evidence, status=None):
    RES.append({"test": name, "purpose": purpose, "status": status or ("PASS" if ok else "FAIL"),
                "evidence": str(evidence)[:400], "mode": "simulated"})
    print(f"[{RES[-1]['status']}] {name}: {str(evidence)[:160]}", flush=True)


def req(method, path, token=None, body=None, raw=False, headers=None):
    h = {"Content-Type": "application/json"}
    if token:
        h["Authorization"] = "Bearer " + token
    if headers:
        h.update(headers)
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(API + path, data=data, method=method, headers=h)
    try:
        for attempt in (1, 2):
            try:
                with urllib.request.urlopen(r, timeout=25) as resp:
                    b = resp.read()
                    return resp.status, (b if raw else (json.loads(b) if b else None)), dict(resp.headers)
            except (TimeoutError, ConnectionError, urllib.error.URLError) as te:
                if isinstance(te, urllib.error.HTTPError):
                    raise
                if attempt == 2 or method not in ("GET", "OPTIONS"):
                    raise
                print(f"  (retry after {type(te).__name__} on {method} {path})", flush=True)
    except urllib.error.HTTPError as e:
        b = e.read()
        try:
            return e.code, json.loads(b), dict(e.headers)
        except Exception:
            return e.code, b, dict(e.headers)


def sh(args, timeout=30):
    p = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    return p.returncode, (p.stdout + p.stderr)


def mq_pub(u, pw, topic, msg, retain=False, v5=True, qos=1):
    a = ["docker", "exec", MQ, "mosquitto_pub", "-h", "localhost", "-p", "1883", "-t", topic, "-m", msg, "-q", str(qos)]
    if u is not None:
        a += ["-u", u, "-P", pw]
    if retain:
        a.append("-r")
    if v5:
        a += ["-V", "mqttv5"]
    return sh(a)


def mq_sub(u, pw, topic, secs=4, v5=True):
    a = ["docker", "exec", MQ, "mosquitto_sub", "-h", "localhost", "-p", "1883", "-t", topic, "-W", str(secs), "-v", "-d"]
    if u is not None:
        a += ["-u", u, "-P", pw]
    if v5:
        a += ["-V", "mqttv5"]
    return sh(a, timeout=secs + 20)


def mq_listen(u, pw, topic, secs):
    a = ["docker", "exec", MQ, "mosquitto_sub", "-h", "localhost", "-p", "1883", "-t", topic, "-W", str(secs),
         "-u", u, "-P", pw, "-V", "mqttv5"]
    return subprocess.Popen(a, stdout=subprocess.PIPE, text=True)


def wait(cond, secs=15, step=0.5):
    end = time.time() + secs
    while time.time() < end:
        v = cond()
        if v:
            return v
        time.sleep(step)
    return None


def b64(d):
    return base64.urlsafe_b64encode(d).rstrip(b"=").decode()


RUN = uuid.uuid4().hex[:6]


def register(tag):
    u, e, p = f"{tag}{RUN}", f"{tag}{RUN}@example.test", "Passw0rd-" + RUN
    s, b, _ = req("POST", "/api/auth/register", body={"username": u, "email": e, "password": p})
    return u, e, p, s, b


def login(u, e, p):
    for body in ({"username": u, "password": p}, {"email": e, "password": p}, {"usernameOrEmail": u, "password": p}):
        s, b, _ = req("POST", "/api/auth/login", body=body)
        if s == 200 and isinstance(b, dict):
            return b.get("token") or b.get("accessToken"), s, b
    return None, s, b


def pair(token, name, dev_id):
    s, b, _ = req("POST", "/api/devices/pairing-codes", token, {"name": name})
    code = b["code"]
    s2, b2, _ = req("POST", "/api/device/pair", body={"pairingCode": code, "deviceId": dev_id})
    return code, s2, b2


def tele(u, pw, dev, seq, **kw):
    d = {"deviceId": dev, "seq": seq}
    d.update(kw)
    return mq_pub(u, pw, f"home/devices/{dev}/telemetry", json.dumps(d))


def device_view(token, uid):
    return req("GET", f"/api/devices/{uid}", token)[1] or {}


def cmd_status(tok, uid, cid):
    return (req("GET", f"/api/devices/{uid}/commands/{cid}", tok)[1] or {}).get("status")


def main():
    s, b, _ = req("GET", "/api/health")
    rec("health.public", "Public health endpoint is up", s == 200 and b.get("status") == "UP", f"{s} {b}")

    # ---------------- JWT / auth ----------------
    uA, eA, pA, s, b = register("alice")
    rec("auth.register", "Register a user (token issued, no password/hash leaked)",
        s in (200, 201) and "password" not in json.dumps(b).lower(), f"{s} keys={list(b) if isinstance(b, dict) else b}")
    s, b, _ = req("POST", "/api/auth/register", body={"username": uA, "email": eA, "password": pA})
    rec("auth.register.duplicate", "Duplicate username/email rejected", s == 409, s)
    s, b, _ = req("POST", "/api/auth/register", body={"username": "x" + RUN, "email": "bad", "password": "short"})
    rec("auth.register.validation", "Invalid email/short password rejected", s == 400, s)
    s, b, _ = req("POST", "/api/auth/login", body={"username": uA, "email": eA, "password": "wrong-password"})
    rec("auth.login.wrong_password", "Wrong password -> 401", s in (400, 401), s)
    tokA, s, bl = login(uA, eA, pA)
    rec("auth.login", "Valid login yields JWT", bool(tokA), f"{s} {list(bl) if isinstance(bl, dict) else bl}")
    uB, eB, pB, s, bB = register("bob")
    tokB, s, bl = login(uB, eB, pB)
    s, b, _ = req("GET", "/api/auth/me", tokA)
    rec("auth.me", "Authenticated /me returns own user", s == 200 and uA in json.dumps(b), f"{s} {b}")
    s, b, _ = req("GET", "/api/devices")
    rec("jwt.no_token", "No token -> 401", s == 401, s)
    s, b, _ = req("GET", "/api/devices", "garbage.token.value")
    rec("jwt.garbage", "Garbage token -> 401", s == 401, s)
    h, p_, sig = tokA.split(".")
    tampered = h + "." + p_[:-2] + ("AA" if not p_.endswith("AA") else "BB") + "." + sig
    s, b, _ = req("GET", "/api/devices", tampered)
    rec("jwt.tampered_payload", "Modified payload with old signature -> 401", s == 401, s)
    none_tok = b64(b'{"alg":"none","typ":"JWT"}') + "." + p_ + "."
    s, b, _ = req("GET", "/api/devices", none_tok)
    rec("jwt.alg_none", "alg=none token -> 401", s == 401, s)
    claims = json.loads(base64.urlsafe_b64decode(p_ + "=" * (-len(p_) % 4)))
    forged_h = b64(b'{"alg":"HS256","typ":"JWT"}')
    forged_p = b64(json.dumps(claims).encode())
    forged_sig = b64(hmac.new(b"guessed-secret-guessed-secret-123456", (forged_h + "." + forged_p).encode(), hashlib.sha256).digest())
    s, b, _ = req("GET", "/api/devices", forged_h + "." + forged_p + "." + forged_sig)
    rec("jwt.forged_signature", "Token signed with a wrong key -> 401", s == 401, s)
    claims2 = dict(claims)
    claims2["exp"] = int(time.time()) - 3600
    s, b, _ = req("GET", "/api/devices", h + "." + b64(json.dumps(claims2).encode()) + "." + sig)
    rec("jwt.expired_claims", "Expired claims with original signature -> 401", s == 401, s)
    rec("jwt.claims", "JWT carries issuer + expiry", claims.get("iss") == "smarthome-backend" and "exp" in claims,
        {k: claims[k] for k in claims if k != "sub"})
    s, b, hd = req("OPTIONS", "/api/devices", headers={"Origin": "https://evil.example", "Access-Control-Request-Method": "GET"})
    rec("cors.foreign_origin", "Preflight from a non-allowed origin gets no allow-origin header",
        "access-control-allow-origin" not in {k.lower() for k in hd}, f"status={s}")
    s, b, hd = req("OPTIONS", "/api/devices", headers={"Origin": "http://localhost:5173", "Access-Control-Request-Method": "GET"})
    rec("cors.allowed_origin", "Preflight from the configured origin allowed",
        s == 200 and hd.get("Access-Control-Allow-Origin") == "http://localhost:5173", f"{s} {hd.get('Access-Control-Allow-Origin')}")

    # ---------------- pairing ----------------
    devA = "esp32-a" + RUN + "01"
    s, b, _ = req("POST", "/api/device/pair", body={"pairingCode": "ZZZZZZ", "deviceId": devA})
    rec("pair.bad_code", "Unknown pairing code rejected", s in (400, 404, 409, 422), s)
    code, s, pr = pair(tokA, "Alice ESP32", devA)
    credA = pr.get("credential") if isinstance(pr, dict) else None
    rec("pair.ok", "Pairing code exchanged for a device credential", s == 200 and bool(credA), f"{s} deviceId={pr.get('deviceId') if isinstance(pr, dict) else pr}")
    s, b, _ = req("POST", "/api/device/pair", body={"pairingCode": code, "deviceId": "esp32-other-" + RUN})
    rec("pair.code_reuse", "Pairing code is single-use", s != 200, s)
    s, b, _ = req("GET", "/api/device/me", headers={"X-Device-Id": devA, "X-Device-Key": "wrong-key"})
    rec("device_auth.bad_cred", "Device endpoint rejects a wrong key", s == 401, s)
    s, b, _ = req("GET", "/api/device/me")
    rec("device_auth.missing_cred", "Device endpoint rejects missing credentials", s == 401, s)
    s, b, _ = req("GET", "/api/device/me", tokA)
    rec("device_auth.user_jwt_rejected", "A user JWT cannot act as a device", s in (401, 403), s)
    s, b, _ = req("GET", "/api/devices", headers={"X-Device-Id": devA, "X-Device-Key": credA})
    rec("device_auth.cred_on_user_api", "A device credential cannot call the user API", s in (401, 403), s)
    s, b, _ = req("GET", "/api/device/me", headers={"X-Device-Id": devA, "X-Device-Key": credA})
    rec("device_auth.valid", "Valid device credential accepted", s == 200, f"{s} {b}")
    s, lst, _ = req("GET", "/api/devices", tokA)
    uidA = next(d["id"] for d in lst if d["deviceId"] == devA)
    devB = "esp32-b" + RUN + "01"
    _, s, prB = pair(tokB, "Bob ESP32", devB)
    credB = prB["credential"]
    uidB = req("GET", "/api/devices", tokB)[1][0]["id"]
    rec("pair.two_tenants", "Second user pairs an independent device", bool(credB) and credB != credA, f"A={uidA} B={uidB}")

    # ---------------- tenant isolation ----------------
    s, lstB2, _ = req("GET", "/api/devices", tokB)
    rec("tenant.list", "Bob's device list excludes Alice's device", all(d["deviceId"] != devA for d in lstB2), [d["deviceId"] for d in lstB2])
    for name, m, path, body in [
        ("get_device", "GET", f"/api/devices/{uidA}", None),
        ("telemetry", "GET", f"/api/devices/{uidA}/telemetry", None),
        ("rename", "PATCH", f"/api/devices/{uidA}", {"name": "hacked"}),
        ("delete", "DELETE", f"/api/devices/{uidA}", None),
        ("command", "POST", f"/api/devices/{uidA}/commands", {"action": "set_pir_led", "value": "on"}),
        ("command_list", "GET", f"/api/devices/{uidA}/commands", None),
        ("ml_status", "GET", f"/api/devices/{uidA}/ml/status", None),
        ("ml_train", "POST", f"/api/devices/{uidA}/ml/occupancy/train", None),
        ("anomalies", "GET", f"/api/devices/{uidA}/ml/anomalies", None),
        ("twin", "GET", f"/api/devices/{uidA}/twin", None),
        ("report_for_foreign_device", "POST", "/api/reports", {"kind": "daily", "deviceId": uidA}),
    ]:
        s, b, _ = req(m, path, tokB, body)
        rec(f"tenant.{name}", f"Bob cannot use '{name}' on Alice's device", s in (400, 403, 404), s)
    s, b, _ = req("GET", f"/api/devices/{uidA}", tokA)
    rec("tenant.alice_intact", "Alice's device is intact and not renamed", s == 200 and b["name"] == "Alice ESP32", b.get("name"))

    # ---------------- MQTT auth / ACL ----------------
    rc, o = mq_pub(None, None, f"home/devices/{devA}/telemetry", "{}")
    rec("mqtt.anonymous_denied", "Anonymous MQTT connection refused", rc != 0, o.strip()[-200:])
    rc, o = mq_pub(devA, "wrong-password", f"home/devices/{devA}/telemetry", "{}")
    rec("mqtt.bad_password_denied", "Wrong device password refused", rc != 0, o.strip()[-200:])
    rc, o = tele(devA, credA, devA, 1, temperature=24.5, humidity=51.0, motion=False, gasRaw=300)
    ok = wait(lambda: (device_view(tokA, uidA).get("latestTelemetry") or {}).get("temperatureC") == 24.5)
    rec("mqtt.valid_publish_ingested", "Authenticated device telemetry is stored and visible via the API", bool(ok), f"pub rc={rc}; latest={device_view(tokA, uidA).get('latestTelemetry')}")
    rc, o = tele(devA, credA, devB, 1, temperature=77.7)
    time.sleep(2)
    after = device_view(tokB, uidB).get("latestTelemetry") or {}
    rec("mqtt.acl_publish_other_device", "Device A cannot publish telemetry into device B's topic", after.get("temperatureC") != 77.7, f"rc={rc} B.temp={after.get('temperatureC')} out={o.strip()[-100:]}")
    rc, o = mq_sub(devA, credA, f"home/devices/{devB}/commands", 3)
    rec("mqtt.acl_subscribe_other_commands", "Device A cannot subscribe to B's command topic", "): 135" in o or "): 128" in o or "not authori" in o.lower(), o.strip()[-200:])
    rc, o = mq_sub(devA, credA, "#", 3)
    rec("mqtt.acl_wildcard_denied", "Device cannot subscribe to '#'", "): 135" in o or "): 128" in o or "not authori" in o.lower(), o.strip()[-200:])
    rc, o = mq_sub(devA, credA, "$CONTROL/dynamic-security/#", 3)
    rec("mqtt.acl_dynsec_denied", "Device cannot touch Dynamic-Security control topics", "): 135" in o or "): 128" in o or "not authori" in o.lower(), o.strip()[-200:])
    rc, o = mq_sub(devA, credA, f"home/devices/{devA}/commands", 3)
    rec("mqtt.acl_subscribe_own_commands", "Device may subscribe to its own commands topic", "Subscribed (mid: 1): 0" in o or "Subscribed (mid: 1): 1" in o, o.strip()[-160:])
    sub = mq_listen(devA, credA, f"home/devices/{devA}/commands", 6)
    time.sleep(1.5)
    mq_pub(devA, credA, f"home/devices/{devA}/commands", '{"spoof":true}')
    out, _ = sub.communicate()
    rec("mqtt.acl_device_cannot_publish_commands", "A device cannot publish on a commands topic (only the backend can)", "spoof" not in out, repr(out[:100]))

    # ---------------- telemetry validation ----------------
    def latest():
        return device_view(tokA, uidA).get("latestTelemetry") or {}

    tele(devA, credA, devA, 2, temperature=999)
    time.sleep(1.5)
    rec("telemetry.out_of_range", "Out-of-range temperature (999) is not stored", latest().get("temperatureC") != 999, latest().get("temperatureC"))
    mq_pub(devA, credA, f"home/devices/{devA}/telemetry", "not json {{")
    mq_pub(devA, credA, f"home/devices/{devA}/telemetry", json.dumps({"deviceId": "someone-else", "seq": 3, "temperature": 31.1}))
    time.sleep(1.5)
    rec("telemetry.malformed_and_mismatch", "Malformed JSON and spoofed payload deviceId are ignored", latest().get("temperatureC") != 31.1, latest().get("temperatureC"))
    mq_pub(devA, credA, f"home/devices/{devA}/telemetry", "x" * 20000)
    mq_pub(devA, credA, f"home/devices/{devA}/telemetry", json.dumps({"deviceId": devA, "seq": 4, "temperature": 26.5}), retain=True)
    time.sleep(1.5)
    def count_265():
        r = req("GET", f"/api/devices/{uidA}/telemetry", tokA)[1]
        r = r if isinstance(r, list) else (r or {}).get("readings", (r or {}).get("items", []))
        return sum(1 for x in r if x.get("temperatureC") == 26.5)
    c_live = count_265()
    rec("telemetry.retained_live_stored_once", "A retained publish seen live (retain flag cleared by broker) is stored exactly once", c_live == 1, f"count={c_live}")
    tele(devA, credA, devA, 5, temperature=23.0, humidity=48.0, motion=False, gasRaw=310)
    ok = wait(lambda: latest().get("temperatureC") == 23.0)
    rec("telemetry.valid_followup", "Backend keeps ingesting after bad input", bool(ok), latest())
    for i in range(6, 12):
        tele(devA, credA, devA, i, temperature=22.0 + i / 10, humidity=47.0, motion=bool(i % 2), gasRaw=300 + i)
    time.sleep(2)
    s, hist, _ = req("GET", f"/api/devices/{uidA}/telemetry", tokA)
    n = len(hist) if isinstance(hist, list) else (len(hist.get("readings", hist.get("items", []))) if isinstance(hist, dict) else 0)
    rec("telemetry.history", "Historical readings are queryable", s == 200 and n >= 6, f"{s} n={n}")

    # ---------------- online/offline ----------------
    mq_pub(devA, credA, f"home/devices/{devA}/status", json.dumps({"deviceId": devA, "state": "offline"}))
    ok = wait(lambda: "OFFLINE" in str(device_view(tokA, uidA).get("connection")).upper())
    rec("presence.offline", "Device 'offline' status is reflected", bool(ok), device_view(tokA, uidA).get("connection"))
    tele(devA, credA, devA, 20, temperature=23.5, humidity=48.0, motion=False, gasRaw=300)
    ok = wait(lambda: "ONLINE" in str(device_view(tokA, uidA).get("connection")).upper())
    rec("presence.online_again", "Fresh telemetry brings the device back online", bool(ok), device_view(tokA, uidA).get("connection"))

    # ---------------- commands ----------------
    s, b, _ = req("POST", f"/api/devices/{uidA}/commands", tokA, {"action": "set_gas_led", "value": "off"})
    rec("command.gas_led_off_rejected", "SAFETY: the gas LED cannot be set to 'off'", s == 400, f"{s} {str(b)[:100]}")
    s, b, _ = req("POST", f"/api/devices/{uidA}/commands", tokA, {"action": "format_disk", "value": "on"})
    rec("command.unknown_action", "Unknown action rejected", s == 400, s)
    s, b, _ = req("POST", f"/api/devices/{uidA}/commands", tokA, {"action": "set_pir_led", "value": "maybe"})
    rec("command.invalid_value", "Invalid value rejected", s == 400, s)
    s, b, _ = req("POST", f"/api/devices/{uidA}/commands", None, {"action": "set_pir_led", "value": "on"})
    rec("command.unauthenticated", "Unauthenticated command -> 401", s == 401, s)
    sub = mq_listen(devA, credA, f"home/devices/{devA}/commands", 8)
    time.sleep(2)
    s, cmd, _ = req("POST", f"/api/devices/{uidA}/commands", tokA, {"action": "set_pir_led", "value": "on"})
    out, _ = sub.communicate()
    rec("command.delivered_over_mqtt", "An authenticated command reaches the device's MQTT command topic",
        s in (200, 201, 202) and cmd["id"] in out, f"{s} status={cmd.get('status')} payload={out.strip()[:140]}")
    mq_pub(devA, credA, f"home/devices/{devA}/acks", json.dumps({"id": cmd["id"], "status": "applied"}))
    ok = wait(lambda: cmd_status(tokA, uidA, cmd["id"]) == "ACKNOWLEDGED")
    rec("command.ack", "A device ack moves the command to ACKNOWLEDGED", bool(ok), cmd_status(tokA, uidA, cmd["id"]))
    s, cmd2, _ = req("POST", f"/api/devices/{uidA}/commands", tokA, {"action": "set_pir_led", "value": "off"})
    mq_pub(devB, credB, f"home/devices/{devB}/acks", json.dumps({"id": cmd2["id"], "status": "applied"}))
    time.sleep(2)
    st = cmd_status(tokA, uidA, cmd2["id"])
    rec("command.foreign_ack_ignored", "Another device's ack cannot complete my command", st != "ACKNOWLEDGED", st)
    t0 = time.time()
    ok = wait(lambda: cmd_status(tokA, uidA, cmd2["id"]) in ("EXPIRED", "FAILED", "UNKNOWN"), secs=90, step=3)
    rec("command.ttl_expiry", "A sent-but-unacknowledged command leaves SENT after its TTL (30s): EXPIRED/UNKNOWN, never ACKNOWLEDGED", bool(ok), f"status={cmd_status(tokA, uidA, cmd2['id'])} after {time.time() - t0:.0f}s")

    # ---------------- automation / relay safety ----------------
    s, b, _ = req("POST", "/api/automations", tokA, {"name": "hot room", "deviceId": uidA, "trigger": {"type": "TELEMETRY"}, "logic": "AND",
                  "conditions": [{"sensor": "temperature", "operator": ">", "value": 30}],
                  "action": {"action": "set_pir_led", "value": "on"}, "cooldownSeconds": 60})
    rid = b.get("id") if isinstance(b, dict) else None
    rec("automation.create", "Create a telemetry rule", s in (200, 201) and bool(rid), f"{s} {str(b)[:150]}")
    s, b, _ = req("POST", "/api/automations", tokB, {"name": "steal", "deviceId": uidA, "trigger": {"type": "TELEMETRY"}, "logic": "AND",
                  "conditions": [{"sensor": "temperature", "operator": ">", "value": 1}],
                  "action": {"action": "set_pir_led", "value": "on"}})
    rec("automation.tenant_create", "Bob cannot create a rule on Alice's device", s in (400, 403, 404), s)
    s, b, _ = req("POST", "/api/automations", tokA, {"name": "bad", "deviceId": uidA, "trigger": {"type": "TELEMETRY"}, "logic": "AND",
                  "conditions": [{"sensor": "gas", "operator": ">", "value": 100}],
                  "action": {"action": "set_gas_led", "value": "off"}})
    rec("automation.gas_led_off_rule_rejected", "SAFETY: a rule that turns the gas LED off is rejected", s == 400, f"{s} {str(b)[:120]}")
    s, b, _ = req("GET", f"/api/automations/{rid}", tokB)
    rec("automation.tenant_read", "Bob cannot read Alice's rule", s in (403, 404), s)
    tele(devA, credA, devA, 30, temperature=35.0, humidity=40.0, motion=False, gasRaw=300)

    def execs():
        r = req("GET", f"/api/automations/{rid}/executions", tokA)[1]
        return r if isinstance(r, list) else (r or {}).get("items", []) if isinstance(r, dict) else []
    ok = wait(lambda: execs(), secs=15)
    rec("automation.fires", "Temperature above threshold fires the rule", bool(ok), [(e.get("outcome"), e.get("commandStatus")) for e in execs()][:3])
    tele(devA, credA, devA, 31, temperature=36.0, humidity=40.0, motion=False, gasRaw=300)
    time.sleep(3)
    ex2 = execs()
    sent = [e for e in ex2 if e.get("commandId")]
    rec("automation.cooldown", "Cooldown prevents a second command within 60s", len(sent) <= 1, f"executions={[(e.get('outcome'), e.get('repeatCount')) for e in ex2]}")

    # ---------------- gas alert + motion/away ----------------
    tele(devA, credA, devA, 40, temperature=24.0, humidity=45.0, motion=False, gasRaw=3300)
    tele(devA, credA, devA, 41, temperature=24.0, humidity=45.0, motion=False, gasRaw=3400)
    ok = wait(lambda: (req("GET", "/api/alerts/gas", tokA)[1] or []) != [])
    ga = req("GET", "/api/alerts/gas", tokA)[1]
    rec("gas.incident_recorded", "Two gas readings above the threshold open an incident", bool(ok), str(ga)[:200])
    gb = req("GET", "/api/alerts/gas", tokB)[1]
    rec("gas.tenant_isolation", "Bob cannot see Alice's gas incidents", devA not in json.dumps(gb) and uidA not in json.dumps(gb), str(gb)[:120])
    tele(devA, credA, devA, 42, temperature=24.0, humidity=45.0, motion=False, gasRaw=300)
    tele(devA, credA, devA, 43, temperature=24.0, humidity=45.0, motion=False, gasRaw=290)
    s, b, _ = req("PUT", "/api/security/mode", tokA, {"mode": "AWAY"})
    rec("security.away", "Switch to AWAY mode", s == 200, f"{s} {str(b)[:100]}")
    tele(devA, credA, devA, 50, temperature=24.0, humidity=45.0, motion=True, gasRaw=290)
    ok = wait(lambda: "MOTION" in json.dumps(req("GET", "/api/security/events", tokA)[1]))
    rec("security.motion_alert", "Motion while AWAY raises a MOTION_ALERT event", bool(ok), str(req("GET", "/api/security/events", tokA)[1])[:200])
    s, b, _ = req("GET", "/api/security/events", tokB)
    rec("security.tenant_isolation", "Bob sees none of Alice's security events", uidA not in json.dumps(b) and devA not in json.dumps(b), s)
    req("PUT", "/api/security/mode", tokA, {"mode": "HOME"})

    # ---------------- reports / PDF ----------------
    s, rep, _ = req("POST", "/api/reports", tokA, {"kind": "daily"})
    rid2 = rep.get("id") if isinstance(rep, dict) else None
    rec("report.generate", "Generate a daily report", s in (200, 201) and bool(rid2), f"{s} {str(rep)[:140]}")
    s, pdf, hd = req("GET", f"/api/reports/{rid2}/pdf", tokA, raw=True)
    rec("report.pdf", "PDF download is a valid PDF", s == 200 and pdf[:5] == b"%PDF-" and b"%%EOF" in pdf[-32:], f"{s} {len(pdf)} bytes ct={hd.get('Content-Type')}")
    open(os.path.join(os.path.dirname(OUT) or ".", "sample_report.pdf"), "wb").write(pdf)
    s, b, _ = req("GET", f"/api/reports/{rid2}/pdf", tokB, raw=True)
    rec("report.tenant_pdf", "Bob cannot download Alice's report PDF", s in (403, 404), s)
    s, b, _ = req("GET", f"/api/reports/{rid2}", tokB)
    rec("report.tenant_detail", "Bob cannot read Alice's report", s in (403, 404), s)
    s, b, _ = req("GET", "/api/reports", tokB)
    rec("report.tenant_list", "Bob's report list excludes Alice's report", s == 200 and rid2 not in json.dumps(b), s)
    s, b, _ = req("POST", "/api/reports", tokA, {"kind": "bogus"})
    rec("report.invalid_kind", "Invalid report kind -> 400", s == 400, s)
    s, b, _ = req("POST", "/api/reports", tokA, {"kind": "custom", "from": "2026-01-10", "to": "2026-01-01"})
    rec("report.invalid_range", "Reversed date range rejected", s == 400, s)

    # ---------------- ML ----------------
    s, b, _ = req("GET", f"/api/devices/{uidA}/ml/status", tokA)
    rec("ml.status", "ML status for own device", s == 200, f"{s} {str(b)[:120]}")
    s, b, _ = req("POST", f"/api/devices/{uidA}/ml/anomalies/scan", tokA)
    rec("ml.anomaly_scan", "Anomaly scan runs on own device", s in (200, 202), f"{s} {str(b)[:120]}")

    # ---------------- reconnect: broker restart ----------------
    sh(["docker", "restart", MQ])
    wait(lambda: sh(["docker", "inspect", "-f", "{{.State.Health.Status}}", MQ])[1].strip() == "healthy", 60, 2)
    ok = None
    for i in range(45):
        v = 21.0 + i / 100
        tele(devA, credA, devA, 100 + i, temperature=v, humidity=44.0, motion=False, gasRaw=290)
        time.sleep(2)
        if abs((latest().get("temperatureC") or 0) - v) < 1e-6:
            ok = i
            break
    c_after = count_265()
    rec("telemetry.retained_replay_not_restored", "After a restart the broker replays the retained message on resubscribe; the backend must not store it again", c_after == c_live, f"before={c_live} after={c_after}")
    mq_pub(devA, credA, f"home/devices/{devA}/telemetry", "", retain=True)
    rec("reconnect.broker_restart", "After a broker restart the backend resubscribes and the device can publish again (same credentials)",
        ok is not None, f"first reading stored on attempt {ok} (~{(ok or 0) * 2}s)")
    s, b, _ = req("GET", "/api/system/status", tokA)
    rec("system.status", "System status endpoint after reconnect", s == 200, f"{s} {str(b)[:200]}")

    # ---------------- device deletion revokes broker access ----------------
    s, b, _ = req("DELETE", f"/api/devices/{uidB}", tokB)
    time.sleep(2)
    rc, o = mq_pub(devB, credB, f"home/devices/{devB}/telemetry", json.dumps({"deviceId": devB, "seq": 1, "temperature": 20.0}))
    rec("device.delete_revokes_mqtt", "A removed device's broker account is revoked", s in (200, 204) and rc != 0, f"delete={s} pub rc={rc} {o.strip()[-100:]}")

    json.dump({"api": API, "run": RUN, "results": RES}, open(OUT, "w"), indent=1)
    p = sum(r["status"] == "PASS" for r in RES)
    f = sum(r["status"] == "FAIL" for r in RES)
    print(f"\nTOTAL {len(RES)}  PASS {p}  FAIL {f}")
    return 1 if f else 0


if __name__ == "__main__":
    sys.exit(main())
