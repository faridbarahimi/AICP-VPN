# AICP NPV import core

A minimal Android-bindable package for offline `.npvt` / `.npvs` decryption.

- Upstream: FrontierTM/Pantegnos `v9.4.3` (MIT), commit
  `09ae0699f7d18717f1900643b703021344f6a9fe`.
- API: `npv.Inspect(fileName, data)` and `npv.Decrypt(fileName, data, password)`.
- Input cap: 4 MiB.
- Supported outputs: the upstream decryptor's recovered profile text/share URIs.
- The later AGPL-3.0 Pantegnos releases are not included.

Build an Android AAR on a supported x86_64 Linux/macOS host with Go 1.27.4,
Android NDK 27.3.13750724 (recommended), and the gomobile version pinned by `go.mod`:

```sh
go tool gomobile bind -target=android -androidapi=29 \
  -javapkg=com.aicp.vpn.npv -o ../app/libs/aicpnpv.aar ./npv
```
