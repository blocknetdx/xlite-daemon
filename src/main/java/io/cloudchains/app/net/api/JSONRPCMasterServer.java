package io.cloudchains.app.net.api;

import io.cloudchains.app.App;
import io.cloudchains.app.net.api.http.master.HTTPServerInitializer;
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

public class JSONRPCMasterServer extends Thread {
	private final static LogManager LOGMANAGER = LogManager.getLogManager();
	private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

	private final int port;
	private boolean stopping = false;

	private Channel channel;

	JSONRPCMasterServer(int port) {
		this.port = port;
	}

	public void run() {
		EventLoopGroup workerGroup = new NioEventLoopGroup(2);
		try {
			LOGGER.log(Level.INFO, "[rpc] Starting master RPC server on port " + port + ".");

			ServerBootstrap bootstrap = new ServerBootstrap();
			bootstrap.group(workerGroup)
					.option(ChannelOption.SO_BACKLOG, 128)
					.option(ChannelOption.SO_REUSEADDR, true)
					.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
					.channel(NioServerSocketChannel.class)
					.childHandler(new HTTPServerInitializer());

			channel = bootstrap.bind(port).sync().channel();

			channel.closeFuture().sync();
		} catch (Exception e) {
			if (!stopping) {
				LOGGER.log(Level.FINER, "[json-rpc-server] ERROR: Error during server operation! (master RPC)");
				e.printStackTrace();
			}
		}
	}

	public void deinit() {
		stopping = true;
		LOGGER.log(Level.FINER, "[json-rpc-server] Interrupting server.");

		if (channel != null && channel.isOpen()) {
			channel.close();
		} else {
			LOGGER.log(Level.FINER, "[json-rpc-server] Channel is null or not open during deinitialization.");
		}
	}
}
