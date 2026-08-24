# Xlite Wallet Backend

The Xlite Wallet Backend is a Java-based project that serves as the backend infrastructure for the Xlite wallet application. It is built using Java with JDK version 17 and utilizes Gradle for build automation. The project incorporates the org.bitcoinj library version 0.14.7 for Bitcoin-related functionality.

## Table of Contents

- [Project Overview](#project-overview)
- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
- [Usage](#usage)
- [Configuration](#configuration)
- [Contributing](#contributing)
- [License](#license)

## Project Overview

Provide a brief description of the Xlite Wallet Backend project. Explain its purpose, key features, and how it fits into the overall Xlite wallet application architecture.

## Prerequisites

List the prerequisites required to set up and run the Xlite Wallet Backend. Include the following:

- JDK 17: Install the Java Development Kit version 17 or a compatible version.

## Getting Started

Provide step-by-step instructions on how to set up and run the Xlite Wallet Backend locally. Include the following:

1. Clone the repository:
```
git clone https://github.com/blocknetdx/xlite-daemon
```
2. Build the project:
```
cd xlite-daemon

# mac/linux:
chmod +x gradlew
./gradlew nativeImage

# windows:
nativeImageWindows.bat
```
3. Configuration: If any configuration files or settings need to be modified, provide instructions on how to set them up.

4. Run the application: 
```
binary to find in build/graal/ folder
```
## Usage

Explain how to use the Xlite Wallet Backend. Provide information on available APIs, endpoints, or functionalities. Include any code snippets or examples to demonstrate usage patterns.

https://docs.blocknet.org/xlite/access-coin-daemons-via-rpc/

Runtime Environment Variables:
```
WALLET_MNEMONIC
WALLET_PASSWORD
```

Supported RPC calls
```
Calls requiring XRouter calls: getblockhash, getblock, gettransaction, sendrawtransaction
help - This command help.
stop - Shutdown the server
=====Blockchain=====
gettxout <txid> <vout> - Get info about an unspent transaction output
=====Network=====
getinfo - Get information such as balances, protocol version, and more
getnetworkinfo - Get network information
getrawmempool - Get raw mempool
getblockchaininfo - Get blockchain info
getblockhash <height> - Get the hash of a block at a given height
getblock <hash> - Get a block's JSON representation given its hash
=====Wallet=====
listunspent - Get all UTXOs in the wallet
getnewaddress - Generate a new address
gettransaction <txid> - Get a transaction given its TXID
getaddressesbyaccount <account> - Get addresses belonging to a given account. The only account available is 'main' which contains all addresses
importprivkey <privkey> - Import an address given it's privkey
dumpprivkey <address> - Dump an addresses private key
=====Utilities=====
signmessage <address> <message> - Sign a message with a given address' private key
verifymessage <address> <signature> <message> - Verify a signature for a message signed by a given address
=====Raw Transactions=====
createrawtransaction <inputs> <outputs> - Create a raw transaction given inputs and outputs in JSON format. For more info, run createrawtransaction with no arguments.
decoderawtransaction <rawtx> - Get a raw transaction's JSON representation
signrawtransaction <rawtx> - Sign a raw transaction
sendrawtransaction <rawtx> - Broadcast a signed raw transaction to the network
```

## Configuration

Describe any configuration options available for the Xlite Wallet Backend. Explain the purpose of each configuration file or setting and how to modify them as needed. Include instructions on any environment variables or external configurations required for proper functioning.

one file per coin,
xlite-daemon (Backend) Configuration Files:

```
Windows
%appdata%\CloudChains\settings\config-*.json

MacOS
~/Library/Application Support/CloudChains/settings/config-*.json

Linux
~/.config/CloudChains/settings/config-*.json
```

## Contributing


Explain how others can contribute to the Xlite Wallet Backend project. Describe the guidelines for submitting bug reports, feature requests, or code contributions. Include information on how to set up the development environment, coding conventions, and the contribution workflow.

## License

Specify the license under which the Xlite Wallet Backend project is released. Choose an appropriate license that suits your project's requirements. If you're not sure, consult with your team or a legal professional.

