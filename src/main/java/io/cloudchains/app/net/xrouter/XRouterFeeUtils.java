package io.cloudchains.app.net.xrouter;

import com.google.common.base.Preconditions;
import com.subgraph.orchid.encoders.Hex;
import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.net.protocols.blocknet.BlocknetPeer;
import io.cloudchains.app.util.XRouterConfiguration;
import io.cloudchains.app.wallet.WalletHelper;
import org.bitcoinj.core.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class XRouterFeeUtils {
	private final static LogManager LOGMANAGER = LogManager.getLogManager();
	private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

	public static String getXRouterFeeTx(BlocknetPeer blocknetPeer, String xRouterCommand) {
		CoinInstance blocknetCoin = CoinInstance.getInstance(CoinInstance.getActiveBlocknetNetwork());
		WalletHelper blocknetWalletHelper = blocknetCoin.getWalletHelper();
		NetworkParameters params = blocknetCoin.getNetworkParameters();
		XRouterConfiguration xRouterConfig = blocknetPeer.getxRouterConfiguration();

		Preconditions.checkNotNull(xRouterConfig, "XRouter config was not received yet!");

		HashMap<String, Double> feeMap = xRouterConfig.getFeeMap();

		if (!feeMap.containsKey(xRouterCommand)) {
			LOGGER.log(Level.FINER, "[xrouter-fee-utils] WARNING: Invalid/unknown XRouter command supplied to getXRouterFeeTx()! Assuming this command is free.");
			LOGGER.log(Level.FINER, "[xrouter-fee-utils] Command: " + xRouterCommand);

			return "nohash;nofee";
		}

		double fee = feeMap.get(xRouterCommand);

		Coin xRouterFeeAmt = Coin.valueOf((long) Math.floor(fee * Coin.COIN.value));
		if (xRouterFeeAmt.value == 0) {
			LOGGER.log(Level.FINER, "[xrouter-fee-utils] DEBUG: This command is free.");
			return "nohash;nofee";
		}

		double totalSpending = fee + blocknetCoin.getConfigHelper().getFee();
		double totalAvailable = blocknetWalletHelper.getSpendBalance(totalSpending);
		double changeAmt = ((totalAvailable - blocknetCoin.getConfigHelper().getFee()) - fee);

		Address xRouterPaymentAddress = Address.fromBase58(params, xRouterConfig.getFeeAddress());
		Coin blocknetNetworkFeeAmt = Coin.valueOf((long) Math.floor(blocknetCoin.getConfigHelper().getFee() * Coin.COIN.value));
		Coin xRouterChangeAmt = Coin.valueOf((long) Math.floor(totalAvailable * Coin.COIN.value)).minus(blocknetNetworkFeeAmt).minus(xRouterFeeAmt);

		TransactionOutput feeOutput = new TransactionOutput(params, null, xRouterFeeAmt, xRouterPaymentAddress);

		ArrayList<TransactionOutput> outputs = new ArrayList<>();
		outputs.add(feeOutput);

		if (changeAmt > 0.06) {
			double halvedAmt = changeAmt / 3;
			Coin halvedChangeAmt = Coin.valueOf((long) Math.floor(halvedAmt * Coin.COIN.value));
			TransactionOutput halvedChangeOutput = new TransactionOutput(params, null, halvedChangeAmt, blocknetWalletHelper.getChangeAddress());

			for (int i = 0; i < 3; i++) {
				outputs.add(halvedChangeOutput);
			}
		} else {
			TransactionOutput changeOutput = new TransactionOutput(params, null, xRouterChangeAmt, blocknetWalletHelper.getChangeAddress());
			outputs.add(changeOutput);
		}

		Transaction xRouterFeeTx = blocknetWalletHelper.createRawTransactionWithAllUTXOs(outputs, totalAvailable);

		String feetx = new String(Hex.encode(xRouterFeeTx.bitcoinSerialize()));
		LOGGER.log(Level.FINER, "[xrouter-fee-utils] XRouter fee transaction string representation:");
		LOGGER.log(Level.FINER, xRouterFeeTx.toString());
		LOGGER.log(Level.FINER, "[xrouter-fee-utils] DEBUG: Feetx: " + feetx);
		return feetx;
	}

	public static TransactionOutput createXrSendTransactionFeeOutput(BlocknetPeer blocknetPeer) {
		CoinInstance blocknetCoin = CoinInstance.getInstance(CoinInstance.getActiveBlocknetNetwork());
		XRouterConfiguration xRouterConfig = blocknetPeer.getxRouterConfiguration();
		HashMap<String, Double> feeMap = xRouterConfig.getFeeMap();
		Preconditions.checkState(feeMap.containsKey("xrSendTransaction"), "Fee map has no fee for xrSendTransaction");

		double fee = feeMap.get("xrSendTransaction");
		String feeAddress = xRouterConfig.getFeeAddress();

		Coin feeAmount = Coin.valueOf((long) Math.floor(fee * Coin.COIN.value));

		return new TransactionOutput(blocknetCoin.getNetworkParameters(), null, feeAmount, Address.fromBase58(blocknetCoin.getNetworkParameters(), feeAddress));
	}

	public static String coveredXrFee(BlocknetPeer blocknetPeer, Transaction transaction) {
		CoinInstance blocknetCoin = CoinInstance.getInstance(CoinInstance.getActiveBlocknetNetwork());
		WalletHelper.setAsSpent(blocknetCoin.getTicker(), transaction, true);

		String xrFee = getXRouterFeeTx(blocknetPeer, "xrSendTransaction");

		if (xrFee.equals("nohash;nofee"))
			return xrFee;

		Transaction tx = new Transaction(blocknetCoin.getNetworkParameters(), Hex.decode(xrFee));

		if (tx.getInputs().size() == 0)
			return null;

		return xrFee;
	}
}
