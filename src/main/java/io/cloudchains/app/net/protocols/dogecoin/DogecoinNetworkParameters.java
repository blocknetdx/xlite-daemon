package io.cloudchains.app.net.protocols.dogecoin;

import org.bitcoinj.core.*;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.utils.MonetaryFormat;

public class DogecoinNetworkParameters extends NetworkParameters {

	public DogecoinNetworkParameters() {
		super();
	}

	@Override
	public String getPaymentProtocolId() {
		return "main";
	}

	@Override
	public void checkDifficultyTransitions(StoredBlock storedPrev, Block next, BlockStore blockStore) throws VerificationException, BlockStoreException {}

	@Override
	public Coin getMaxMoney() {
		return Coin.valueOf(2000000000 * Coin.COIN.value);
	}

	@Override
	public Coin getMinNonDustOutput() {
		return Transaction.MIN_NONDUST_OUTPUT;
	}

	@Override
	public MonetaryFormat getMonetaryFormat() {
		return new MonetaryFormat().code(0, "DOGE");
	}

	@Override
	public String getUriScheme() {
		return "dogecoin:";
	}

	@Override
	public boolean hasMaxMoney() {
		return true;
	}

	@Override
	public BitcoinSerializer getSerializer(boolean parseRetain) {
		return new BitcoinSerializer(this, parseRetain);
	}

	@Override
	public int getProtocolVersionNum(ProtocolVersion version) {
		return 70004;
	}

	@Override
	public int getAddressHeader() {
		return 30;
	}

	@Override
	public int getP2SHHeader() {
		return 22;
	}

	@Override
	public int getDumpedPrivateKeyHeader() {
		return 158;
	}

	@Override
	public int[] getAcceptableAddressCodes() {
		return new int[] {getAddressHeader(), getP2SHHeader()};
	}

	@Override
	public int getBip32HeaderPriv() {
		return 0x02fac398;
	}

	@Override
	public int getBip32HeaderPub() {
		return 0x02facafd;
	}

	@Override
	public int getSubsidyDecreaseBlockCount() {
		return 100000;
	}

	@Override
	public int getInterval() {
		return 108;
	}

	@Override
	public String getId() {
		return "DOGE";
	}
}
