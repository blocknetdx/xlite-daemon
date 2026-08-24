package io.cloudchains.app.net;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.AtomicDouble;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
//import com.subgraph.orchid.encoders.Hex;
import io.cloudchains.app.App;
import io.cloudchains.app.Version;
import io.cloudchains.app.crypto.KeyHandler;
import io.cloudchains.app.net.api.JSONRPCController;
import io.cloudchains.app.net.api.JSONRPCServer;
//import io.cloudchains.app.net.protocols.alqocoin.AlqocoinNetworkParameters;
//import io.cloudchains.app.net.protocols.bitbay.BitbayNetworkParameters;
//import io.cloudchains.app.net.protocols.bitcoincash.BitcoinCashNetworkParameters;
import io.cloudchains.app.net.protocols.blocknet.*;
import io.cloudchains.app.net.protocols.dashcoin.DashcoinNetworkParameters;
//import io.cloudchains.app.net.protocols.digibyte.DigibyteNetworkParameters;
import io.cloudchains.app.net.protocols.dogecoin.DogecoinNetworkParameters;
import io.cloudchains.app.net.protocols.litecoin.LitecoinNetworkParameters;
//import io.cloudchains.app.net.protocols.phorecoin.PhorecoinNetworkParameters;
import io.cloudchains.app.net.protocols.pivx.PivxNetworkParameters;
//import io.cloudchains.app.net.protocols.poliscoin.PoliscoinNetworkParameters;
//import io.cloudchains.app.net.protocols.ravencoin.RavencoinNetworkParameters;
import io.cloudchains.app.net.protocols.syscoin.SyscoinNetworkParameters;
import io.cloudchains.app.net.protocols.unobtanium.UnobtaniumNetworkParameters;
//import io.cloudchains.app.net.protocols.trezarcoin.TrezarcoinNetworkParameters;
import io.cloudchains.app.net.xrouter.XRouterMessage;
import io.cloudchains.app.net.xrouter.XRouterMessageSerializer;
import io.cloudchains.app.net.xrouter.XRouterPacketManager;
import io.cloudchains.app.util.AddressBalance;
import io.cloudchains.app.util.CloudTransaction;
import io.cloudchains.app.util.ConfigHelper;
import io.cloudchains.app.util.UTXO;
import io.cloudchains.app.util.history.Transaction;
import io.cloudchains.app.wallet.WalletHelper;
import org.bitcoinj.core.*;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.SPVBlockStore;
import org.bitcoinj.utils.BtcFormat;
import org.bitcoinj.utils.ListenerRegistration;
import org.bitcoinj.utils.MonetaryFormat;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.Wallet;
import org.json.JSONArray;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import com.subgraph.orchid.encoders.Hex;

public class CoinInstance {
    public static class CoinError {
        public enum CoinErrorCode {
            BADPASSWORD, UNSUPPORTEDCOIN, BADMNEMONIC, CHANGEPASSWORDFAILED
        }
        private final CoinErrorCode code;
        private final String msg;
        public CoinError(String msg, CoinErrorCode code) {
            this.msg = msg;
            this.code = code;
        }
        public CoinErrorCode getCode() {
            return this.code;
        }
        public String getMessage() {
            return this.msg;
        }
    }

	private final static LogManager LOGMANAGER = LogManager.getLogManager();
	private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

	private static final int MINIMUM_UTXO_UPDATE_INTERVAL = 500;

	private static final int FORWARD_ADDRESS_COUNT = 0;

	private static ArrayList<CoinInstance> coinInstances = new ArrayList<>();
	private static CoinInstance activeCurrency;
	private static CoinTicker activeBlocknetNetwork = null;
	private static CopyOnWriteArrayList<ListenerRegistration<ActiveCoinChangedEventListener>> activeCoinChangedListeners = new CopyOnWriteArrayList<>();
	private static HashMap<CoinTicker, AtomicInteger> blockCounts = new HashMap<>();
	private static HashMap<CoinTicker, AtomicDouble> relayFees = new HashMap<>();

	private ConfigHelper configHelper;
	private WalletHelper walletHelper = null;
	private CoinTicker ticker;
	private ConcurrentHashMap<String, Transaction> transactionList = new ConcurrentHashMap<>();
	private ArrayList<AddressBalance> addressKeyPairs = new ArrayList<>();
	private ArrayList<CloudTransaction> transactionObservableList = new ArrayList<>();
	private BlocknetPeerGroup blocknetPeerGroup;
	private BlocknetParameters blocknetNetworkParameters;
	private NetworkParameters networkParameters;
	private Wallet wallet;
	private BlockChain chain;
	private boolean hasXRouter = false;
	private KeyHandler keyHandler;
	private XRouterPacketManager xRouterPacketManager = null;
	private int rpcPort = -1;
	private boolean testnet = false;
	private JSONRPCServer coinRPCServer = null;
	private long lastUtxoUpdate = 0;
	private int updateFailures = 0;
	private int generatedAddressCount;

