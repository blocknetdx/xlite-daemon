package io.cloudchains.app.net.api;

import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.net.CoinTickerUtils;
import io.cloudchains.app.net.api.http.server.HTTPServerInitializer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class JSONRPCServer extends Thread {
	private final static LogManager LOGMANAGER = LogManager.getLogManager();
	private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

	private final CoinInstance coin;
	private final int port;
	private boolean stopping = false;

	private Channel channel;

	JSONRPCServer(CoinInstance coin, int port) {
		this.coin = coin;
		this.port = port;
	}

	public void run() {
		EventLoopGroup workerGroup = new NioEventLoopGroup(5);
		try {
			ServerBootstrap bootstrap = new ServerBootstrap();
			bootstrap.group(workerGroup)
					.option(ChannelOption.SO_BACKLOG, 128)
					.option(ChannelOption.SO_REUSEADDR, true)
					.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
					.channel(NioServerSocketChannel.class)
					.childHandler(new HTTPServerInitializer(coin));

			channel = bootstrap.bind(port).sync().channel();

			LOGGER.log(Level.FINER, "[rpc] Starting RPC server for " + CoinTickerUtils.tickerToString(coin.getTicker()) + " on port " + port + ".");

			channel.closeFuture().sync();
		} catch (Exception e) {
			if (!stopping) {
				LOGGER.log(Level.FINER, "[json-rpc-server] ERROR: Error during server operation! (" + CoinTickerUtils.tickerToString(coin.getTicker()) + ")");
				e.printStackTrace();
			}
		}
	}

	public void deinit() {
		stopping = true;
		LOGGER.log(Level.FINER, "[json-rpc-server] Interrupting server.");

		channel.close();
	}
}
