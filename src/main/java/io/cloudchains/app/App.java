package io.cloudchains.app;

import io.cloudchains.app.console.*;
import io.cloudchains.app.net.api.JSONRPCController;
import io.cloudchains.app.net.api.JSONRPCMasterServer;
import io.cloudchains.app.net.api.http.client.HTTPClient;
import io.cloudchains.app.util.CCLogger;
import io.cloudchains.app.util.ConfigHelper;

import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.logging.*;

public class App {
	private final static LogManager LOGMANAGER = LogManager.getLogManager();
	private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);
	private static Handler errorFileHandler;

	private static final boolean isLoggingEnabled = false;
	// DEBUG ENDPOINT
	public static String BASE_URL = "https://xliterevp.mywire.org/";
	// "http://xl-dae-prox.airdns.org:42111/";
	// DEBUG ENDPOINT
	public static HTTPClient feeUpdateHttpClient = new HTTPClient(2);
	public static HTTPClient heightUpdateHttpClient = new HTTPClient(2);
	public static JSONRPCMasterServer masterRPC = JSONRPCController.getMasterServer();
	public static ConsoleMenu console = null;

	public static void main(String[] args) {
		CCLogger.setLogging(isLoggingEnabled);
		LOGGER.setLevel(Level.INFO);
		LOGGER.setUseParentHandlers(false);

        Runtime.getRuntime().addShutdownHook(new Thread(App::shutdown));

		DateTimeFormatter timeStampPattern = DateTimeFormatter.ofPattern("yyyy-MM-dd");
		Path logPath = Paths.get(ConfigHelper.getLocalDataDirectory(),
				"error-" + timeStampPattern.format(java.time.LocalDateTime.now()) + ".log");
		FileChannel logChannel = ConfigHelper.openOwnerOnlyAppendFile(logPath.toFile());
		errorFileHandler = new StreamHandler(Channels.newOutputStream(logChannel), new SimpleFormatter() {
			private static final String format = "[%1$tF %1$tT] [%2$-7s] %3$s %n";

				@Override
				public synchronized String format(LogRecord lr) {
					return String.format(format,
							new Date(lr.getMillis()),
							lr.getLevel().getLocalizedName(),
							lr.getMessage()
					);
				}
		});
		errorFileHandler.setLevel(Level.INFO);

		LOGGER.addHandler(errorFileHandler);

		ConsoleHandler consoleHandler = new ConsoleHandler (){
			@Override
			protected synchronized void setOutputStream(OutputStream out) throws SecurityException {
				super.setOutputStream(System.out);
			}
		};
		consoleHandler.setLevel(Level.FINE);

		LOGGER.addHandler(consoleHandler);

		console = new ConsoleMenu(args);
		console.init();
	}

	public static void shutdown() {
		if (errorFileHandler != null) {
			errorFileHandler.close();
			errorFileHandler = null;
		}

		if (masterRPC.isAlive()) {
			System.out.println("Shutting down...");
		}

		if (feeUpdateHttpClient != null) {
			feeUpdateHttpClient.close();
		}

		if (heightUpdateHttpClient != null) {
			heightUpdateHttpClient.close();
		}

		if (masterRPC != null) {
			masterRPC.deinit();
		}

		if (console != null) {
			console.deinit();
		}

		for (Handler handler : LOGGER.getHandlers()) {
			LOGGER.removeHandler(handler);
			handler.close();
		}
	}
}