	private CoinInstance(CoinTicker ticker) {
		this.ticker = ticker;
		this.configHelper = new ConfigHelper(CoinTickerUtils.tickerToString(getTicker()));

		addBlockCount(ticker, 0);

		if (isBlocknetNetwork())
			setActiveCurrency(this);
	}

    /**
     * Return mnemonic seed from wallet stored on disk. Correct passphrase required.
     * Returns empty string on error or failure to retrieve mnemonic (or if mnemonic
     * doesn't exist).
     * @param pw String
     * @return String
     */
    public static String getMnemonicForPw(String pw) {
        if (!KeyHandler.existsBaseECKeyFromLocal())
            return "";

        List<String> seed = KeyHandler.getBaseSeed(pw);
        if (seed == null)
            return "";

        return Joiner.on(" ").join(seed);
    }
	public static int getBlockCountByTicker(CoinTicker ticker) {
		if (!blockCounts.containsKey(ticker)) {
			return -1;
		}

		return blockCounts.get(ticker).get();
	}

	public static double getRelayFeeByTicker(CoinTicker ticker) {
		if (!relayFees.containsKey(ticker)) {
			return -1;
		}

		return relayFees.get(ticker).get();
	}

	public static ArrayList<CoinInstance> getCoinInstances() {
		return coinInstances;
	}

	public boolean isBlocknetNetwork() {
		return getTicker() == CoinTicker.BLOCKNET || getTicker() == CoinTicker.BLOCKNET_TESTNET5;
	}

	public static void setActiveCurrency(CoinInstance newActiveCurrency) {
		activeCurrency = newActiveCurrency;

		for (ListenerRegistration<ActiveCoinChangedEventListener> registration : activeCoinChangedListeners) {
			if (registration.executor == Threading.SAME_THREAD) {
				registration.listener.onActiveCoinChanged(activeCurrency);
			}
		}
	}

	public XRouterPacketManager getXRouterPacketManager() {
		return xRouterPacketManager;
	}

	public static void addActiveCoinChangedListener(ActiveCoinChangedEventListener listener) {
		activeCoinChangedListeners.add(new ListenerRegistration<>(listener, Threading.SAME_THREAD));
	}

	public static CoinInstance getActiveCurrency() {
		return activeCurrency;
	}

	public AddressBalance getAddress(String addressB58) {
		for (AddressBalance address : addressKeyPairs) {
			if (address.getAddress().toBase58().equals(addressB58))
				return address;
		}

		return null;
	}

	public AddressBalance generateAddress(boolean updateConfig) {
		AddressBalance addressKeyPair = getWalletHelper().generateAddress();
		Address address = addressKeyPair.getAddress();
		DumpedPrivateKey privateKey = addressKeyPair.getPrivateKey();
		addressKeyPairs.add(addressKeyPair);
		LOGGER.log(Level.FINER, "[wallet] DEBUG: Generated new address, have " + addressKeyPairs.size() + ": " + address.toBase58() + ", private key: " + privateKey.toBase58() + " (hex: " + privateKey.getKey().getPrivateKeyAsHex() + ")");

		if (updateConfig) {
			configHelper.setAddressCount(configHelper.getAddressCount() + 1);
			configHelper.writeConfig();
			generatedAddressCount = configHelper.getAddressCount();
		}

		return addressKeyPair;
	}

	public void importPrivateKey(String privKey) {
		AddressBalance addressKeyPair = getWalletHelper().generateFromPrivateKey(privKey);
		AddressBalance addrExists = addressKeyPairs.stream()
				.filter(e -> e.getAddress().equals(addressKeyPair.getAddress())).findAny().orElse(null);

		if (addrExists == null)
			addressKeyPairs.add(addressKeyPair);
	}

	public CoinTicker getTicker() {
		return ticker;
	}

	private static CoinInstance getInstanceByTicker(CoinTicker ticker) {
		for (CoinInstance instance : coinInstances) {
			if (instance.getTicker() == ticker) {
				return instance;
			}
		}

		return null;
	}

	public static CoinTicker getActiveBlocknetNetwork() {
		return activeBlocknetNetwork;
	}

