# Third-party notices

## Tailscale (`app/src/main/jniLibs/arm64-v8a/libtailscale.so`)

BYD Trip Stats bundles a build of the Tailscale client daemon, used to put the head unit on the
user's own private network so the web companion and ADB are reachable remotely. It is shipped under
a library's filename because Android only extracts files from `lib/` as executables; it is not
linked into the app, and it is only run when the user supplies an auth key.

- Upstream: <https://github.com/tailscale/tailscale>, tag `v1.102.4`
- License: **BSD 3-Clause** — Copyright (c) 2020 Tailscale Inc & contributors
- Full text: <https://github.com/tailscale/tailscale/blob/main/LICENSE>

**How the shipped binary was produced** (reproducible — see `MD/TAILSCALE.md` for the reasoning):

```
git clone --depth 1 --branch v1.102.4 https://github.com/tailscale/tailscale.git
cd tailscale
cp ../tailscale-patch/android_dns.go cmd/tailscaled/     # see "Local patch" below
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build \
  -tags "ts_include_cli,ts_omit_aws,ts_omit_bird,ts_omit_tap,ts_omit_kube,ts_omit_completion,\
ts_omit_completion_scripts,ts_omit_ssh,ts_omit_drive,ts_omit_webclient,ts_omit_systray,\
ts_omit_desktop_sessions,ts_omit_capture,ts_omit_clientupdate,ts_omit_relayserver,\
ts_omit_synology,ts_omit_qrcodes,ts_omit_dbus,ts_omit_syslog,ts_omit_debugportmapper,ts_omit_doctor,\
ts_omit_appconnectors,ts_omit_advertiseexitnode,ts_omit_networkmanager,\
ts_omit_resolved,ts_omit_iptables,ts_omit_taildrop,ts_omit_portlist,ts_omit_posture,\
ts_omit_captiveportal,ts_omit_logtail,ts_omit_netlog,ts_omit_debugeventbus,ts_omit_runtimemetrics,\
ts_omit_syspolicy,ts_omit_tailnetlock,ts_omit_oauthkey" \
  -ldflags "-s -w" -o libtailscale.so ./cmd/tailscaled
upx --best --lzma libtailscale.so
```

39.5 MB → 22.8 MB (omit tags) → **5.58 MB** (UPX). `ts_include_cli` keeps the CLI in the same
binary, reached with `TS_BE_CLI=1`. `ts_omit_logtail` also means the daemon uploads no logs to
Tailscale.

`ts_omit_serve` and `ts_omit_acme` were dropped in 2.17.0 so the daemon can terminate TLS for the
web companion (`tailscale serve`, with a Let's Encrypt certificate for the node's MagicDNS name).
That is the whole of the size difference: +1.2 MB unpacked, **+0.27 MB shipped**.

SHA-256 of the shipped file: `ef57250f8c82c2be722981a1e8d6ea221d268d767ba5442c70b67fd47d39fd30`.

### Local patch: `tailscale-patch/android_dns.go`

It fixes two things that a `GOOS=linux` Go binary gets wrong on Android: **no DNS**, and **no root
certificates**. Both are one-line consequences of building for linux rather than android, and both
only bite on code paths that talk to the wider internet — which on this daemon means exactly one
thing, fetching a TLS certificate.

#### No root certificates

`crypto/x509` does know Android's CA store, but only behind `if goos.IsAndroid == 1`, which a
`GOOS=linux` build compiles out. It then searches `/etc/ssl/certs` and friends, finds none of them,
ends up with an empty root pool, and fails every verification:

    tls: failed to verify certificate: x509: certificate signed by unknown authority

The head unit has 140 PEM roots in `/system/etc/security/cacerts`. `SSL_CERT_DIR` is crypto/x509's
own documented override, so the patch sets it to that directory plus the user-added store, exactly
as the stdlib does natively on Android. `TailscaleManager` passes the same value on the daemon's
command line, so a daemon left running from an older launch line also picks it up.

#### No DNS

One file of ours is added to `cmd/tailscaled/` before building. It changes no Tailscale code — it
only installs a `net.DefaultResolver.Dial` at init, and it is a no-op on any system that has an
`/etc/resolv.conf`.

**Why it is needed.** Android has no `/etc/resolv.conf` (`/etc` symlinks to the read-only
`/system/etc`), and the daemon is built `CGO_ENABLED=0`, so there is no libc fallback either. Go's
pure-Go resolver therefore finds no nameservers and falls back to `127.0.0.1:53` / `[::1]:53`, where
nothing is listening. The tailnet still works, because the control plane bootstraps from hardcoded
addresses — but any ordinary hostname lookup fails, which killed the Let's Encrypt fetch behind
`tailscale serve`:

    lookup acme-v02.api.letsencrypt.org on [::1]:53: read: connection refused

The ACME client is constructed with no custom HTTP client (`feature/acme/certstore.go`), so it uses
Go's default transport and default resolver and never reaches Tailscale's own DNS machinery.

Android publishes its nameservers as system properties instead of a file, so the patch collects
candidates — the default-route gateway, the first host of each attached subnet, the `net.*.dns*`
properties, then public resolvers — and **probes each with a real query** before using it, caching
the winner for five minutes.

The probe is not belt-and-braces. The properties list the *cellular* nameservers, and on Wi-Fi
there is no route to them, so queries leave the Wi-Fi address bound for a carrier-internal resolver
and time out. Nor can that be handled by trying servers in turn inside the dial: a UDP "dial"
succeeds for any address at all, so the first candidate always wins and the failure surfaces later
as a read timeout inside Go's resolver, out of reach. The subnet guess is there because Android
keeps default routes in per-network policy tables — a head unit's `/proc/net/route` has only subnet
routes and no `00000000` destination, so the ordinary gateway lookup finds nothing.

**Re-apply this on every version bump** — it is a plain copy, and if upstream ever gives the ACME
client a tsdial-backed transport, drop it.
