package io.cloudchains.app.net.protocols.blocknet;

import com.subgraph.orchid.encoders.Hex;
import io.cloudchains.app.net.protocols.blocknet.messages.VersionMessageImpl;
import io.cloudchains.app.net.xrouter.XRouterMessage;
import org.bitcoinj.core.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class BlocknetSerializer extends BitcoinSerializer {
	private final static LogManager LOGMANAGER = LogManager.getLogManager();
	private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

	private BlocknetParameters params;
	private boolean parseRetain;

	private static final HashMap<Class<? extends Message>, String> messageNames = new HashMap<>();

	static {
		messageNames.put(VersionMessage.class, "version");
		messageNames.put(VersionMessageImpl.class, "version");
		messageNames.put(InventoryMessage.class, "inv");
		messageNames.put(Block.class, "block");
		messageNames.put(GetDataMessage.class, "getdata");
		messageNames.put(Transaction.class, "tx");
		messageNames.put(AddressMessage.class, "addr");
		messageNames.put(Ping.class, "ping");
		messageNames.put(Pong.class, "pong");
		messageNames.put(VersionAck.class, "verack");
		messageNames.put(GetBlocksMessage.class, "getblocks");
		messageNames.put(GetHeadersMessage.class, "getheaders");
		messageNames.put(GetAddrMessage.class, "getaddr");
		messageNames.put(HeadersMessage.class, "headers");
		messageNames.put(BloomFilter.class, "filterload");
		messageNames.put(FilteredBlock.class, "merkleblock");
		messageNames.put(NotFoundMessage.class, "notfound");
		messageNames.put(MemoryPoolMessage.class, "mempool");
		messageNames.put(RejectMessage.class, "reject");
		messageNames.put(GetUTXOsMessage.class, "getutxos");
		messageNames.put(UTXOsMessage.class, "utxos");
	}

	public BlocknetSerializer(NetworkParameters params, boolean parseRetain) {
		super(params, parseRetain);

		if (!(params instanceof BlocknetParameters)) {
			throw new IllegalArgumentException("Invalid network parameters for Blocknet.");
		}

		this.params = (BlocknetParameters) params;
		this.parseRetain = parseRetain;
	}

	@Override
	public BlocknetPacketHeader deserializeHeader(ByteBuffer in) throws ProtocolException {
		//in.position(0);
		//seekPastMagicBytes(in);
		return new BlocknetPacketHeader(in);
	}

	@Override
	public Message deserializePayload(BitcoinSerializer.BitcoinPacketHeader header, ByteBuffer in) throws ProtocolException, BufferUnderflowException {
		//in.position(0);
		//seekPastMagicBytes(in);
		BlocknetPacketHeader blocknetPacketHeader = (BlocknetPacketHeader) header;

		byte[] payloadBytes = new byte[blocknetPacketHeader.getLength()];
		in.get(payloadBytes, 0, payloadBytes.length);

		if (!BlocknetUtils.verifyChecksum(blocknetPacketHeader, payloadBytes)) {
			throw new ProtocolException("Checksum failed to verify.");
		}

		switch (blocknetPacketHeader.getCommand().toLowerCase()) {
			case "xrouter":
				LOGGER.log(Level.FINER, "[blocknet-serializer] Received XRouter packet, at position: " + in.position());
				return new XRouterMessage(params, payloadBytes);
			case "version":
//				LOGGER.log(Level.FINER, "[blocknet-serializer] Version message received");
				return new VersionMessage(params, payloadBytes);
			case "inv":
//				LOGGER.log(Level.FINER, "[blocknet-serializer] Warning: Inventory messages are ignored");
				return null;
			case "block":
				return new Block(params, payloadBytes, 0, this, blocknetPacketHeader.getLength());
			case "merkleblock":
				return new FilteredBlock(params, payloadBytes);
			case "getdata":
				return new GetDataMessage(params, payloadBytes, this, blocknetPacketHeader.getLength());
			case "getblocks":
				return new GetBlocksMessage(params, payloadBytes);
			case "getheaders":
				return new GetHeadersMessage(params, payloadBytes);
			case "tx":
				return new Transaction(params, payloadBytes, 0, null, this, blocknetPacketHeader.getLength());
			case "addr":
				return makeAddressMessage(payloadBytes, blocknetPacketHeader.getLength());
			case "alert":
				return new AlertMessage(params, payloadBytes);
			case "ping":
				return new Ping(params, payloadBytes);
			case "pong":
				return new Pong(params, payloadBytes);
			case "verack":
				return new VersionAck(params, payloadBytes);
			case "headers":
				return new HeadersMessage(params, payloadBytes);
			case "filterload":
				return new BloomFilter(params, payloadBytes);
			case "notfound":
				return new NotFoundMessage(params, payloadBytes);
			case "mempool":
				return new MemoryPoolMessage();
			case "reject":
				return new RejectMessage(params, payloadBytes);
			case "utxos":
				return new UTXOsMessage(params, payloadBytes);
			case "getutxos":
				return new GetUTXOsMessage(params, payloadBytes);
			case "getsporks":
			case "ssc":
			case "mnget":
			case "xbridge":
//				LOGGER.log(Level.FINER, "[blocknet-serializer] Warning: This serializer does not support deserializing xbridge/ssc/mnget/getsporks packets yet.");
				return null;
			case "dseg":
//				LOGGER.log(Level.FINER, "[blocknet-serializer] Warning: This serializer does not support deserializing dseg packets yet.");
				return null;
			default:
				LOGGER.log(Level.FINER, "[blocknet-serializer] Warning: This serializer does not support deserializing " + blocknetPacketHeader.getCommand() + " packets (yet).");
				return new UnknownMessage(params, blocknetPacketHeader.getCommand(), payloadBytes);
		}
	}

	@Override
	public Message deserialize(ByteBuffer in) throws ProtocolException {
		//in.position(0);
		seekPastMagicBytes(in);
		BlocknetPacketHeader header = new BlocknetPacketHeader(in);

		return deserializePayload(header, in);
	}

	@Override
	public boolean isParseRetainMode() {
		return parseRetain;
	}

	@Override
	public AddressMessage makeAddressMessage(byte[] payloadBytes, int length) throws ProtocolException, UnsupportedOperationException {
		return null;
	}

	@Override
	public Message makeAlertMessage(byte[] payloadBytes) throws ProtocolException, UnsupportedOperationException {
		return new AlertMessage(params, payloadBytes);
	}

	@Override
	public Block makeBlock(byte[] payloadBytes, int offset, int length) throws ProtocolException, UnsupportedOperationException {
		return new Block(params, payloadBytes, offset, this, length);
	}

	@Override
	public Message makeBloomFilter(byte[] payloadBytes) throws ProtocolException, UnsupportedOperationException {
		return new BloomFilter(params, payloadBytes);
	}

	@Override
	public FilteredBlock makeFilteredBlock(byte[] payloadBytes) throws ProtocolException, UnsupportedOperationException {
		return new FilteredBlock(params, payloadBytes);
	}

	@Override
	public InventoryMessage makeInventoryMessage(byte[] payloadBytes, int length) throws ProtocolException, UnsupportedOperationException {
		return new InventoryMessage(params, payloadBytes, this, length);
	}

	@Override
	public Transaction makeTransaction(byte[] payloadBytes, int offset, int length, byte[] hash) throws ProtocolException, UnsupportedOperationException {
		return new Transaction(params, payloadBytes, offset, null, this, length);
	}

	@Override
	public void seekPastMagicBytes(ByteBuffer in) throws BufferUnderflowException {
		BlocknetUtils.seekPastMagicBytes(in, params);
	}

	@Override
	public void serialize(String name, byte[] message, OutputStream out) throws IOException, UnsupportedOperationException {
		byte[] header = BlocknetUtils.getHeader(name, (int) params.getPacketMagic(), message);

		out.write(header);
		out.write(message);

		LOGGER.log(Level.FINER, "[blocknet-serializer] Serialized " + name + " message. Bytes: " + new String(Hex.encode(header)) + new String(Hex.encode(message)));
	}

	@Override
	public void serialize(Message message, OutputStream out) throws IOException {
		if (message instanceof XRouterMessage) {
			params.getXRouterMessageSerializer(parseRetain).serialize(message, out);
		} else {
			String name = messageNames.get(message.getClass());
			if (name == null) {
				LOGGER.log(Level.FINER, "[blocknet-serializer] ERROR: BlocknetSerializer cannot serialize " + message.getClass().getSimpleName() + " (yet)!");
				return;
			}
			serialize(name, message.bitcoinSerialize(), out);
		}
	}
}