	public static CoinInstance getInstance(CoinTicker ticker) {
		CoinInstance instance = getInstanceByTicker(ticker);
		if (getActiveBlocknetNetwork() != null && (ticker == CoinTicker.BLOCKNET || ticker == CoinTicker.BLOCKNET_TESTNET5)) {
			return getInstanceByTicker(activeBlocknetNetwork);
		}

		if (ticker == CoinTicker.BLOCKNET || ticker == CoinTicker.BLOCKNET_TESTNET5) {
			activeBlocknetNetwork = ticker;
			LOGGER.log(Level.FINER, "[coin] Initialized active Blocknet network: " + ticker.toString());
			LOGGER.log(Level.FINER, "[coin] All subsequent calls to this function requesting a Blocknet network will return the above regardless of testnet or mainnet status.");
		}

		if (instance == null) {
			instance = new CoinInstance(ticker);
			if (ticker == CoinTicker.BLOCKNET)
				coinInstances.add(0, instance);
			else
				coinInstances.add(instance);
		}

		return instance;
	}

    /**
     * Change the password. Recreates the wallet file and encrypts with new password.
     * @param oldPassword
     * @param newPassword
     * @return Error or null
     */
    public static CoinError changePassword(String oldPassword, String newPassword) {
        if (!KeyHandler.existsBaseECKeyFromLocal()) {
            LOGGER.log(Level.FINER, "[wallet] Unable to change the password: Wallet not found on disk");
            return new CoinError("Unable to change the password: Wallet not found on disk",
                    CoinError.CoinErrorCode.CHANGEPASSWORDFAILED);
        }

        List<String> baseSeed = KeyHandler.getBaseSeed(oldPassword);
        if (baseSeed == null) {
            LOGGER.log(Level.FINER, "[wallet] Unable to change the password: Incorrect password");
            return new CoinError("Unable to change the password: Incorrect password",
                    CoinError.CoinErrorCode.CHANGEPASSWORDFAILED);
        }

        // Get current wallet seed
        DeterministicSeed seed = new DeterministicSeed(baseSeed, null, "", System.currentTimeMillis() / 1000);
        List<String> mnemonic = seed.getMnemonicCode();

        if (!KeyHandler.importFromMnemonic(mnemonic, newPassword)) {
            LOGGER.log(Level.FINER, "[wallet] Unable to change the password: Failed to create new wallet file");
            return new CoinError("Unable to change the password: Failed to create new wallet file",
                    CoinError.CoinErrorCode.CHANGEPASSWORDFAILED);
        }

        return null;
    }

	public NetworkParameters getNetworkParameters() {
		return networkParameters;
	}

	private BlocknetParameters getBlocknetNetworkParameters() {
		return blocknetNetworkParameters;
	}

	public boolean hasXRouter() {
		return hasXRouter;
	}

	public void deinit() {
		if (getTicker() == getActiveBlocknetNetwork() && blocknetPeerGroup != null) {
			blocknetPeerGroup.stop();
		}

		if (coinRPCServer != null) {
			try {
				coinRPCServer.deinit();
				coinRPCServer.join();
			} catch (Exception e) {
				LOGGER.log(Level.FINER, "[coin] ERROR: Error while deinitializing coin RPC server!");
				e.printStackTrace();
			}
		}
	}

	public CoinError init(String pw, String userMnemonic, boolean isMnemonic) {
		return init(pw, userMnemonic, isMnemonic, false);
    }

