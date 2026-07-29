# cloud-filecoin

`filecoin.cloud.*` — a client for **Filecoin Onchain Cloud** (Synapse) as
portable `.cljc`: PieceCID v2, the deployed contracts, and calldata for PDP,
Filecoin Pay and Warm Storage.

Companion to [`io-filecoin`](https://github.com/kotoba-lang/io-filecoin)
(the chain protocol) and
[`io-filecoin-node`](https://github.com/kotoba-lang/io-filecoin-node)
(what a node is). Built on
[`org-ethereum-abi`](https://github.com/kotoba-lang/org-ethereum-abi).

| Namespace | What it owns |
|---|---|
| `filecoin.cloud.piece` | PieceCID v2 — FR32, the truncated Merkle tree, FRC-0069. |
| `filecoin.cloud.chain` | Where the contracts are, and how epochs relate to clocks. |
| `filecoin.cloud.pdp` | PDPVerifier: data sets, pieces, proofs. |
| `filecoin.cloud.pay` | Payment rails, and the operator approval that funds them. |
| `filecoin.cloud.warm` | Warm Storage, and its separate state-view contract. |
| `filecoin.cloud.evm` | Calldata → a Filecoin message. Where the two halves join. |

## What Onchain Cloud is

Filecoin's original storage market is a deal: you hand data to a provider, they
seal it into a sector, and PoRep/PoSt proves the sector exists. Retrieval is
somebody else's problem, and payment is settled up front.

Onchain Cloud is a different arrangement, and the pieces here are its parts:

- **PDP** proves an *unsealed* piece is still held, every proving period, by
  answering a challenge derived from on-chain randomness. That is what makes
  the storage *warm* — provable without unsealing.
- **Filecoin Pay** streams payment along a **rail** at a rate per epoch, so a
  provider is paid for as long as they keep proving, not once in advance.
- **Warm Storage** is the *listener* joining the two: PDPVerifier tells it
  when pieces are added, removed or proven, and it decides what that means for
  the rail.

The consequence worth knowing before writing any of it: proving and paying are
separate contracts. A data set can be proven for a service that has stopped
paying, and a rail can run for a data set that has stopped being proven.

## Calling a contract

An FEVM contract is also an ordinary Filecoin actor with an `f410f…` address,
so calling it is an ordinary Filecoin message — no Ethereum transaction, no
RLP, no keccak:

```clojure
(require '[filecoin.cloud.evm :as evm]
         '[filecoin.cloud.pdp :as pdp])

(evm/call-message :calibration :pdp-verifier
                  (pdp/call :data-set-live ["42"])
                  {:from "t1…" :nonce 7 :gas-limit 20000000
                   :gas-fee-cap "1000" :gas-premium "500"})
;; => a filecoin.message, ready for signing-bytes and MpoolPush
```

```
to      the contract's f410f address     (not its 0x… form)
method  filecoin.method/invoke-contract  (FRC-0042 "InvokeEVM" = 3844450837)
params  the calldata, as a CBOR byte string
```

Three details, each of which produces a well-formed message that does the
wrong thing:

- **`params` is CBOR-wrapped, not raw.** `InvokeContract` takes
  `abi.CborBytes`. Passing the calldata unwrapped gives the actor a parameter
  block it cannot decode, and the message reverts having already paid gas.
- **`to` is the `f410f` form.** `chain/contract` returns `0x…` because that is
  what an explorer and an ABI show; a message field takes address bytes.
- **The sender decides the signature type.** An `f1` sender signs the message
  CID (secp256k1), which `io-filecoin` can produce. An `f410f` sender signs the
  **RLP-encoded Ethereum transaction** (delegated) — a different payload
  entirely, which it deliberately cannot. The native `f1` path is the one that
  works end to end.

## PieceCID v2

```clojure
(require '[filecoin.cloud.piece :as piece])

(piece/calculate my-bytes)
;; => {:cid "bafkzcibcdmbmhxp7o3q5ntjp4triphpezgi4gaonnueaqnvfzmeohbk3atjlcpy"
;;     :root [...] :height 2 :padding 27 :size 100 :padded-size 128}
```

A PieceCID is a CIDv1 with the **raw** codec and the multihash
`fr32-sha2-256-trunc254-padded-binary-tree` (`0x1011`), whose digest is not a
hash but a structure — `[padding][height][root]` — so the CID carries the
piece's exact size. That is what v2 added: the old PieceCID held only the root
and left the size to be carried alongside, and lost.

Three things in the pipeline are load-bearing and easy to skip:

1. The payload is **zero-padded to `127 × 2^n`** source bytes, because a quad
   is 127 bytes and the tree has to be complete.
2. **FR32 expansion** turns every 127 bytes into 128 by inserting two zero
   bits per 254. 254 is how many bits of a BLS12-381 field element can hold
   arbitrary data; the zero bits keep every leaf below the modulus.
3. A Merkle parent is `SHA-256(left ‖ right)` **with the top two bits of the
   last byte cleared**, for the same reason. Plain SHA-256 gives a different
   root about half the time — a piece nobody can prove, from code that looks
   right.

## Selectors

There is no keccak here. The only one in this workspace
(`eth-crypto.core/keccak256`) is JVM-only on purpose, since every JavaScript
bitwise operator truncates to 32 bits, so a selector cannot be computed on
both runtimes. They are constants, each beside the signature it came from —
and **every one is checked against the deployed ABI** in the test.

That check is not ceremony. Four wrong bytes are indistinguishable from four
right ones until a call reverts with no reason string. When these were first
written from the documentation, fifteen of thirty-one were wrong, including
`addPieces`: the plausible `addPieces(uint256,(bytes)[],bytes)` is one
argument short of the real `addPieces(uint256,address,(bytes)[],bytes)`.

## Verification

Three oracles, none of them this library:

- **PieceCIDs** from `@filoz/synapse-core/piece` — the implementation the
  providers and the contracts agree with. Twenty payload sizes, chosen at the
  boundaries where the height steps (127, 254, 508) rather than at round
  numbers.
- **Selectors** from viem's `toFunctionSelector`, applied to the canonical
  signature of every function in every deployed ABI. A signature this library
  claims that is not in that table fails as a missing entry.
- **Addresses** from `@filoz/synapse-core/chains`, as published.

Both runtimes run the whole suite. **499 assertions, green on both.**

```sh
clojure -M:test        # JVM
npm run test:cljs      # nbb
npm install && npm run vectors   # regenerate
```

## What is not here

- **Signing and transport.** No keys, no sockets. These namespaces return
  calldata and messages; signing and sending are yours.
- **Gas and nonce.** `evm/invoke` carries whatever you give it.
  `gas-limit`/`gas-fee-cap`/`gas-premium` come from
  `Filecoin.GasEstimateMessageGas` and `nonce` from `Filecoin.MpoolGetNonce`
  — both are calls, and nothing here makes calls. `filecoin.rpc` builds the
  request bodies.
- **Proof generation.** Answering a PDP challenge means holding the data and
  producing Merkle paths into it. This library computes the commitment, not
  the proofs.
- **Event log decoding.** Indexed topics hash dynamic types instead of storing
  them, and reading a hash as a value is worse than not reading it.
- **Piece upload and retrieval.** The provider HTTP API and FilBeam are not
  modelled.
- **Large pieces.** `calculate` is byte arithmetic over Clojure vectors: fine
  for a manifest or a test fixture, not for a 32 GiB sector. The size math
  (`height-for`, `padded-size`) is cheap at any size; only the hashing is not.

## Scope — read this before using it

**No transaction built here has been sent to a network.** The selectors and
addresses are checked against what FilOzone publishes, and the encodings
against viem, but nothing in this repository has called a contract. The
distance between correct calldata and a working client is a signer and a
transport, and neither is here.
