# NeoRemote Windows

Windows receiver implementation for NeoRemote.

The first milestone is a tray-first Windows desktop receiver that publishes
`_neoremote._tcp.`, listens on TCP port `50505`, accepts one mobile controller,
and maps `move`, `tap`, `scroll`, and `drag` commands to Windows mouse input.

## Projects

- `src/NeoRemote.Core`: protocol, stream decoder, session logic, and mouse event planning.
- `src/NeoRemote.Windows`: Windows adapters for TCP, DNS-SD/mDNS, SendInput, and tray integration.
- `src/NeoRemote.App`: WinUI 3 desktop shell.
- `tests/NeoRemote.Core.Tests`: small console test runner for core behavior.

## Tooling Required

This repository expects Visual Studio 2022 with:

- Desktop development with C++
- Windows 10/11 SDK
- Windows App SDK
- C++/WinRT tooling

The current machine previously showed no C++/WinUI build toolchain in `PATH`,
so install those components before building with Visual Studio.