	public CoinError init(String pw, String userMnemonic, boolean isMnemonic, boolean xliteRPC) {
		switch (ticker) {
			case BLOCKNET: {
				LOGGER.log(Level.FINER, "[coin] Initializing for Blocknet main network.");
				blocknetNetworkParameters = new BlocknetNetworkParameters();
				networkParameters = blocknetNetworkParameters;
				hasXRouter = true;
				rpcPort = 41419;
				break;
			}
			case BLOCKNET_TESTNET5: {
				LOGGER.log(Level.FINER, "[coin] Initializing for Blocknet test network v5.");
				blocknetNetworkParameters = new BlocknetTestnet5NetworkParameters();
				networkParameters = blocknetNetworkParameters;
				hasXRouter = true;
				rpcPort = 41419;
				testnet = true;
				break;
			}
			case BITCOIN: {
				LOGGER.log(Level.FINER, "[coin] Initializing for Bitcoin main network.");
				networkParameters = MainNetParams.get();
				rpcPort = 8332;
				break;
			}
			// case BITCOIN_CASH: {
			// 	LOGGER.log(Level.FINER, "[coin] Initializing for BitcoinCash main network.");
			// 	networkParameters = new BitcoinCashNetworkParameters();
			// 	rpcPort = 48332;
			// 	break;
			// }
			case LITECOIN: {
				LOGGER.log(Level.FINER, "[coin] Initializing for Litecoin main network.");
				networkParameters = new LitecoinNetworkParameters();
				rpcPort = 9332;
				break;
			}
			case DASHCOIN: {
				LOGGER.log(Level.FINER, "[coin] Initializing for Dashcoin main network.");
				networkParameters = new DashcoinNetworkParameters();
				rpcPort = 9998;
				break;
			}
			// case DIGIBYTE: {
			// 	LOGGER.log(Level.FINER, "[coin] Initializing for Digibyte main network.");
			// 	networkParameters = new DigibyteNetworkParameters();
			// 	rpcPort = 14022;
			// 	break;
			// }
			case DOGECOIN: {
				LOGGER.log(Level.FINER, "[coin] Initializing for Dogecoin main network.");
				networkParameters = new DogecoinNetworkParameters();
				rpcPort = 22555;
				break;
			}
			case SYSCOIN: {
				LOGGER.log(Level.FINER, "[coin] Initializing for Syscoin main network.");
				networkParameters = new SyscoinNetworkParameters();
				rpcPort = 8370;
				break;
			}
			// case TREZARCOIN: {
			// 	networkParameters = new TrezarcoinNetworkParameters();
			// 	rpcPort = 17299;
			// 	break;
			// }
			// case BITBAY: {
			// 	networkParameters = new BitbayNetworkParameters();
			// 	rpcPort = 19915;
			// 	break;
			// }
			case PIVX: {
				LOGGER.log(Level.FINER, "[coin] Initializing for Pivx main network.");
				networkParameters = new PivxNetworkParameters();
				rpcPort = 9951;
				break;
			}
			case UNOBTANIUM: {
				LOGGER.log(Level.FINER, "[coin] Initializing for Unobtanium main network.");
				networkParameters = new UnobtaniumNetworkParameters();
				rpcPort = 65111;
				break;
			}
			// case ALQOCOIN: {
			// 	LOGGER.log(Level.FINER, "[coin] Initializing for Alqo main network.");
			// 	networkParameters = new AlqocoinNetworkParameters();
			// 	rpcPort = 55000;
			// 	break;
			// }
			// case POLISCOIN: {
			// 	LOGGER.log(Level.FINER, "[coin] Initializing for Polis main network.");
			// 	networkParameters = new PoliscoinNetworkParameters();
			// 	rpcPort = 24127;
			// 	break;
			// }
			// case PHORECOIN: {
			// 	LOGGER.log(Level.FINER, "[coin] Initializing for Phore main network.");
			// 	networkParameters = new PhorecoinNetworkParameters();
			// 	rpcPort = 11772;
			// 	break;
			// }
			// case RAVENCOIN: {
			// 	LOGGER.log(Level.FINER, "[coin] Initializing for Ravencoin main network.");
			// 	networkParameters = new RavencoinNetworkParameters();
			// 	rpcPort = 8766;
			// 	break;
			// }
			default: {
				LOGGER.log(Level.FINER, "[coin] ERROR: Invalid/unsupported network: " + ticker.toString());
				return new CoinError("Unsupported coin", CoinError.CoinErrorCode.UNSUPPORTEDCOIN);
			}
		}

		if (xliteRPC) {
			rpcPort = rpcPort + 1;

			configHelper.setRpcPort(rpcPort);
			configHelper.writeConfig();
		}

		Context.propagate(new Context(networkParameters));

		List<String> baseSeed;
		boolean existsOnDisk = false;

		if (isMnemonic) {
			baseSeed = Arrays.asList(pw.split(" "));
		} else {
			if (KeyHandler.existsBaseECKeyFromLocal())
				existsOnDisk = true;
			else if (userMnemonic != null) {
				if (!KeyHandler.importFromMnemonic(Arrays.asList(new String(userMnemonic).split(" ")), pw)) {
					LOGGER.log(Level.FINER, "[wallet] Unable to create wallet from mnemonic");
					return new CoinError("Unable to create wallet from mnemonic", CoinError.CoinErrorCode.BADMNEMONIC);
				}
			}

			baseSeed = KeyHandler.getBaseSeed(pw);
		}

		if (baseSeed == null) {
			LOGGER.log(Level.FINER, "[wallet] Possible Bad password: Unable to import or create base seed!");
			return new CoinError("Bad password", CoinError.CoinErrorCode.BADPASSWORD);
		}

		// In-memory wallet only
		DeterministicSeed seed = new DeterministicSeed(baseSeed, null, "", System.currentTimeMillis() / 1000);
		wallet = Wallet.fromSeed(networkParameters, seed);
		if (isBlocknetNetwork()) {
			String mnemonic = getMnemonic();
			// LOGGER.log(Level.FINE, "[wallet] Mnemonic = " + mnemonic);
		}

		// Make sure wallet addresses are available
		generateForwardAddresses(true);

		if (configHelper.getRpcPort() == -1000) {
			configHelper.setRpcPort(rpcPort);
			configHelper.writeConfig();
		} else {
			rpcPort = configHelper.getRpcPort();
		}

		if (configHelper.isRpcEnabled() && configHelper.validAuth() && rpcPort != -1) {
			coinRPCServer = JSONRPCController.getRPCServer(this);
			LOGGER.log(Level.INFO, "[rpc] Starting JSON-RPC server for coin " + CoinTickerUtils.tickerToString(getTicker()) + " on port " + getRPCPort());

			if (coinRPCServer.isAlive())
				coinRPCServer.deinit();

			coinRPCServer.start();
		}

		// Blocknet Network / XRouter not used (Dec 10)
//		if (isBlocknetNetwork() && hasXRouter()) {
//			XRouterMessageSerializer xRouterMessageSerializer = (getBlocknetNetworkParameters()).getXRouterMessageSerializer(false);
//			xRouterPacketManager = new XRouterPacketManager(xRouterMessageSerializer, blocknetNetworkParameters);
//			LOGGER.log(Level.FINER, "[coin] This network is a Blocknet network and supports XRouter. Our packet version is " + Integer.toString(XRouterPacketManager.getXRouterPacketVersion(), 16));
//		} else {
//			LOGGER.log(Level.FINER, "[coin] WARNING: This network (" + CoinTickerUtils.tickerToString(getTicker()) + ") does not support XRouter.");
//		}
//
//		if (isBlocknetNetwork()) {
//			String userHome = ConfigHelper.getLocalDataDirectory();
//			Preconditions.checkNotNull(userHome);
//
//			File spvDat = new File(userHome,"spv-" + CoinTickerUtils.tickerToString(getTicker()) + ".dat");
//			try {
//				chain = new BlockChain(networkParameters, getWallet(), new SPVBlockStore(networkParameters, spvDat));
//			} catch (BlockStoreException e) {
//				try {
//					chain = new BlockChain(networkParameters, getWallet(), new SPVBlockStore(networkParameters, spvDat));
//				} catch (BlockStoreException ex) {
//					LOGGER.log(Level.FINER, "Error while initializing blockchain object!");
//					ex.printStackTrace();
//					return false;
//				}
//			}
//
//			LOGGER.log(Level.INFO, "[coin] Connecting to the (" + getTicker().toString() + ") network.");
//
//			if (getAddressKeyPairs().size() == 0) {
//				LOGGER.log(Level.FINER, "[peer] Have no addresses. Generating forward addresses.");
//
//				generateForwardAddresses(true);
//			}
//
//			AddressBalance blockProofAddress = getAddressKeyPairs().get(0);
//
//			keyHandler = new KeyHandler(blockProofAddress.getPrivateKey().getKey());
//
//			blocknetPeerGroup = new BlocknetPeerGroup(this, (BlocknetNetworkParameters) blocknetNetworkParameters, chain);
//			connectToBlocknetNetwork();
//		}

		return null;
	}

