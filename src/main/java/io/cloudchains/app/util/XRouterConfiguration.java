package io.cloudchains.app.util;

import com.google.common.collect.HashBiMap;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class XRouterConfiguration {
	private final static LogManager LOGMANAGER = LogManager.getLogManager();
	private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

	private final String rawXRouterConfig;
	private final HashMap<String, Double> feeMap = new HashMap<>();
	private final ArrayList<String> supportedWallets = new ArrayList<>();
	private String feeAddress;
	private int timeout;
	private int blockLimit;

	private static HashBiMap<String, Class> pluginParamTypes;

	static {
		pluginParamTypes = HashBiMap.create(2);

		pluginParamTypes.put("string", String.class);
		pluginParamTypes.put("int", Integer.class);
		pluginParamTypes.put("bool", Boolean.class);
	}

	public static String getStringByClass(Class clazz) {
		return pluginParamTypes.inverse().get(clazz);
	}

	public static class XRouterPluginConfiguration {
		private final String rawPluginConfig;
		private final String pluginName;

		private ArrayList<Class> paramTypes = new ArrayList<>();
		private double fee;
		private int clientRequestLimit;

		public XRouterPluginConfiguration(String pluginName, String rawPluginConfig) {
			this.pluginName = pluginName;
			this.rawPluginConfig = rawPluginConfig;
		}

		public void parsePluginConfig() {
			Properties properties;
			try {
				properties = getPluginProperties(rawPluginConfig);
			} catch (IOException e) {
				LOGGER.log(Level.FINER, "[xrouter-plugin-config-parser] ERROR: Error while parsing plugin config!");
				e.printStackTrace();
				return;
			}

			if (!properties.containsKey("parameters") && !properties.containsKey("paramsType")) {
				LOGGER.log(Level.FINER, "[xrouter-plugin-config-parser] ERROR: Plugin has no parameters!");
			} else {
				String[] rawParamTypes;

				if (properties.containsKey("parameters"))
					rawParamTypes = properties.getProperty("parameters").split(",");
				else if (properties.containsKey("paramsType"))
					rawParamTypes = properties.getProperty("paramsType").split(",");
				else
					return;

				for (String rawParamType : rawParamTypes) {
					if (rawParamType.isEmpty())
						continue;

					if (!pluginParamTypes.containsKey(rawParamType)) {
						LOGGER.log(Level.FINER, "[xrouter-plugin-config-parser] ERROR: Invalid/unsupported plugin parameter type: " + rawParamType + ". Failing.");
						throw new IllegalArgumentException("Invalid/unsupported plugin parameter type: " + rawParamType);
					}

					paramTypes.add(pluginParamTypes.get(rawParamType));
				}
			}

			if (properties.contains("fee")) {
				fee = Double.parseDouble(properties.getProperty("fee"));
			} else {
				fee = 0;
			}

			if (properties.contains("clientrequestlimit")) {
				clientRequestLimit = Integer.parseInt(properties.getProperty("clientrequestlimit"));
			} else {
				clientRequestLimit = 100;
			}

			LOGGER.log(Level.FINER, "[xrouter-plugin-config-parser] Processing '" + pluginName + "' complete.");
			LOGGER.log(Level.FINER, "[xrouter-plugin-config-parser] DEBUG: " + pluginName + ": fee = " + fee);
			LOGGER.log(Level.FINER, "[xrouter-plugin-config-parser] DEBUG: " + pluginName + ": params = ");
			for (int i = 0; i < paramTypes.size(); i++) {
				LOGGER.log(Level.FINER, "Parameter " + i + ":\t" + paramTypes.get(i).getSimpleName());
			}
			LOGGER.log(Level.FINER, "[xrouter-plugin-config-parser] DEBUG: " + pluginName + ": clientRequestLimit = " + clientRequestLimit);
		}

		private static Properties getPluginProperties(String rawConfig) throws IOException {
			String toRead = rawConfig.replace("\\n", "\n");

			Properties properties = new Properties();
			properties.load(new StringReader(toRead));
			return properties;
		}

		public String getPluginName() {
			return pluginName;
		}

		public ArrayList<Class> getParamTypes() {
			return paramTypes;
		}

		public double getFee() {
			return fee;
		}

		public int getClientRequestLimit() {
			return clientRequestLimit;
		}
	}

	public XRouterConfiguration(String rawXRouterConfig) {
		this.rawXRouterConfig = rawXRouterConfig;
	}

	public void parseConfig() {
		HashMap<String, Properties> properties = getProperties(rawXRouterConfig);

		if (properties == null)
			return;

		LOGGER.log(Level.FINER, "[xrouter-config-parser] DEBUG: Properties: " + properties.toString());

		supportedWallets.addAll(Arrays.asList(((String) properties.get("Main").get("wallets")).split(",")));
		timeout = Integer.parseInt((String) properties.get("Main").get("timeout"));
		blockLimit = Integer.parseInt((String) properties.get("Main").get("blocklimit"));
		feeAddress = (String) properties.get("Main").get("paymentaddress");

		for (String key : properties.keySet()) {
			if (key.startsWith("xr")) {
				Properties xRouterPropertySet = properties.get(key);
				double fee = Double.parseDouble((String) xRouterPropertySet.get("fee"));
				feeMap.put(key, fee);
			}
		}

		LOGGER.log(Level.FINER, "[xrouter-config-parser] Processing complete.");
	}

	public ArrayList<String> getSupportedWallets() {
		return supportedWallets;
	}

	public HashMap<String, Double> getFeeMap() {
		return feeMap;
	}

	public String getFeeAddress() {
		return feeAddress;
	}

	public int getBlockLimit() {
		return blockLimit;
	}

	public int getTimeout() {
		return timeout;
	}

	private static HashMap<String, Properties> parseINI(String toRead) throws IOException {
		HashMap<String, Properties> result = new HashMap<>();
		new Properties() {

			private Properties section;

			@Override
			public Object put(Object key, Object value) {
				String header = (key + " " + value).trim();
				if (header.startsWith("[") && header.endsWith("]"))
					return result.put(header.substring(1, header.length() - 1),
							section = new Properties());
				else
					return section.put(key, value);
			}

		}.load(new StringReader(toRead));
		return result;
	}

	private static HashMap<String, Properties> getProperties(String rawConfig) {
		String formatted = rawConfig.replace("\\n", "\n");

		HashMap<String, Properties> properties;

		try {
			properties = parseINI(formatted);
		} catch (IOException e) {
			LOGGER.log(Level.FINER, "[xrouter-config-parser] ERROR: Error while parsing XRouter config!");
			e.printStackTrace();
			return null;
		}

		return properties;
	}
}
