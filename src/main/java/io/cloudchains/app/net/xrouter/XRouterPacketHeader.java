package io.cloudchains.app.net.xrouter;

import com.subgraph.orchid.encoders.Hex;
import org.bitcoinj.core.Utils;

import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class XRouterPacketHeader {
	private final static LogManager LOGMANAGER = LogManager.getLogManager();
	private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

	private byte[] compactSizeBytes;
	private int version;
	private int command;
	private int timestamp;
	private int size;
	private String uuid;
	private byte[] pubkey;
	private byte[] signature;

	private int headerLength;

	public XRouterPacketHeader(ByteBuffer in) {
		byte compactSize = in.get();
		byte[] rawHeader;
		in.rewind();
		int cursor = 1;

		if (compactSize == (byte) 253) {
			rawHeader = new byte[160];
			in.get(rawHeader, 0, rawHeader.length);
			compactSizeBytes = new byte[2];
		} else if (compactSize == (byte) 254) {
			rawHeader = new byte[162];
			in.get(rawHeader, 0, rawHeader.length);
			compactSizeBytes = new byte[4];
		} else if (compactSize == (byte) 255) {
			rawHeader = new byte[166];
			in.get(rawHeader, 0, rawHeader.length);
			compactSizeBytes = new byte[8];
		} else {
			rawHeader = new byte[158];
			in.get(rawHeader, 0, rawHeader.length);
			compactSizeBytes = new byte[1];
			cursor = 0;
		}

		System.arraycopy(rawHeader, cursor, compactSizeBytes, 0, compactSizeBytes.length);
		cursor += compactSizeBytes.length;

		LOGGER.log(Level.FINER, "[xrouter] Retrieved compact size: " + new String(Hex.encode(new byte[]{compactSize})));
		LOGGER.log(Level.FINER, "[xrouter] Retrieved compact size bytes: " + new String(Hex.encode(compactSizeBytes)));

		version = (int) Utils.readUint32(rawHeader, cursor);
		cursor += 4;
		LOGGER.log(Level.FINER, "[xrouter] Retrieved version: " + version);
		command = (int) Utils.readUint32(rawHeader, cursor);
		cursor += 4;
		LOGGER.log(Level.FINER, "[xrouter] Retrieved command: " + command);
		timestamp = (int) Utils.readUint32(rawHeader, cursor);
		cursor += 4;
		LOGGER.log(Level.FINER, "[xrouter] Retrieved timestamp: " + timestamp);
		size = (int) Utils.readUint32(rawHeader, cursor);
		cursor += 4;
		LOGGER.log(Level.FINER, "[xrouter] Retrieved size: " + size);

		//reserved header fields
		//we don't use these fields, so we skip them
		cursor += 8;

		byte[] uuidArr = new byte[36];
		System.arraycopy(rawHeader, cursor, uuidArr, 0, uuidArr.length);
		cursor += 36;

		uuid = new String(uuidArr);
		LOGGER.log(Level.FINER, "[xrouter] Retrieved UUID: " + uuid);

		byte[] pubkeyArr = new byte[33];
		System.arraycopy(rawHeader, cursor, pubkeyArr, 0, pubkeyArr.length);
		cursor += 33;
		LOGGER.log(Level.FINER, "[xrouter] Retrieved pubkey: " + new String(Hex.encode(pubkeyArr)));
		pubkey = pubkeyArr;

		byte[] sigArr = new byte[64];
		System.arraycopy(rawHeader, cursor, sigArr, 0, sigArr.length);
		cursor += 64;
		LOGGER.log(Level.FINER, "[xrouter] Retrieved signature: " + new String(Hex.encode(sigArr)));
		signature = sigArr;

		LOGGER.log(Level.FINER, "[xrouter] XRouter header read complete, at position: " + cursor);
		headerLength = cursor;
		//should have read 157 bytes at this point (excluding compact size)
	}

	byte[] getCompactSizeBytes() {
		return compactSizeBytes;
	}

	int getVersion() {
		return version;
	}

	public int getCommand() {
		return command;
	}

	int getTimestamp() {
		return timestamp;
	}

	int getExtSize() {
		return size + 157;
	}

	int getSize() {
		return size;
	}

	public String getUUID() {
		return uuid;
	}

	byte[] getPubkey() {
		return pubkey;
	}

	byte[] getSignature() {
		return signature;
	}

	int getHeaderLength() {
		return headerLength;
	}
}