	private void generateForwardAddresses(boolean fromStartup) {
		int configAddressCount = configHelper.getAddressCount();
		boolean updateConfig = false;
		if (configAddressCount < FORWARD_ADDRESS_COUNT) { // minimum starting addresses
			configAddressCount = FORWARD_ADDRESS_COUNT;
			updateConfig = true;
		}

		LOGGER.log(Level.FINER, "[wallet] Generating " + configAddressCount + " forward addresses for network " + getTicker().toString() + ".");

		// Ensure that internal HD wallet pointer matches the count we're expecting.
		// Required because wallet doesn't remember last HD wallet address prior to
		// reboot.
		if (fromStartup) {
			for (int i = 0; i < generatedAddressCount; i++) {
				getWalletHelper().generateAddress();
			}
		}
		for (int i = generatedAddressCount; i < configAddressCount; i++) {
			generateAddress(false);
		}
		generatedAddressCount = configAddressCount;

		if (updateConfig) {
			configHelper.setAddressCount(configAddressCount);
			configHelper.writeConfig();
		}
	}

	private void connectToBlocknetNetwork() {
		try {
			blocknetPeerGroup.start();
		} catch (Exception e) {
			LOGGER.log(Level.FINER, "Error while initializing blocking client object!");
			e.printStackTrace();
			return;
		}

		LOGGER.log(Level.FINER, "[coin] This network is connecting/connected.");
	}

