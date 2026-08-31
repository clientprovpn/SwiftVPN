# SwiftVPN 3p — v5.1.1 (versionCode 61) mega-build

## Scope (all approved by user, single build)
1. **IKEv2 EAP-PEAP/EAP-TTLS plugins** (Surfshark dedicated-IP fix) — Android.mk DONE
2. **IKEv2 encryption selector UI** (AES-256-GCM / ChaCha20-Poly1305 / Auto) — DONE
3. **Backup credentials + names fix** (OpenVPN user/pass in manifest, original names) — DONE
4. **Real delay system** — NEW, this plan's main work

## Stage A — Real delay engine (per-protocol native probes)
File: `XrayTester.kt` (extend) + new `DelayProbe.kt`
- XRAY: keep `Libv2ray.measureOutboundDelay` (real delay).
- IKEV2: UDP/500 IKE_SA_INIT probe (crafted header, SPIi, notify) — any response = reachable.
- WIREGUARD: real Noise_IK handshake initiation with the config's own keys (X25519+BLAKE2s+ChaCha20-Poly1305, pure Kotlin/javax.crypto, minSdk 24); response = handshake initiation response.
- OPENVPN: UDP → P_CONTROL_HARD_RESET_CLIENT_V2 packet (key-id 0, tls-auth HMAC if key present); TCP → TCP connect timing.
Timeout ~3s, result in ms or -1.

## Stage B — UI wiring
- MainViewModel: `testGroupDelay(protocol?)` — iterate visible profiles (respect type filter), run probes with limited concurrency, publish `delayResults: StateFlow<Map<uuid, Long>>` (ms or -1), testing state per profile.
- ProfileListScreen: ⋮ menu item "تست تاخیر واقعی"; per-row badge right of protocol line: green "XXX ms" / red "-1 ms" / spinner while testing.

## Stage C — strings (en/fa), version bump 61/5.1.1

## Stage D — build pipeline
- fb5-v61.sh + run-v61.sh (sed from v60), mksrc61 parts (90MB splits), build, verify
  (aapt 61/5.1.1, Sig Block 42, libcharon NOW GLOBAL), APK + source zip delivery.
