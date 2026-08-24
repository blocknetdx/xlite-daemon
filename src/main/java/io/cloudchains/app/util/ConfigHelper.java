package io.cloudchains.app.util;

import com.google.common.base.Preconditions;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class ConfigHelper {
	private final static LogManager LOGMANAGER = LogManager.getLogManager();
	private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

	private String tickerStr;
	private File file;
	private FileWriter fileWriter;

	private double fee;
	private boolean feeFlat;
	private boolean rpcEnabled;
	private String rpcUsername;
	private String rpcPassword;
	private int rpcPort;
	private int addressCount;

	// Override specific configuration directory (useful in unit tests)
	public static String CONFIG_DIR = ""; // Must not end with [/], e.g. /home/user/.config, not /home/user/.config/

	public ConfigHelper(String tickerStr) {
		this.tickerStr = tickerStr;

		try {
			file = Preconditions.checkNotNull(this.getFile());
			loadConfig();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void loadConfig() {
		try {
			String rawConfig = new String(Files.readAllBytes(file.toPath()));
			if (rawConfig.isEmpty()) {
				fee = 0.0001;
				feeFlat = true;
				rpcEnabled = false;
				rpcUsername = "";
				rpcPassword = "";
				if (this.tickerStr.equalsIgnoreCase("master")) {
					rpcPort = 9955;
				} else {
					rpcPort = -1000;
				}
				addressCount = 0;

				writeConfig();
				return;
			}

			JSONObject config = new JSONObject(rawConfig);

			final String[] configKeys = new String[] {
					"fee",
					"feeFlat",
					"rpcEnabled",
					"rpcUsername",
					"rpcPassword",
					"rpcPort",
					"addressCount"
			};

			for (String configKey : configKeys) {
				if (!config.has(configKey)) {
					LOGGER.log(Level.FINER, "[config] Warning: Configuration file does not contain required value '" + configKey + "'. This will probably break things later on.");
				}
			}

			fee = config.getDouble("fee");
			feeFlat = config.getBoolean("feeFlat");
			rpcEnabled = config.getBoolean("rpcEnabled");
			rpcUsername = config.getString("rpcUsername");
			rpcPassword = config.getString("rpcPassword");
			rpcPort = config.getInt("rpcPort");

			if (!config.has("addressCount")) {
				setAddressCount(0);
				writeConfig();
			} else {
				addressCount = config.getInt("addressCount");
			}
		} catch (Exception e) {
			LOGGER.log(Level.FINER, "[config] ERROR: Error while reading config file!");
			e.printStackTrace();
		}
	}

	private File getFile() {
		String userHome = getLocalDataDirectory();
		Preconditions.checkNotNull(userHome);

		File home = new File(userHome);
		File settingsDirectory = new File(home, "settings");
		if (!settingsDirectory.exists()) {
			if (!settingsDirectory.mkdirs()) {
				LOGGER.log(Level.FINER, "[config] ERROR: Could not create base/settings directory!");
				return null;
			}
		}

		File configFile = new File(settingsDirectory, "config-" + tickerStr + ".json");
		try {
			if (!configFile.createNewFile() && !configFile.exists())
				return null;
		} catch (IOException e) {
			e.printStackTrace();
		}

		return configFile;
	}

	public void setFee(double fee) {
		this.fee = fee;
	}

	public void setFlatFee(boolean flat) {
		this.feeFlat = flat;
	}

	public void setRpcEnabled(boolean isEnabled) {
		this.rpcEnabled = isEnabled;
	}

	public void setRpcUsername(String user) {
		this.rpcUsername = user;
	}

	public void setRpcPassword(String pass) {
		this.rpcPassword = pass;
	}

	public void setRpcPort(int rpcPort) {
		if (PortCheck.available(rpcPort))
			this.rpcPort = rpcPort;
		else
			setRpcPort(rpcPort + 1);
	}

	public void setAddressCount(int addressCount) {
		this.addressCount = addressCount;
	}

	public double getFee() {
		return fee;
	}

	public boolean isFlatFee() {
		return feeFlat;
	}

	public boolean isRpcEnabled() {
		return rpcEnabled;
	}

	public String getRpcUsername() {
		return rpcUsername;
	}

	public String getRpcPassword() {
		return rpcPassword;
	}

	public int getMasterRpcPort() {
		if (rpcPort == -1000) {
			rpcPort = 9955;
		}

		return rpcPort;
	}

	public int getRpcPort() {
		return rpcPort;
	}

	public int getAddressCount() {
		return addressCount;
	}

	public boolean validAuth() {
		return rpcUsername != null && !rpcUsername.equals("") && rpcPassword != null && !rpcPassword.equals("");
	}

	public void writeConfig() {
		try {
			fileWriter = new FileWriter(file, false);

			JSONObject config = new JSONObject();
			config.put("fee", fee);
			config.put("feeFlat", feeFlat);
			config.put("rpcEnabled", rpcEnabled);
			config.put("rpcUsername", rpcUsername);
			config.put("rpcPassword", rpcPassword);
			config.put("rpcPort", rpcPort);
			config.put("addressCount", addressCount);

			fileWriter.write(config.toString(4));
			fileWriter.flush();
			fileWriter.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static String getLocalDataDirectory() {
		String userHomeDir;
		if (CONFIG_DIR.isEmpty()) {
		String OS = (System.getProperty("os.name")).toLowerCase();

		if (OS.contains("win")) {
			userHomeDir = System.getenv("AppData");
		} else if (OS.contains("nix") || OS.contains("nux") || OS.contains("aix")) {
			userHomeDir = System.getProperty("user.home") + File.separator + ".config";
		} else if (OS.contains("mac")) {
			userHomeDir = System.getProperty("user.home") + File.separator + "Library" + File.separator + "Application Support";
		} else {
			userHomeDir = System.getProperty("user.home") + File.separator + ".config";
		}
		userHomeDir += File.separator + "CloudChains" + File.separator;
		} else {
			userHomeDir = CONFIG_DIR + File.separator + "CloudChains" + File.separator;
		}

		File directory = new File(userHomeDir);
		if (!directory.exists()) {
			directory.mkdir();
		}

		return userHomeDir;
	}
}
