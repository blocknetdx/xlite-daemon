package io.cloudchains.app.util;

import com.google.common.base.Preconditions;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class ConfigHelper {
	private final static LogManager LOGMANAGER = LogManager.getLogManager();
	private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);
	private static final boolean POSIX_PERMISSIONS_SUPPORTED =
			FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
	private static final Object CONFIG_FILE_LOCK = new Object();

	private String tickerStr;
	private File file;

	private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY_PERMISSIONS = EnumSet.of(
			PosixFilePermission.OWNER_READ,
			PosixFilePermission.OWNER_WRITE,
			PosixFilePermission.OWNER_EXECUTE);
	private static final Set<PosixFilePermission> OWNER_ONLY_FILE_PERMISSIONS = EnumSet.of(
			PosixFilePermission.OWNER_READ,
			PosixFilePermission.OWNER_WRITE);

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
		file = Preconditions.checkNotNull(this.getFile());
		loadConfig();
	}

	public void loadConfig() {
		synchronized (CONFIG_FILE_LOCK) {
			try {
				ensureOwnerOnlyFile(file);
				String rawConfig = readOwnerOnlyFile(file);
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
			} catch (IllegalStateException e) {
				throw e;
			} catch (Exception e) {
				LOGGER.log(Level.FINER, "[config] ERROR: Error while reading config file!");
				e.printStackTrace();
			}
		}
	}

	private File getFile() {
		String userHome = getLocalDataDirectory();
		Preconditions.checkNotNull(userHome);

		Path home = Paths.get(userHome);
		ensureOwnerOnlyDirectory(home.toFile());
		Path settingsDirectory = home.resolve("settings");
		ensureOwnerOnlyDirectory(settingsDirectory.toFile());

		Path configFile = settingsDirectory.resolve("config-" + tickerStr + ".json");
		ensureOwnerOnlyFile(configFile.toFile());

		return configFile.toFile();
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
		synchronized (CONFIG_FILE_LOCK) {
			ensureOwnerOnlyFile(file);
			JSONObject config = new JSONObject();
			config.put("fee", fee);
			config.put("feeFlat", feeFlat);
			config.put("rpcEnabled", rpcEnabled);
			config.put("rpcUsername", rpcUsername);
			config.put("rpcPassword", rpcPassword);
			config.put("rpcPort", rpcPort);
			config.put("addressCount", addressCount);

			writeOwnerOnlyFileAtomically(file.toPath(), config.toString(4));
		}
	}

	public static String readOwnerOnlyFile(File target) {
		if (target == null)
			throw new IllegalStateException("Cannot read a null path.");
		ensureOwnerOnlyFile(target);
		Path path = target.toPath();
		try {
			BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class,
					LinkOption.NOFOLLOW_LINKS);
			if (before.fileKey() == null)
				throw new IOException("File identity is unavailable: " + path);
			String content;
			try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
				content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
			}
			BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class,
					LinkOption.NOFOLLOW_LINKS);
			if (!Objects.equals(before.fileKey(), after.fileKey()))
				throw new IOException("File was replaced while being read: " + path);
			return content;
		} catch (IOException | UnsupportedOperationException | SecurityException e) {
			throw new IllegalStateException("Unable to read configuration securely.", e);
		}
	}

	public static FileChannel openOwnerOnlyAppendFile(File target) {
		if (target == null)
			throw new IllegalStateException("Cannot open a null path.");
		ensureOwnerOnlyFile(target);
		Path path = target.toPath();
		FileChannel channel = null;
		try {
			BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class,
					LinkOption.NOFOLLOW_LINKS);
			if (before.fileKey() == null)
				throw new IOException("File identity is unavailable: " + path);
			channel = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND,
					LinkOption.NOFOLLOW_LINKS);
			BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class,
					LinkOption.NOFOLLOW_LINKS);
			if (!Objects.equals(before.fileKey(), after.fileKey()))
				throw new IOException("File was replaced while being opened: " + path);
			requireSingleLink(path);
			applyOwnerOnlyFilePermissions(target);
			return channel;
		} catch (IOException | RuntimeException e) {
			if (channel != null) {
				try {
					channel.close();
				} catch (IOException ignored) {
					// Preserve the fail-closed error below.
				}
			}
			throw new IllegalStateException("Unable to open owner-only append file securely.", e);
		}
	}

	private static void writeOwnerOnlyFileAtomically(Path target, String content) {
		Path parent = target.getParent();
		if (parent == null)
			throw new IllegalStateException("Secure file has no parent directory: " + target);

		ensureOwnerOnlyDirectory(parent.toFile());
		Path temporary = null;
		try {
			temporary = Files.createTempFile(parent, "." + target.getFileName() + ".por171-", ".tmp",
					PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE_PERMISSIONS));
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
					StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
				 BufferedWriter writer = new BufferedWriter(Channels.newWriter(
						channel, StandardCharsets.UTF_8.newEncoder(), -1))) {
				writer.write(content);
				writer.flush();
				channel.force(true);
			}
			applyOwnerOnlyFilePermissions(temporary.toFile());
			Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			temporary = null;
			forceDirectory(parent);
		} catch (AtomicMoveNotSupportedException e) {
			throw new IllegalStateException("Atomic configuration replacement is unavailable.", e);
		} catch (IOException | UnsupportedOperationException | SecurityException e) {
			throw new IllegalStateException("Unable to write configuration securely.", e);
		} finally {
			if (temporary != null) {
				try {
					Files.deleteIfExists(temporary);
				} catch (IOException ignored) {
					// The original file remains intact; leave cleanup to the next startup.
				}
			}
		}
	}

	private static void forceDirectory(Path directory) throws IOException {
		try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
			channel.force(true);
		}
	}

	private static void applyOwnerOnlyPermissions(File target, Set<PosixFilePermission> permissions,
			boolean directory) {
		if (target == null)
			throw new IllegalStateException("Cannot secure a null path.");
		requireOwnerOnlyPermissionsSupported();

		Path path = target.toPath();
		try {
			if (Files.isSymbolicLink(path) || !Files.exists(path, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Path is missing or symbolic: " + path);
			if (directory && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Expected directory: " + path);
			if (!directory && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Expected regular file: " + path);
			if (Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) == null)
				throw new IOException("POSIX permissions are unavailable: " + path);
			if (!directory)
				requireSingleLink(path);
			Files.setPosixFilePermissions(path, permissions);
		} catch (IOException | UnsupportedOperationException | SecurityException e) {
			throw new IllegalStateException("Unable to apply owner-only permissions to " + path, e);
		}
	}

	private static void requireSingleLink(Path path) throws IOException {
		Object linkCount = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
		if (!(linkCount instanceof Number) || ((Number) linkCount).longValue() != 1)
			throw new IOException("Hardlinked files are not supported: " + path);
	}

	private static void requireOwnerOnlyPermissionsSupported() {
		if (!POSIX_PERMISSIONS_SUPPORTED)
			throw new IllegalStateException("This hardened daemon requires a POSIX file-attribute view.");
	}

	public static boolean isOwnerOnlyPermissionsSupported() {
		return POSIX_PERMISSIONS_SUPPORTED;
	}

	public static void applyOwnerOnlyFilePermissions(File target) {
		applyOwnerOnlyPermissions(target, OWNER_ONLY_FILE_PERMISSIONS, false);
	}

	public static void applyOwnerOnlyDirectoryPermissions(File target) {
		applyOwnerOnlyPermissions(target, OWNER_ONLY_DIRECTORY_PERMISSIONS, true);
	}

	public static void ensureOwnerOnlyFile(File target) {
		if (target == null)
			throw new IllegalStateException("Cannot secure a null path.");
		requireOwnerOnlyPermissionsSupported();

		Path path = target.toPath();
		try {
			if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
				Files.createFile(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE_PERMISSIONS));
			}
		} catch (FileAlreadyExistsException ignored) {
			// Another process created the file; validate and secure it below.
		} catch (IOException | UnsupportedOperationException | SecurityException e) {
			throw new IllegalStateException("Unable to create secure file " + path, e);
		}
		applyOwnerOnlyFilePermissions(target);
	}

	public static void ensureOwnerOnlyDirectory(File target) {
		if (target == null)
			throw new IllegalStateException("Cannot secure a null path.");
		requireOwnerOnlyPermissionsSupported();

		Path path = target.toPath();
		try {
			Files.createDirectories(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY_PERMISSIONS));
		} catch (IOException | UnsupportedOperationException | SecurityException e) {
			throw new IllegalStateException("Unable to create secure directory " + path, e);
		}
		applyOwnerOnlyDirectoryPermissions(target);
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

		ensureOwnerOnlyDirectory(Paths.get(userHomeDir).toFile());

		return userHomeDir;
	}
}