	public Wallet getWallet() {
		return wallet;
	}

	public String getMnemonic() {
		return Joiner.on(" ").join(Objects.requireNonNull(getWallet().getKeyChainSeed().getMnemonicCode()));
	}

	public double getAllBalances() {
		double balance = 0;

		for (AddressBalance inst : getAddressKeyPairs()) {
			balance += inst.getBalanceProp();
		}

		return balance;
	}

	public String getAllBalancesFormatted() {
		BtcFormat f = BtcFormat.getInstance(BtcFormat.COIN_SCALE);
		return f.format(Coin.valueOf((long) (getAllBalances() * Coin.COIN.value)));
	}

	public void sendXrGetTransaction(BlocknetPeer blocknetPeer, String txid) {
		String currentCurrency = CoinTickerUtils.tickerToString(getTicker());

		HashMap<String, Object> body = new HashMap<>();
		body.put("currency", currentCurrency);
		body.put("txid", txid);

		getInstance(activeBlocknetNetwork).sendXrMessage(blocknetPeer, "xrGetTransaction", body);
	}

	public void sendXrGetBlockCount(BlocknetPeer blocknetPeer) {
		String currentCurrency = CoinTickerUtils.tickerToString(getTicker());

		HashMap<String, Object> body = new HashMap<>();
		body.put("currency", currentCurrency);

		getInstance(activeBlocknetNetwork).sendXrMessage(blocknetPeer, "xrGetBlockCount", body);
	}

	public void sendXrGetUtxos(BlocknetPeer blocknetPeer) {
		if (System.currentTimeMillis() - lastUtxoUpdate < MINIMUM_UTXO_UPDATE_INTERVAL) {
			LOGGER.log(Level.FINER, "[coin] Aborting UTXO checking as the list was updated less than 1 second ago.");
			return;
		}

		String currentCurrency = CoinTickerUtils.tickerToString(getTicker());

		HashMap<String, Object> body = new HashMap<>();
		body.put("currency", currentCurrency);
		body.put("command", "xrmgetutxos");
		body.put("params", getInstance(CoinTickerUtils.stringToTicker(currentCurrency)).getUTXOParams());

		getInstance(activeBlocknetNetwork).sendXrMessage(blocknetPeer, "xrService", body);
	}

	public String sendXrMessage(BlocknetPeer blocknetPeer, String command, HashMap<String, Object> params) {
		return sendXrMessage(blocknetPeer, UUID.randomUUID().toString(), command, params);
	}

	public String sendXrMessage(BlocknetPeer blocknetPeer, String uuid, String command, HashMap<String, Object> params) {
		XRouterMessage message = null;

		if (blocknetPeer == null || !blocknetPeer.getHaveConfig().get()) {
			LOGGER.log(Level.FINER, "[sendXrMessage] Config not received yet");
			return null;
		}

		switch (command) {
			case "xrGetBlockCount": {
				String currency = (String) params.get("currency");

				message = getXRouterPacketManager().getXrGetBlockCount(
						blocknetPeer,
						uuid,
						currency,
						keyHandler.getBaseECKey(),
						keyHandler.getPublicKey());
				break;
			}
			case "xrService": {
				String xrCustomCmd = (String) params.get("command");

				ArrayList paramsList = (ArrayList) params.get("params");

				message = getXRouterPacketManager().getXrService(
						blocknetPeer,
						uuid,
						xrCustomCmd,
						paramsList,
						keyHandler.getBaseECKey(),
						keyHandler.getPublicKey());
				break;
			}
			case "xrSendTransaction": {
				String feePayment = (String) params.get("feetx");
				String transaction = (String) params.get("transaction");
				String currency = (String) params.get("currency");

				message = getXRouterPacketManager().getXrSendTransaction(
						blocknetPeer,
						uuid,
						feePayment,
						currency,
						transaction,
						keyHandler.getBaseECKey(),
						keyHandler.getPublicKey());
				break;
			}
			case "xrGetBlockHash": {
				String feePayment = (String) params.get("feetx");
				String blockIndex = (String) params.get("blockIndex");
				String currency = (String) params.get("currency");

				message = getXRouterPacketManager().getXrGetBlockHash(
						blocknetPeer,
						uuid,
						feePayment,
						currency,
						blockIndex,
						keyHandler.getBaseECKey(),
						keyHandler.getPublicKey());
				break;
			}
			case "xrGetBlock": {
				String feePayment = (String) params.get("feetx");
				String blockHash = (String) params.get("blockHash");
				String currency = (String) params.get("currency");

				message = getXRouterPacketManager().getXrGetBlock(
						blocknetPeer,
						uuid,
						feePayment,
						currency,
						blockHash,
						keyHandler.getBaseECKey(),
						keyHandler.getPublicKey());
				break;
			}
			case "xrGetTransaction": {
				String txid = (String) params.get("txid");
				String currency = (String) params.get("currency");

				message = getXRouterPacketManager().getXrGetTransaction(
						blocknetPeer,
						uuid,
						currency,
						txid,
						keyHandler.getBaseECKey(),
						keyHandler.getPublicKey());
				break;
			}
			case "xrGetConfig": {
				message = getXRouterPacketManager().getXrGetConfig(
						blocknetPeer,
						uuid,
						"self",
						keyHandler.getBaseECKey(),
						keyHandler.getPublicKey());
				break;
			}
			default: {
				LOGGER.log(Level.FINER, "[coin] ERROR: Unknown XRouter Message! Command: " + command);
				uuid = null;
				break;
			}
		}

		if (message != null)
			blocknetPeerGroup.sendMessage(blocknetPeer, message);

		return uuid;
	}

