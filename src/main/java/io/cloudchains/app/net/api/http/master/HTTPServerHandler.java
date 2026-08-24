package io.cloudchains.app.net.api.http.master;

import com.google.common.base.Preconditions;
import com.google.gson.*;
import com.subgraph.orchid.encoders.Base64;
import com.subgraph.orchid.encoders.Hex;
import io.cloudchains.app.App;
import io.cloudchains.app.Version;
import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.net.CoinTicker;
import io.cloudchains.app.net.CoinTickerUtils;
import io.cloudchains.app.net.api.http.client.HTTPClient;
import io.cloudchains.app.net.protocols.blocknet.BlocknetPeer;
import io.cloudchains.app.util.AddressBalance;
import io.cloudchains.app.util.ConfigHelper;
import io.cloudchains.app.util.UTXO;
import io.cloudchains.app.wallet.WalletHelper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import org.bitcoinj.core.*;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.SignatureException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class HTTPServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
	private final static LogManager LOGMANAGER = LogManager.getLogManager();
	private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

	private ConfigHelper configHelper;

	HTTPServerHandler() {
		configHelper = new ConfigHelper("master");

		if (configHelper.getRpcUsername().isEmpty() && configHelper.getRpcPassword().isEmpty()) {
			configHelper.setRpcUsername(generateRandomString(12));
			configHelper.setRpcPassword(generateRandomString(32));

			configHelper.writeConfig();
		}
	}

	private String generateRandomString(int length) {
		SecureRandom secureRandom = new SecureRandom();

		byte[] token = new byte[length];
		secureRandom.nextBytes(token);

		return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(token);
	}

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
    }

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		cause.printStackTrace();

		FullHttpResponse httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST);
		writeResponse(ctx, httpResponse, null);
		ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
		ctx.close();
	}

	@Override
	public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
		LOGGER.log(Level.FINER, "[http-server-handler] DEBUG: Channel read complete. Flushing context.");
		super.channelReadComplete(ctx);
		ctx.flush();
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
		boolean successfulAuth = false;
		HttpResponseStatus status = HttpResponseStatus.OK;
		JsonObject response = new JsonObject();

		if (request != null) {
			HttpHeaders httpHeaders = request.headers();

			if (HttpUtil.is100ContinueExpected(request)) {
				send100Continue(ctx);
			}

			String headerUser;
			String headerPass;
			if (httpHeaders.contains("Authorization")) {
				String authorization = httpHeaders.get("Authorization");

				if (authorization != null && authorization.toLowerCase().startsWith("basic")) {
					String base64Credentials = authorization.substring("Basic".length()).trim();
					byte[] credDecoded = Base64.decode(base64Credentials);
					String credentials = new String(credDecoded, StandardCharsets.UTF_8);
					final String[] values = credentials.split(":", 2);

					headerUser = values[0];
					headerPass = values[1];

					if (headerUser.equals(configHelper.getRpcUsername()) && headerPass.equals(configHelper.getRpcPassword())) {
						successfulAuth = true;
						LOGGER.log(Level.FINER, "[http-server-handler] Successful Auth");
					}
				}
			}

			if (!request.uri().equals("/")) {
				JsonObject onlyServerRootJSON = new JsonObject();
				onlyServerRootJSON.addProperty("code", -1002);
				onlyServerRootJSON.addProperty("message", "Only the server root ('/') is being served.");

				response.add("error", onlyServerRootJSON);
				response.add("result", JsonNull.INSTANCE);
				status = HttpResponseStatus.BAD_REQUEST;
			}

			if (request.method() != HttpMethod.POST) {
				JsonObject onlyPostAllowedJSON = new JsonObject();

				onlyPostAllowedJSON.addProperty("code", -1003);
				onlyPostAllowedJSON.addProperty("message", "Only HTTP POST is accepted.");

				response.add("error", onlyPostAllowedJSON);
				response.add("result", JsonNull.INSTANCE);
				status = HttpResponseStatus.BAD_REQUEST;
			}

			if (!successfulAuth) {
				JsonObject onlyServerRootJSON = new JsonObject();
				onlyServerRootJSON.addProperty("code", -1111);
				onlyServerRootJSON.addProperty("message", "Unauthorized!");

				response.add("error", onlyServerRootJSON);
				response.add("result", JsonNull.INSTANCE);
				status = HttpResponseStatus.FORBIDDEN;
			}
		}

		if (status != HttpResponseStatus.OK) {
			ByteBuf responseContent = Unpooled.copiedBuffer(response.toString(), CharsetUtil.UTF_8);
			FullHttpResponse httpResponse = new DefaultFullHttpResponse(request.protocolVersion(), status, responseContent);

			writeResponse(ctx, httpResponse, request);
			ctx.write(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
			return;
		}

		if (request != null) {
			String content = request.content().toString(CharsetUtil.UTF_8);
			JsonObject jsonReq = null;

			try {
				jsonReq = new JsonParser().parse(content).getAsJsonObject();

				Preconditions.checkNotNull(jsonReq);

				if (!jsonReq.has("method") || !jsonReq.has("params")) {
					ctx.close();
					throw new IllegalArgumentException("Bad JSON-RPC request by client.");
				}
			} catch (Exception e) {
				LOGGER.log(Level.INFO, "Failed Content: " + content);
				e.printStackTrace();
				JsonObject errorParsingJSON = new JsonObject();
				errorParsingJSON.addProperty("code", -1001);
				errorParsingJSON.addProperty("message", "Error parsing JSON.");

				response.add("error", errorParsingJSON);
				response.add("result", JsonNull.INSTANCE);
				if (e instanceof IllegalArgumentException) {
					LOGGER.log(Level.FINER, "[http-server-handler] WARNING: Client sent valid JSON, but did not specify method and/or parameters!");
				} else {
					LOGGER.log(Level.FINER, "[http-server-handler] WARNING: Client sent invalid JSON!");
				}
				status = HttpResponseStatus.BAD_REQUEST;
			}

			if (status == HttpResponseStatus.OK) {
				Preconditions.checkNotNull(jsonReq);

				String method = jsonReq.get("method").getAsString();
				JsonArray params = jsonReq.get("params").getAsJsonArray();

				LOGGER.log(Level.INFO, "[http-server-handler] RPC CALL: " + method + " PARAMS: " + params.size());
				for (int i = 0; i < params.size(); i++) {
					LOGGER.log(Level.INFO, "[http-server-handler] PARAM " + i + ": " + params.get(i).toString());
				}

				response = getResponse(method, params);
				LOGGER.log(Level.FINER, response.toString());
			} else {
				ByteBuf responseContent = Unpooled.copiedBuffer(response.toString(), CharsetUtil.UTF_8);
				FullHttpResponse httpResponse = new DefaultFullHttpResponse(request.protocolVersion(), status, responseContent);

				writeResponse(ctx, httpResponse, request);
				ctx.write(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
				return;
			}

			if (request instanceof LastHttpContent) {
				ByteBuf responseContent = Unpooled.copiedBuffer(response.toString(), CharsetUtil.UTF_8);

				FullHttpResponse httpResponse = new DefaultFullHttpResponse(request.protocolVersion(), status, responseContent);

				if (!writeResponse(ctx, httpResponse, request)) {
					ctx.write(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
				}
			}
		}
	}

	private JsonObject getResponse(String method, JsonArray params) {
		JsonObject response = new JsonObject();
		boolean shutdownRequested = false;

		switch (method.toLowerCase()) {
			case "reloadconfig": {
				if (params.size() != 1) {
					response.add("result", JsonNull.INSTANCE);
					JsonObject errorJSON = new JsonObject();
					errorJSON.addProperty("code", -1);
					errorJSON.addProperty("message", "Usage: reloadconfig <token>\n\ntoken (string, required)");

					response.add("error", errorJSON);
					break;
				}

				CoinTicker ticker = CoinTickerUtils.stringToTicker(params.get(0).getAsString());
				CoinInstance instance = CoinInstance.getInstance(ticker);

				Runnable r = () -> {
					try {
						Thread.sleep(500);
						instance.reloadConfig();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				};
				new Thread(r).start();

				response.addProperty("result", true);
				response.add("error", JsonNull.INSTANCE);
				break;
			}
			case "version": {
				response.addProperty("result", Version.CLIENT_VERSION);
				response.add("error", JsonNull.INSTANCE);
				break;
			}
//			case "reloadconfigs": {
//				boolean success = true;
//				for (CoinInstance instance : CoinInstance.getCoinInstances()) {
//					if (!CoinTickerUtils.isActiveTicker(instance.getTicker()))
//						continue;
//
//					try {
//						LOGGER.log(Level.INFO, instance.getTicker().toString());
//						instance.reloadConfig();
//					} catch (Exception e) {
//						success = false;
//						e.printStackTrace();
//					}
//				}
//
//				response.addProperty("result", success);
//				response.add("error", JsonNull.INSTANCE);
//				break;
//			}
			case "help": {
				String helpString = "Master JSON-RPC server\n"
						+ "This JSON-RPC server is served by " + CoinInstance.getVersionString() + "\n"
						+ "\n"
						+ "help - Display the help\n"
						+ "\n=====RPC Master=====\n"
						+ "stop - Shutdown the server\n"
						+ "reloadconfig <token> - Reload configuration for specified token\n"
						+ "version - Get version\n";
//						+ "reloadconfigs - Reload all configuration files\n";

				response.addProperty("result", helpString);
				response.add("error", JsonNull.INSTANCE);
				break;
			}
			case "stop": {
                shutdownRequested = true;
				response.addProperty("result", "shutting down...");
				response.add("error", JsonNull.INSTANCE);
				break;
			}
			default: {
				JsonObject methodNotFound = new JsonObject();
				methodNotFound.addProperty("code", -32601);
				methodNotFound.addProperty("message", "Method not found.");
				response.add("error", methodNotFound);
				response.add("result", JsonNull.INSTANCE);
				break;
			}
		}

		if (shutdownRequested) {
            (new Thread(() -> {
                System.exit(0);
            })).start(); // shutdown the server
        }
		return response;
	}

	private boolean writeResponse(ChannelHandlerContext ctx, FullHttpResponse httpResponse, FullHttpRequest request) {
		boolean keepAlive = false;
		if (request != null)
			keepAlive = HttpUtil.isKeepAlive(request);

		httpResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
		httpResponse.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, httpResponse.content().readableBytes());
		httpResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
		httpResponse.headers().set(HttpHeaderNames.SERVER, CoinInstance.getVersionString());

		LOGGER.log(Level.FINER, "[http-server-handler] Writing response to channel. Keep alive? " + keepAlive);
		LOGGER.log(Level.FINER, "[http-server-handler] Response content: " + httpResponse.content().toString(CharsetUtil.UTF_8));
		ctx.write(httpResponse);

		return keepAlive;
	}

	private static void send100Continue(ChannelHandlerContext ctx) {
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE);
		ctx.write(response);
	}
}
