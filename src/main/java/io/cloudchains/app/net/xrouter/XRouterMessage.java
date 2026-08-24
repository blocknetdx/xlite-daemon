package io.cloudchains.app.net.xrouter;

import com.google.common.base.Preconditions;
import com.subgraph.orchid.encoders.Hex;
import io.cloudchains.app.net.protocols.blocknet.BlocknetParameters;
import io.cloudchains.app.net.protocols.blocknet.BlocknetPeer;
import io.cloudchains.app.util.XRouterConfiguration;
import org.bitcoinj.core.Message;
import org.bitcoinj.core.ProtocolException;
import org.bitcoinj.core.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class XRouterMessage extends Message {
	private final static LogManager LOGMANAGER = LogManager.getLogManager();
	private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

	private BlocknetPeer blocknetPeer;

	private XRouterPacketHeader xRouterHeader;
	private byte[] data;

	private HashMap<String, Object> parsedData;
	private BlocknetParameters params;

	public XRouterMessage(BlocknetParameters params, byte[] data) {
		this.data = data;

		this.params = params;
		this.parsedData = new HashMap<>();
		parseHeader();
		parse();
	}

	XRouterMessage(BlocknetPeer blocknetPeer, BlocknetParameters params, XRouterPacketHeader xRouterHeader, HashMap<String, Object> parsedData) {
		this.blocknetPeer = blocknetPeer;

		this.xRouterHeader = xRouterHeader;
		this.parsedData = parsedData;

		this.params = params;
		this.data = bitcoinSerialize();
	}

	private void writeCurrency(HashMap<String, Object> body, OutputStream out) throws IOException {
		out.write(((String) body.get("currency")).getBytes());
		out.write(0x00);
	}

	private void writeAccountAndNumber(String account, String number, OutputStream out) throws IOException {
		out.write(account.getBytes());
		out.write(0x00);
		out.write(number.getBytes());
		out.write(0x00);
	}

	/**
	 * Write Payment TX to unserialized XRouter Packet
	 * @param body HashMap<String, Object> The parsed XRouter packet body
	 * @param out OutputStream The output stream to which to write the serialized transaction info
	 * @throws IOException If writing fails or another error occurs
	 */
	private void writePaymentTx(HashMap<String, Object> body, OutputStream out) throws IOException {
		if (!body.containsKey("paymentTx"))
			return;

		out.write(((String) body.get("paymentTx")).getBytes());
		out.write(0x00);
	}

	public XRouterPacketHeader getXRouterHeader() {
		return xRouterHeader;
	}

	public HashMap<String, Object> getParsedData() {
		return parsedData;
	}

	@Override
	public byte[] bitcoinSerialize() {
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

		try {
			bitcoinSerializeToStream(byteArrayOutputStream);
		} catch (Exception e) {
			LOGGER.log(Level.FINER, "Error while serializing XRouter packet! Invalid packet structure?");
			e.printStackTrace();
			return null;
		}

		return byteArrayOutputStream.toByteArray();
	}

	@Override
	protected void bitcoinSerializeToStream(OutputStream stream) throws IOException {
		if (xRouterHeader.getExtSize() < 253) {
			stream.write(xRouterHeader.getCompactSizeBytes());
		} else if (xRouterHeader.getExtSize() <= 65535) {
			stream.write((byte) 253);
			stream.write(xRouterHeader.getCompactSizeBytes());
		} else {
			stream.write((byte) 254);
			stream.write(xRouterHeader.getCompactSizeBytes());
		}

		Utils.uint32ToByteStreamLE(xRouterHeader.getVersion(), stream);
		Utils.uint32ToByteStreamLE(xRouterHeader.getCommand(), stream);
		Utils.uint32ToByteStreamLE(xRouterHeader.getTimestamp(), stream);
		Utils.uint32ToByteStreamLE(xRouterHeader.getSize(), stream);
		stream.write(new byte[8]);
		stream.write(xRouterHeader.getUUID().getBytes());
		stream.write(xRouterHeader.getPubkey());
		stream.write(xRouterHeader.getSignature());

		switch (XRouterCommandUtils.commandIdToString(xRouterHeader.getCommand())) {
			case "xrReply":
			case "xrConfigReply": {
				stream.write(((String) parsedData.get("reply")).getBytes());
				stream.write(0x00);
				break;
			}
			case "xrGetReply": {
				LOGGER.log(Level.FINER, "[xrouter-message] DEBUG: Fetching reply for packet " + xRouterHeader.getUUID());
				break;
			}
			case "xrGetConfig": {
				stream.write(((String) parsedData.get("addr")).getBytes());
				stream.write(0x00);
				break;
			}
			case "xrGetBlockCount": {
				writeCurrency(parsedData, stream);
				writePaymentTx(parsedData, stream);
				Utils.uint32ToByteStreamLE(0, stream); //param count
				break;
			}
			case "xrGetBlockHash": {
				writeCurrency(parsedData, stream);
				writePaymentTx(parsedData, stream);
				Utils.uint32ToByteStreamLE(1, stream); //param count
				stream.write(((String) parsedData.get("blockId")).getBytes());
				stream.write(0x00);
				break;
			}
			case "xrGetBlock": {
				writeCurrency(parsedData, stream);
				writePaymentTx(parsedData, stream);
				Utils.uint32ToByteStreamLE(1, stream); //param count
				stream.write(((String) parsedData.get("blockHash")).getBytes());
				stream.write(0x00);
				break;
			}
			case "xrGetTransaction": {
				writeCurrency(parsedData, stream);
				writePaymentTx(parsedData, stream);
				Utils.uint32ToByteStreamLE(1, stream); //param count
				stream.write(((String) parsedData.get("txid")).getBytes());
				stream.write(0x00);
				break;
			}
			case "xrSendTransaction": {
				writeCurrency(parsedData, stream);
				writePaymentTx(parsedData, stream);
				Utils.uint32ToByteStreamLE(1, stream); //param count
				stream.write(((String) parsedData.get("transaction")).getBytes());
				stream.write(0x00);
				break;
			}
			case "xrGetTxBloomFilter": {
				writeCurrency(parsedData, stream);
				writePaymentTx(parsedData, stream);
				stream.write(((String) parsedData.get("number_s")).getBytes());
				stream.write(0x00);
				break;
			}
			case "xrGenerateBloomFilter": {
				LOGGER.log(Level.FINER, "[xrouter-message] ERROR: Attempted to serialize unsupported command 41.");
				break;
			}
			case "xrGetBlocks": {
				writeCurrency(parsedData, stream);
				writePaymentTx(parsedData, stream);
				writeAccountAndNumber((String) parsedData.get("account"), (String) parsedData.get("number"), stream);
				break;
			}
			case "xrGetTransactions":
			case "xrGetBalanceUpdate": {
				writeCurrency(parsedData, stream);
				writePaymentTx(parsedData, stream);
				writeAccountAndNumber((String) parsedData.get("account"), (String) parsedData.get("number_s"), stream);
				break;
			}
			case "xrGetBlockAtTime": {
				LOGGER.log(Level.FINER, "[xrouter-message] ERROR: Attempted to serialize unsupported command 52.");
				break;
			}
			case "xrGetBalance": { //OBSOLETE, only implemented for backwards compatibility
				writeCurrency(parsedData, stream);
				writePaymentTx(parsedData, stream);
				stream.write(((String) parsedData.get("account")).getBytes());
				stream.write(0x00);
				break;
			}
			case "xrService": {
				String command = (String) parsedData.get("command");
				LOGGER.log(Level.FINER, "[xrService] Command: " + command);

				XRouterConfiguration.XRouterPluginConfiguration pluginConfig = blocknetPeer.getPluginConfig(command);

				if (pluginConfig == null) {
					LOGGER.log(Level.FINER, "[xrService] ERROR: Unsupported server xrs plugin: " + command);
					LOGGER.log(Level.FINER, "[xrService] ERROR: Aborting transmission.");
					throw new IllegalArgumentException("Unsupported server xrs plugin: " + command);
				}

				ArrayList<Class> pluginParamTypes = pluginConfig.getParamTypes();

				stream.write(command.getBytes());
				stream.write(0x00);

				writePaymentTx(parsedData, stream);

				ArrayList params = (ArrayList) parsedData.get("params");

				Utils.uint32ToByteStreamLE(params.size(), stream); //param count

				for (int i = 0; i < params.size(); i++) {
					Class paramClass = pluginParamTypes.get(i);
					Object param = params.get(i);
					String classStr = XRouterConfiguration.getStringByClass(paramClass);

					if (!(param instanceof String && ((String) param).equalsIgnoreCase("true") || ((String) param).equalsIgnoreCase("false")))
						Preconditions.checkState(paramClass.isInstance(param), "Supplied parameter at index " + i + " is not of type '" + classStr + "'. Aborting transmission.");

					LOGGER.log(Level.FINER, "[xrService] DEBUG: Parameter " + i + " is of type " + classStr);

					switch (classStr) {
						case "string": {
							String typedParam = (String) param;
							stream.write(typedParam.getBytes());
							stream.write(0x00);
							break;
						}
						case "bool": {
							int typedParam;

							if (((String) param).equalsIgnoreCase("true"))
								typedParam = 1;
							else
								typedParam = 0;

							Utils.uint32ToByteStreamLE(typedParam, stream);
							break;
						}
						case "int": {
							Integer typedParam = (Integer) param;
							Utils.uint32ToByteStreamLE(typedParam, stream);
							break;
						}
						default: {
							LOGGER.log(Level.FINER, "[xrService] ERROR: Encountered unhandled parameter of type " + classStr + ". Aborting transmission.");
							throw new IllegalStateException("Bad parameter type at index " + i + ": " + classStr);
						}
					}
				}
				break;
			}
			default: { //xrInvalid
				break;
			}
		}
	}

	private String readStringNT(ByteBuffer in) {
		StringBuilder stringBuilder = new StringBuilder();

		byte buf;

		while ((buf = in.get()) != 0x00) {
			stringBuilder.append((char) buf);
		}

		return stringBuilder.toString();
	}

	private void readCurrency(ByteBuffer buf) {
		String currency = readStringNT(buf);
		parsedData.put("currency", currency);
	}

	private void readPaymentTx(ByteBuffer buf) {
		String paymentTx = readStringNT(buf);
		parsedData.put("paymentTx", paymentTx);
	}

	private void readAccountAndNumber(ByteBuffer buf, String secondFieldName) {
		String account = readStringNT(buf);
		parsedData.put("account", account);
		offset += account.length();

		String number = readStringNT(buf);
		parsedData.put(secondFieldName, number);
		offset += number.length();
	}

	private void parseHeader() throws ProtocolException {
		xRouterHeader = new XRouterPacketHeader(ByteBuffer.wrap(data));
	}

	@Override
	protected void parse() throws ProtocolException {
		parsedData.put("header", xRouterHeader);

		LOGGER.log(Level.FINER, "Received raw XRouter packet: " + new String(Hex.encode(data)));
		ByteBuffer buf = ByteBuffer.wrap(data);
		buf.position(xRouterHeader.getHeaderLength());

		int command = xRouterHeader.getCommand();

		switch (XRouterCommandUtils.commandIdToString(command)) {
			case "xrReply":
			case "xrConfigReply": {
				String reply = readStringNT(buf);
				parsedData.put("reply", reply);
				LOGGER.log(Level.FINER, "[xrouter-message] Got reply: '" + reply + "' for packet with UUID '" + xRouterHeader.getUUID() + "'");
				break;
			}
			case "xrGetReply": {
				LOGGER.log(Level.FINER, "[xrouter-message] WARNING: Server asked to fetch reply, but we aren't a server.");
				break;
			}
			case "xrGetConfig": {
				LOGGER.log(Level.FINER, "[xrouter-message] WARNING: Server asked us for config, but we aren't a servicenode.");
				break;
			}
			case "xrGetBlockCount": {
				readCurrency(buf);
				readPaymentTx(buf);
				break;
			}
			case "xrGetBlockHash": {
				readCurrency(buf);

				String blockId = readStringNT(buf);
				parsedData.put("blockId", blockId);

				readPaymentTx(buf);
				break;
			}
			case "xrGetBlock": {
				readCurrency(buf);

				String blockHash = readStringNT(buf);
				parsedData.put("blockHash", blockHash);

				readPaymentTx(buf);
				break;
			}
			case "xrGetTransaction": {
				readCurrency(buf);

				String txid = readStringNT(buf);
				parsedData.put("txid", txid);

				readPaymentTx(buf);
				break;
			}
			case "xrGetBlocks": {
				readCurrency(buf);
				readPaymentTx(buf);

				readAccountAndNumber(buf, "number");
				break;
			}
			case "xrGetTransactions":
			case "xrGetBalanceUpdate": {
				readCurrency(buf);
				readPaymentTx(buf);

				readAccountAndNumber(buf, "number_s");
				break;
			}
			case "xrGetBalance": { //OBSOLETE
				readCurrency(buf);

				String account = readStringNT(buf);
				parsedData.put("account", account);

				readPaymentTx(buf);
				break;
			}
			case "xrGetTxFilter": {
				readCurrency(buf);
				readPaymentTx(buf);

				String number_s = readStringNT(buf);
				parsedData.put("number_s", number_s);
				break;
			}
			case "xrSendTransaction": {
				readCurrency(buf);
				readPaymentTx(buf);

				String transaction = readStringNT(buf);
				parsedData.put("transaction", transaction);
				break;
			}
			case "xrGetBlockAtTime": {
				readCurrency(buf);

				String timestamp = readStringNT(buf);
				parsedData.put("timestamp", timestamp);

				readPaymentTx(buf);
				break;
			}
			case "xrService": {

				String paymentTx = readStringNT(buf);
				parsedData.put("command", paymentTx);

				ArrayList<String> params = new ArrayList<>();
				while (buf.position() < buf.capacity()) {
					String thisParam = readStringNT(buf);
					params.add(thisParam);
				}

				parsedData.put("params", params);
				break;
			}
			default: { //xbcInvalid
				break;
			}
		}
	}
}