	public JsonArray getAllUTXOS() {
		MonetaryFormat PLAIN_FORMAT = MonetaryFormat.BTC.minDecimals(8).repeatOptionalDecimals(1, 0).noCode();

		JsonArray unspentTxsJSON = new JsonArray();
		for (AddressBalance addressBalance : getAddressKeyPairs()) {
			for (UTXO utxo : addressBalance.getUtxos()) {
				if (utxo.isSpent())
					continue;

				org.bitcoinj.core.UTXO bUtxo = utxo.createUTXO();

				JsonObject utxoJSON = new JsonObject();
				utxoJSON.addProperty("txid", bUtxo.getHash().toString());
				utxoJSON.addProperty("vout", bUtxo.getIndex());
				utxoJSON.addProperty("address", bUtxo.getAddress());

				Monetary monetary = new Monetary() {
					@Override
					public int smallestUnitExponent() {
						return 8;
					}

					@Override
					public long getValue() {
						return utxo.getValue();
					}

					@Override
					public int signum() {
						if (this.getValue() == 0)
							return 0;
						return this.getValue() < 0 ? -1 : 1;
					}
				};

				BigDecimal amountDecimal = new BigDecimal(PLAIN_FORMAT.format(monetary).toString());

				utxoJSON.addProperty("amount", amountDecimal);
				utxoJSON.addProperty("scriptPubKey", new String(Hex.encode(bUtxo.getScript().getProgram())));
				utxoJSON.addProperty("spendable", true);

				int totalBlocks = CoinInstance.getBlockCountByTicker(getTicker());
				int confirmations = (totalBlocks - bUtxo.getHeight()) + 1;
				if (bUtxo.getHeight() == 0)
					confirmations = 0;

				utxoJSON.addProperty("confirmations", confirmations);

				unspentTxsJSON.add(utxoJSON);
			}
		}

		return unspentTxsJSON;
	}

	public JsonArray getAllTransactions() {
		JsonArray transactionsJSON = new JsonArray();
		for (Transaction tx : transactionList.values()) {
			JsonObject txJSON = new JsonObject();
			txJSON.addProperty("category", tx.getCategory());
			txJSON.addProperty("txid", tx.getTxid());
			txJSON.addProperty("blockhash", tx.getBlockhash());
			txJSON.addProperty("vout", tx.getVout());
			txJSON.addProperty("address", tx.getAddress());

			txJSON.addProperty("amount", tx.getValue());
			txJSON.addProperty("fee", tx.getFee());
			txJSON.addProperty("trusted", false);
			txJSON.addProperty("blocktime", tx.getBlocktime());
			txJSON.addProperty("time", tx.getBlocktime());

			txJSON.addProperty("confirmations", tx.getConfirmations());

			transactionsJSON.add(txJSON);
		}


		return transactionsJSON;
	}

	public ArrayList<String> getUTXOParams() {
		ArrayList<String> params = new ArrayList<>();
		params.add(CoinTickerUtils.tickerToString(getTicker()));

		JSONArray utxoAddresses = new JSONArray();
		for (AddressBalance addressBalance : getAddressKeyPairs()) {
			utxoAddresses.put(addressBalance.getAddress().toBase58());
		}

		params.add(utxoAddresses.toString());

		return params;
	}

	public AddressBalance getAddressBalance(String address) {
		for (AddressBalance addressBalance : getAddressKeyPairs()) {
			if (addressBalance.getAddress().toBase58().equals(address)) {
				return addressBalance;
			}
		}

		return null;
	}

