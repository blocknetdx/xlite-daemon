package io.cloudchains.app.net.xrouter;

import com.google.common.base.Preconditions;
import com.subgraph.orchid.encoders.Hex;
import io.cloudchains.app.net.protocols.blocknet.BlocknetPacketHeader;
import io.cloudchains.app.net.protocols.blocknet.BlocknetParameters;
import io.cloudchains.app.net.protocols.blocknet.BlocknetUtils;
import org.bitcoinj.core.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class XRouterMessageSerializer extends MessageSerializer {
	private final static LogManager LOGMANAGER = LogManager.getLogManager();
	private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

	private boolean parseRetain;
	private BlocknetParameters params;

	public XRouterMessageSerializer(boolean parseRetain, BlocknetParameters params) {
		this.parseRetain = parseRetain;
		this.params = params;
	}

	@Override
	public XRouterMessage deserialize(ByteBuffer in) throws ProtocolException, UnsupportedOperationException {
		seekPastMagicBytes(in);
		BlocknetPacketHeader header = deserializeHeader(in);

		return deserializePayload(header, in);
	}

	@Override
	public BlocknetPacketHeader deserializeHeader(ByteBuffer in) throws ProtocolException, UnsupportedOperationException {
		return new BlocknetPacketHeader(in);
	}

	@Override
	public XRouterMessage deserializePayload(BitcoinSerializer.BitcoinPacketHeader header, ByteBuffer in) throws ProtocolException, BufferUnderflowException, UnsupportedOperationException {
		BlocknetPacketHeader blocknetPacketHeader = (BlocknetPacketHeader) header;

		byte[] payloadBytes = new byte[blocknetPacketHeader.getLength()];
		in.get(payloadBytes, 0, payloadBytes.length);

		if (!BlocknetUtils.verifyChecksum(blocknetPacketHeader, payloadBytes)) {
			throw new ProtocolException("XRouter packet's checksum failed to verify.");
		}

		int dataLength = in.capacity() - in.position();

		byte[] data = new byte[dataLength];
		in.get(data, 0, dataLength);

		return new XRouterMessage(params, data);
	}

	@Override
	public boolean isParseRetainMode() {
		return parseRetain;
	}

	@Override
	public AddressMessage makeAddressMessage(byte[] payloadBytes, int length) throws ProtocolException, UnsupportedOperationException {
		throw new UnsupportedOperationException("This serializer does not support address message construction.");
	}

	@Override
	public Message makeAlertMessage(byte[] payloadBytes) throws ProtocolException, UnsupportedOperationException {
		throw new UnsupportedOperationException("This serializer does not support alert message construction.");
	}

	@Override
	public Block makeBlock(byte[] payloadBytes, int offset, int length) throws ProtocolException, UnsupportedOperationException {
		throw new UnsupportedOperationException("This serializer does not support block construction.");
	}

	@Override
	public Message makeBloomFilter(byte[] payloadBytes) throws ProtocolException, UnsupportedOperationException {
		throw new UnsupportedOperationException("This serializer does not support bloom filter construction.");
	}

	@Override
	public FilteredBlock makeFilteredBlock(byte[] payloadBytes) throws ProtocolException, UnsupportedOperationException {
		throw new UnsupportedOperationException("This serializer does not support block construction.");
	}

	@Override
	public InventoryMessage makeInventoryMessage(byte[] payloadBytes, int length) throws ProtocolException, UnsupportedOperationException {
		throw new UnsupportedOperationException("This serializer does not support inventory message construction.");
	}

	@Override
	public Transaction makeTransaction(byte[] payloadBytes, int offset, int length, byte[] hash) throws ProtocolException, UnsupportedOperationException {
		throw new UnsupportedOperationException("This serializer does not support transaction construction");
	}

	@Override
	public void serialize(String name, byte[] message, OutputStream out) throws UnsupportedOperationException {
		throw new UnsupportedOperationException("This serializer currently does not support name/message serialization.");
	}

	private void serialize(byte[] data, OutputStream out) throws IOException {
		byte[] header = BlocknetUtils.getHeader("xrouter", (int) params.getPacketMagic(), data);

		out.write(header);
		out.write(data);

		LOGGER.log(Level.FINER, "[blocknet-serializer] Serialized xrouter message. Bytes: " + new String(Hex.encode(header)) + new String(Hex.encode(data)));
	}

	/**
	 * Serialize XRouter packet header and body
	 * @param message XRouterMessage The XRouter message to serialize
	 * @param out OutputStream The output stream to which to write the serialized XRouter message bytes
	 * @throws IOException If writing fails or another I/O related error occurs
	 */
	@Override
	public void serialize(Message message, OutputStream out) throws IOException {
		Preconditions.checkArgument(message instanceof XRouterMessage, "This message is not an XRouter message.");

		XRouterMessage xRouterMessage = (XRouterMessage) message;
		serialize(xRouterMessage.bitcoinSerialize(), out);
	}

	@Override
	public void seekPastMagicBytes(ByteBuffer in) throws BufferUnderflowException {
		BlocknetUtils.seekPastMagicBytes(in, params);
	}
}
