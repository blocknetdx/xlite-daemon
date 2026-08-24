# POR-172 requirements

- Allow the restricted `signmessage(address, message)` RPC to sign the exact requested address as a self-address proof when `coin.getAddressBalance(address)` proves the address is wallet-owned.
- Preserve the existing, validated Core `UtxoEntry` message proof path.
- Reject arbitrary messages, malformed messages, and address messages that do not exactly equal the requested address.
- Reject non-wallet addresses before any signing operation.
- Keep private-key import/export RPCs disabled and do not broaden signing or expose secrets.
- Add focused behavioural and source-level tests covering the compatibility branch and security invariants.
- Limit the implementation to the HTTP server handler, the new POR-172 compatibility test, the plan triad, and README only if essential.