	public ConfigHelper getConfigHelper() {
		return this.configHelper;
	}

	public WalletHelper getWalletHelper() {
		if (this.walletHelper == null)
			this.walletHelper = new WalletHelper(this);

		return this.walletHelper;
	}

	public void addBlockCount(CoinTicker ticker, Integer blockCount) {
		if (blockCounts.containsKey(ticker)) {
			if (blockCounts.get(ticker).get() > blockCount)
				return;

			blockCounts.get(ticker).set(blockCount);
			return;
		}

		blockCounts.put(ticker, new AtomicInteger(blockCount));
	}

	public void addRelayFee(CoinTicker ticker, Double relayFee) {
		if (relayFees.containsKey(ticker)) {
			relayFees.get(ticker).set(relayFee);
			return;
		}

		configHelper.setFee(relayFee);

		relayFees.put(ticker, new AtomicDouble(relayFee));
	}

	public void addCloudTransaction(CloudTransaction cloudTransaction) {
		if (transactionObservableList.isEmpty()) {
			transactionObservableList.add(cloudTransaction);
			return;
		}

		CloudTransaction tx = transactionObservableList.stream()
				.filter(e -> e.getTxHash().equals(cloudTransaction.getTxHash())).findAny().orElse(null);

		if (tx == null) {
			transactionObservableList.add(cloudTransaction);
		}
	}

	public void processUtxos(List<UTXO> utxoList) {
		//  first lets clear UTXOs out of each address
		for (UTXO utxo : utxoList) {
			AddressBalance addressBalance = getAddress(utxo.getAddress());
			addressBalance.clearUtxos();
		}

		// now let's add them back
		for (UTXO utxo : utxoList) {
			AddressBalance addressBalance = getAddress(utxo.getAddress());

			if (addressBalance == null) {
				LOGGER.log(Level.FINER, "[utxo-parser] Warning: Encountered non-tracked address in reply: " + utxo.getAddress());
				continue;
			}

			boolean isNewUtxo = addressBalance.addUtxo(utxo);

			if (isNewUtxo) {
				addCloudTransaction(new CloudTransaction(utxo));
				LOGGER.log(Level.FINER, "[utxo-parser] Added new UTXO, address: " + utxo.getAddress() + " value: " + utxo.getAmount());
			}
		}

		setLastUtxoUpdate(System.currentTimeMillis());
	}

	public void processHistoryTxs(List<Transaction> transactions) {
	    for (Transaction tx : transactions)
            transactionList.put(tx.uid(), tx);
	}

	private void setLastUtxoUpdate(long newUtxoTime) {
		lastUtxoUpdate = newUtxoTime;
	}

	public void reloadConfig() {
		this.configHelper.loadConfig();
		rpcPort = configHelper.getRpcPort();
		if (configHelper.getAddressCount() != generatedAddressCount)
			generateForwardAddresses(false);

		if (coinRPCServer == null)
			return; // no rpc available, skip

		JSONRPCController.removeRPCServer(this);

		coinRPCServer = JSONRPCController.getRPCServer(this);

		LOGGER.log(Level.INFO, "[rpc] Starting JSON-RPC server for coin " + CoinTickerUtils.tickerToString(getTicker()) + " on port " + getRPCPort());
		coinRPCServer.start();
	}

	public KeyHandler getKeyHandler() {
		return keyHandler;
	}

	public BlocknetPeerGroup getBlocknetPeerGroup() {
		return blocknetPeerGroup;
	}

	public BlocknetPeer getBestBlocknetPeer(String currency) {
		if (blocknetPeerGroup == null)
			return null;

		return blocknetPeerGroup.getBestBlocknetPeer(currency);
	}

	public ArrayList<AddressBalance> getAddressKeyPairs() {
		return addressKeyPairs;
	}

	public ArrayList<CloudTransaction> getTransactionList() {
		return transactionObservableList;
	}

	public static AtomicInteger getBlockCount(CoinTicker ticker) {
		return blockCounts.putIfAbsent(ticker, new AtomicInteger(0));
	}

	public static HashMap<CoinTicker, AtomicInteger> getBlockCounts() {
		return blockCounts;
	}

	public int getRPCPort() {
		return rpcPort;
	}

	public boolean isTestnet() {
		return testnet;
	}

	public static String getVersionString() {
		return Version.SUBVERSION;
	}

	public void incrementUpdateFailures() {
		updateFailures += 1;
	}

	public void resetUpdateFailures() {
		updateFailures = 0;
	}

	public boolean isInstanceRunning() {
		return updateFailures < 5;
	}
}
