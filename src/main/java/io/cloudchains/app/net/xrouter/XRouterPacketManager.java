package io.cloudchains.app.net.xrouter;

import com.google.common.base.Preconditions;
import com.subgraph.orchid.encoders.Hex;
import io.cloudchains.app.net.protocols.blocknet.BlocknetParameters;
import io.cloudchains.app.net.protocols.blocknet.BlocknetPeer;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Utils;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class XRouterPacketManager {
	private final static LogManager LOGMANAGER = LogManager.getLogManager();
	private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

	private static final int XROUTER_PACKET_VERSION = 0xff000023;

	private final XRouterMessageSerializer xRouterMessageSerializer;
	private final BlocknetParameters blocknetNetworkParameters;

	public XRouterPacketManager(XRouterMessageSerializer xRouterMessageSerializer, BlocknetParameters blocknetNetworkParameters) {
		this.xRouterMessageSerializer = xRouterMessageSerializer;
		this.blocknetNetworkParameters = blocknetNetworkParameters;
	}

	public static int getXRouterPacketVersion() {
		return XROUTER_PACKET_VERSION;
	}

	private byte[] signPacket(byte[] packetBytes, ECKey ecPrivateKey) {
		LOGGER.log(Level.FINER, "[xrouter] DEBUG: Packet bytes: " + new String(Hex.encode(packetBytes)));
		Sha256Hash packetHash = Sha256Hash.wrap(Sha256Hash.hash(packetBytes));
		LOGGER.log(Level.FINER, "[xrouter] DEBUG: Packet byte hash: " + packetHash.toString());
		ECKey.ECDSASignature rawSignature = ecPrivateKey.sign(packetHash).toCanonicalised();

		byte[] r = rawSignature.r.toByteArray();
		byte[] s = rawSignature.s.toByteArray();

		if (r.length > 32) {
			LOGGER.log(Level.FINER, "[xrouter] WARNING: Signature R is greater than 32 bytes! Trimming from the beginning. Size: " + r.length);
			LOGGER.log(Level.FINER, "[xrouter] WARNING: Signature R: " + new String(Hex.encode(r)));
		} else if (r.length < 32) {
			LOGGER.log(Level.FINER, "[xrouter] WARNING: Signature R is less than 32 bytes! Prepending null bytes to the beginning. Size: " + s.length);
			LOGGER.log(Level.FINER, "[xrouter] WARNING: Signature R: " + new String(Hex.encode(r)));

			r = prependNullTo32(r);
		}

		if (s.length > 32) {
			LOGGER.log(Level.FINER, "[xrouter] WARNING: Signature S is greater than 32 bytes! Trimming from the beginning. Size: " + s.length);
			LOGGER.log(Level.FINER, "[xrouter] WARNING: Signature S: " + new String(Hex.encode(s)));
		} else if (s.length < 32) {
			LOGGER.log(Level.FINER, "[xrouter] WARNING: Signature S is less than 32 bytes! Prepending null bytes. Size: " + s.length);
			LOGGER.log(Level.FINER, "[xrouter] WARNING: Signature S: " + new String(Hex.encode(s)));

			s = prependNullTo32(s);
		}

		byte[] signature = new byte[64];

		System.arraycopy(r, r.length - 32, signature, 0, 32);
		System.arraycopy(s, s.length - 32, signature, 32, 32);

		LOGGER.log(Level.FINER, "[xrouter] Signature: " + new String(Hex.encode(signature)) + ", byte length " + signature.length);

		return signature;
	}

	private byte[] prependNullTo32(byte[] toPrependTo) {
		Preconditions.checkState(toPrependTo.length < 32, "This array is too large (32 bytes or more)");

		byte[] processed = new byte[32];

		System.arraycopy(new byte[32 - toPrependTo.length], 0, processed, 0, 32 - toPrependTo.length);
		System.arraycopy(toPrependTo, 0, processed, 32 - toPrependTo.length, toPrependTo.length);

		return processed;
	}

	private XRouterMessage getPacket(BlocknetPeer blocknetPeer, int size, int commandId, String uuid, ECKey ecPublicKey, ECKey ecPrivateKey, HashMap<String, Object> body) {
		Preconditions.checkNotNull(xRouterMessageSerializer);
		Preconditions.checkNotNull(blocknetNetworkParameters);

		int extSize = size + 157;

		byte[] xRouterHeaderBytes;
		byte compactSize;
		int compactSizeBytes;

		if (extSize < 253) {
			xRouterHeaderBytes = new byte[158];
			compactSize = (byte) extSize;
			//LOGGER.log(Level.FINER, "Compact size = " + compactSize);
			xRouterHeaderBytes[0] = compactSize;
			compactSizeBytes = 1;
		} else if (extSize <= 65535) {
			xRouterHeaderBytes = new byte[160];
			compactSize = (byte) 253;
			//LOGGER.log(Level.FINER, "Compact size = " + compactSize + ", extSize = " + extSize);
			xRouterHeaderBytes[0] = compactSize;
			xRouterHeaderBytes[1] = (byte) (0xFF & (extSize));
			xRouterHeaderBytes[2] = (byte) (0xFF & (extSize >> 8));
			compactSizeBytes = 3;
		} else {
			xRouterHeaderBytes = new byte[162];
			compactSize = (byte) 254;
			//LOGGER.log(Level.FINER, "Compact size = " + compactSize + ", extSize = " + extSize);
			xRouterHeaderBytes[0] = compactSize;
			Utils.uint32ToByteArrayLE(extSize, xRouterHeaderBytes, 1);
			compactSizeBytes = 5;
		}

		int cursor = compactSizeBytes;
		//int cursor = 0;

		Utils.uint32ToByteArrayLE(XROUTER_PACKET_VERSION, xRouterHeaderBytes, cursor);
		cursor += 4;
		Utils.uint32ToByteArrayLE(commandId, xRouterHeaderBytes, cursor);
		cursor += 4;
		Utils.uint32ToByteArrayLE(Math.round(System.currentTimeMillis() / 1000), xRouterHeaderBytes, cursor);
		cursor += 4;
		Utils.uint32ToByteArrayLE(size, xRouterHeaderBytes, cursor);
		cursor += 4;

		//fill in reserved header fields with zeros
		System.arraycopy(new byte[8], 0, xRouterHeaderBytes, cursor, 8);
		cursor += 8;

		System.arraycopy(uuid.getBytes(), 0, xRouterHeaderBytes, cursor, 36);
		cursor += 36;

		byte[] pubkey = ecPublicKey.getPubKey();

		System.arraycopy(pubkey, 0, xRouterHeaderBytes, cursor, 33);
		cursor += 33;

		ByteBuffer xRouterHeaderBufSigned = ByteBuffer.allocate(xRouterHeaderBytes.length);
		xRouterHeaderBufSigned.put(xRouterHeaderBytes);
		xRouterHeaderBufSigned.position(cursor);

		System.arraycopy(new byte[64], 0, xRouterHeaderBytes, cursor, 64);
		cursor += 64;
		LOGGER.log(Level.FINER, "[xrouter] Serialized XRouter header. Cursor is at " + cursor);

		LOGGER.log(Level.FINER, "[xrouter] Serializing XRouter message (phase 1).");
		XRouterPacketHeader xRouterHeader = new XRouterPacketHeader(ByteBuffer.wrap(xRouterHeaderBytes));
		XRouterMessage message = new XRouterMessage(blocknetPeer, blocknetNetworkParameters, xRouterHeader, body);

		byte[] rawPktUnsigned = message.bitcoinSerialize();

		byte[] toSign = new byte[rawPktUnsigned.length - compactSizeBytes];
		System.arraycopy(rawPktUnsigned, compactSizeBytes, toSign, 0, toSign.length);

		byte[] signature = signPacket(toSign, ecPrivateKey);
		xRouterHeaderBufSigned.put(signature);

		xRouterHeaderBufSigned.flip();

		LOGGER.log(Level.FINER, "[xrouter] Serializing XRouter message (phase 2).");
		XRouterPacketHeader xRouterHeaderSigned = new XRouterPacketHeader(xRouterHeaderBufSigned);
		return new XRouterMessage(blocknetPeer, blocknetNetworkParameters, xRouterHeaderSigned, body);
	}

	private HashMap<String, Object> getBody(@Nullable String paymentTx) {
		HashMap<String, Object> body = new HashMap<>();
		if (paymentTx != null)
			body.put("paymentTx", paymentTx);

		return body;
	}

	public XRouterMessage getXrGetBlockCount(BlocknetPeer blocknetPeer, String uuid, String currency, ECKey ecPrivateKey, ECKey ecPublicKey) {
		String paymentTx = XRouterFeeUtils.getXRouterFeeTx(blocknetPeer,"xrGetBlockCount");

		HashMap<String, Object> body = getBody(paymentTx);
		body.put("currency", currency);

		int size = currency.length() + 1 + paymentTx.length() + 1 + 4;

		return getPacket(blocknetPeer, size, XRouterCommandUtils.commandStringToInt("xrGetBlockCount"), uuid, ecPublicKey, ecPrivateKey, body);
	}

	public XRouterMessage getXrService(BlocknetPeer blocknetPeer, String uuid, String command, ArrayList params, ECKey ecPrivateKey, ECKey ecPublicKey) {
		String paymentTx = XRouterFeeUtils.getXRouterFeeTx(blocknetPeer, "xrService");

		HashMap<String, Object> body = getBody(paymentTx);
		body.put("command", command);
		body.put("params", params);

		int size = command.length() + 1 + paymentTx.length() + 1 + 4;

		for (Object param : params) {
			if (param instanceof Boolean)
				size += 4;
			else if (param instanceof String)
				size += ((String) param).length() + 1;
			else if (param instanceof Integer)
				size += 4;
			else
				throw new IllegalArgumentException("Argument of unsupported class: " + param.getClass().getSimpleName());
		}

		return getPacket(blocknetPeer, size, XRouterCommandUtils.commandStringToInt("xrService"), uuid, ecPublicKey, ecPrivateKey, body);
	}

	public XRouterMessage getXrGetConfig(BlocknetPeer blocknetPeer, String uuid, String address, ECKey ecPrivateKey, ECKey ecPublicKey) {
		HashMap<String, Object> body = getBody(null);
		body.put("addr", address);

		int size = address.length() + 1;

		return getPacket(blocknetPeer, size, XRouterCommandUtils.commandStringToInt("xrGetConfig"), uuid, ecPublicKey, ecPrivateKey, body);
	}

	public XRouterMessage getXrSendTransaction(BlocknetPeer blocknetPeer, String uuid, String feePayment, String currency, String transaction, ECKey ecPrivateKey, ECKey ecPublicKey) {
		HashMap<String, Object> body = getBody(feePayment);
		body.put("currency", currency);
		body.put("transaction", transaction);

		int size = feePayment.length() + 1 + currency.length() + 1 + 4 + transaction.length() + 1;

		return getPacket(blocknetPeer, size, XRouterCommandUtils.commandStringToInt("xrSendTransaction"), uuid, ecPublicKey, ecPrivateKey, body);
	}

	public XRouterMessage getXrGetBlockHash(BlocknetPeer blocknetPeer, String uuid, String feePayment, String currency, String blockIndex, ECKey ecPrivateKey, ECKey ecPublicKey) {
		HashMap<String, Object> body = getBody(feePayment);
		body.put("currency", currency);
		body.put("blockId", blockIndex);

		int size = feePayment.length() + 1 + currency.length() + 1 + 4 + blockIndex.length() + 1;

		return getPacket(blocknetPeer, size, XRouterCommandUtils.commandStringToInt("xrGetBlockHash"), uuid, ecPublicKey, ecPrivateKey, body);
	}

	public XRouterMessage getXrGetBlock(BlocknetPeer blocknetPeer, String uuid, String feePayment, String currency, String blockHash, ECKey ecPrivateKey, ECKey ecPublicKey) {
		HashMap<String, Object> body = getBody(feePayment);
		body.put("currency", currency);
		body.put("blockHash", blockHash);

		int size = feePayment.length() + 1 + currency.length() + 1 + blockHash.length() + 1 + 4;

		return getPacket(blocknetPeer, size, XRouterCommandUtils.commandStringToInt("xrGetBlock"), uuid, ecPublicKey, ecPrivateKey, body);
	}

	public XRouterMessage getXrGetTransaction(BlocknetPeer blocknetPeer, String uuid, String currency, String txid, ECKey ecPrivateKey, ECKey ecPublicKey) {
		String paymentTx = XRouterFeeUtils.getXRouterFeeTx(blocknetPeer, "xrGetBlockCount");

		HashMap<String, Object> body = getBody(paymentTx);
		body.put("currency", currency);
		body.put("txid", txid);

		int size = paymentTx.length() + 1 + currency.length() + 1 + txid.length() + 1 + 4;

		return getPacket(blocknetPeer, size, XRouterCommandUtils.commandStringToInt("xrGetTransaction"), uuid, ecPublicKey, ecPrivateKey, body);
	}
}
