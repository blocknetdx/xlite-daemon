import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import io.cloudchains.app.console.ConsoleMenu;
import io.cloudchains.app.console.ArgMenu;
import io.cloudchains.app.crypto.KeyHandler;
import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.net.CoinTicker;
import io.cloudchains.app.util.AddressBalance;
import io.cloudchains.app.util.ConfigHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class POR171SecurityTest {
    private static final Path SOURCE_ROOT = Path.of("src/main/java");
    private static final Path README = Path.of("README.md");
    private static final Path APP = SOURCE_ROOT.resolve("io/cloudchains/app/App.java");
    private static final Path MASTER_SERVER = SOURCE_ROOT.resolve(
            "io/cloudchains/app/net/api/JSONRPCMasterServer.java");
    private static final Path ASSET_SERVER = SOURCE_ROOT.resolve(
            "io/cloudchains/app/net/api/JSONRPCServer.java");
    private static final Path MASTER_HANDLER = SOURCE_ROOT.resolve(
            "io/cloudchains/app/net/api/http/master/HTTPServerHandler.java");
    private static final Path ASSET_HANDLER = SOURCE_ROOT.resolve(
            "io/cloudchains/app/net/api/http/server/HTTPServerHandler.java");
    private static final Path KEY_HANDLER = SOURCE_ROOT.resolve(
            "io/cloudchains/app/crypto/KeyHandler.java");
    private static final Path COIN_INSTANCE = SOURCE_ROOT.resolve(
            "io/cloudchains/app/net/CoinInstance.java");
    private static final Path HTTP_CLIENT = SOURCE_ROOT.resolve(
            "io/cloudchains/app/net/api/http/client/HTTPClient.java");
    private static final Path XROUTER_MESSAGE = SOURCE_ROOT.resolve(
            "io/cloudchains/app/net/xrouter/XRouterMessage.java");
    private static final Path XROUTER_SERIALIZER = SOURCE_ROOT.resolve(
            "io/cloudchains/app/net/xrouter/XRouterMessageSerializer.java");
    private static final Path BLOCKNET_SERIALIZER = SOURCE_ROOT.resolve(
            "io/cloudchains/app/net/protocols/blocknet/BlocknetSerializer.java");
    private static final Path BLOCKNET_PEER_GROUP = SOURCE_ROOT.resolve(
            "io/cloudchains/app/net/protocols/blocknet/BlocknetPeerGroup.java");
    private static final Path BLOCKNET_PEER = SOURCE_ROOT.resolve(
            "io/cloudchains/app/net/protocols/blocknet/BlocknetPeer.java");
    private static final Path ARG_MENU = SOURCE_ROOT.resolve(
            "io/cloudchains/app/console/ArgMenu.java");
    private static final Path CONSOLE_MENU = SOURCE_ROOT.resolve(
            "io/cloudchains/app/console/ConsoleMenu.java");
    private static final Path REFLECTION_CONFIG = Path.of("contrib/netty-reflection.json");
    private static final Path BUILD_GRADLE = Path.of("build.gradle");

    @Test
    void rpcServersBindToPlatformLoopback() throws IOException {
        for (Path source : new Path[] {MASTER_SERVER, ASSET_SERVER}) {
            String content = Files.readString(source);
            assertTrue(content.contains("InetAddress.getLoopbackAddress()"), source.toString());
            assertTrue(content.contains("bootstrap.bind(InetAddress.getLoopbackAddress(), port)"), source.toString());
            assertFalse(content.contains("bootstrap.bind(port)"), source.toString());
        }
    }

    @Test
    void passwordInputIsReadFromStdinOnly() throws Exception {
        Method readPassword = ConsoleMenu.class.getDeclaredMethod(
                "readPassword", Scanner.class, String.class);
        readPassword.setAccessible(true);
        ConsoleMenu menu = new ConsoleMenu(new String[] {"--password", "cli-secret"});

        String password = (String) readPassword.invoke(menu, new Scanner("stdin-secret\n"), "");

        assertEquals("stdin-secret", password);
        assertFalse(Files.readString(Path.of("src/main/java/io/cloudchains/app/console/ConsoleMenu.java"))
                .contains("return args[argPos]"));
    }

    @Test
    void legacySecretIngressAndMnemonicExportAreDisabled() throws IOException {
        String console = Files.readString(CONSOLE_MENU);
        String legacy = Files.readString(ARG_MENU);

        assertFalse(console.contains("System.getenv(\"WALLET_"));
        assertFalse(console.contains("System.out.println(mnemonic)"));
        assertTrue(console.contains("Mnemonic export is disabled."));
        assertFalse(legacy.contains("password = arguments"));
        assertFalse(legacy.contains("System.out"));
        assertTrue(legacy.contains("legacy argument menu is disabled"));

        ArgMenu menu = new ArgMenu(new String[] {"--new-wallet", "cli-secret"});
        assertThrows(IllegalStateException.class, menu::init);
    }

    @Test
    void serverDiagnosticsAreFixedAndReflectionTargetsThePackagedHandler() throws IOException {
        for (Path source : new Path[] {MASTER_HANDLER, ASSET_HANDLER}) {
            String content = Files.readString(source);
            assertFalse(content.contains("RPC CALL"), source.toString());
            assertFalse(content.contains("printStackTrace"), source.toString());
            assertTrue(content.contains("RPC request received."), source.toString());
        }

        String reflection = Files.readString(REFLECTION_CONFIG);
        assertTrue(reflection.contains("io.cloudchains.app.net.api.http.server.HTTPServerHandler"));
        assertFalse(reflection.contains("io.cloudchains.app.net.api.http.HTTPServerHandler"));
    }

    @Test
    void nativeImageArchiveInputsAreConfiguredReproducibly() throws IOException {
        String build = Files.readString(BUILD_GRADLE);
        assertTrue(build.contains("reproducibleFileOrder = true"));
        assertTrue(build.contains("preserveFileTimestamps = false"));
    }

    @Test
    void keyMaterialUsesOwnerOnlyPermissionHelpers() throws IOException {
        String content = Files.readString(KEY_HANDLER);
        assertTrue(content.contains("ensureOwnerOnlyFile(keyFile)"));
        assertTrue(content.contains("createKeyBackup(active)"));
        assertTrue(content.contains("replaceKeyAtomically(temporary, active)"));
    }

    @Test
    void getNewAddressCallPathNeverLogsPrivateMaterial() throws IOException {
        String coinSource = Files.readString(COIN_INSTANCE);
        int methodStart = coinSource.indexOf("public AddressBalance generateAddress");
        int methodEnd = coinSource.indexOf("public void importPrivateKey", methodStart);
        assertTrue(methodStart >= 0);
        assertTrue(methodEnd > methodStart);
        String generateAddressMethod = coinSource.substring(methodStart, methodEnd);

        assertTrue(generateAddressMethod.contains("getWalletHelper().generateAddress()"));
        assertFalse(generateAddressMethod.contains("private key"));
        assertFalse(generateAddressMethod.contains("privateKey.toBase58"));
        assertFalse(generateAddressMethod.contains("getPrivateKeyAsHex"));
        assertTrue(Files.readString(ASSET_HANDLER).contains("coin.generateAddress(true)"));
    }

    @Test
    void getNewAddressBehaviourDoesNotEmitPrivateMaterial(@TempDir Path tempDirectory) {
        String previousConfigDirectory = ConfigHelper.CONFIG_DIR;
        Logger logger = LogManager.getLogManager().getLogger(Logger.GLOBAL_LOGGER_NAME);
        StringBuilder messages = new StringBuilder();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.append(record.getMessage()).append('\n');
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Level previousLevel = logger.getLevel();
        try {
            ConfigHelper.CONFIG_DIR = tempDirectory.toString();
            CoinInstance.getCoinInstances().clear();
            logger.addHandler(capture);
            logger.setLevel(Level.ALL);
            CoinInstance coin = CoinInstance.getInstance(CoinTicker.LITECOIN);
            coin.getConfigHelper().setAddressCount(1);
            assertTrue(coin.init("Test^1234", null, false) == null);
            AddressBalance generated = coin.generateAddress(true);

            assertFalse(messages.toString().contains("private key"));
            assertFalse(messages.toString().contains(generated.getPrivateKey().toBase58()));
            assertFalse(messages.toString().contains(generated.getPrivateKey().getKey().getPrivateKeyAsHex()));
            coin.deinit();
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            CoinInstance.getCoinInstances().clear();
            ConfigHelper.CONFIG_DIR = previousConfigDirectory;
        }
    }

    @Test
    void rpcHandlersDoNotLogRequestOrResponseValues() throws IOException {
        for (Path source : new Path[] {MASTER_HANDLER, ASSET_HANDLER}) {
            String content = Files.readString(source);
            assertFalse(content.contains("params.get(i).toString()"), source.toString());
            assertFalse(content.contains("PARAM \" + i"), source.toString());
            assertFalse(content.contains("Failed Content: \" + content"), source.toString());
            assertFalse(content.contains("Response content: \" +"), source.toString());
        }
        assertFalse(Files.readString(HTTP_CLIENT).contains("LOGGER.log(Level.FINER, \"[httpclient] getRawTransaction \" + res"));
        assertFalse(Files.readString(XROUTER_MESSAGE).contains("Got reply: '"));
        assertFalse(Files.readString(XROUTER_SERIALIZER).contains("Hex.encode(header)) + new String(Hex.encode(data))"));
        assertFalse(Files.readString(BLOCKNET_SERIALIZER).contains("Bytes: \" + new String(Hex.encode(message))"));
    }

    @Test
    void blocknetPeerPathsDoNotLogProtocolPayloads() throws IOException {
        String peerGroup = Files.readString(BLOCKNET_PEER_GROUP);
        String peer = Files.readString(BLOCKNET_PEER);
        assertFalse(peerGroup.contains("LOGGER.log(Level.FINER, reply)"));
        assertFalse(peerGroup.contains("replyJson.getString(\"error\")"));
        assertFalse(peerGroup.contains("utxoJson.toString()"));
        assertFalse(peerGroup.contains("Dumping reply"));
        assertFalse(peer.contains("ourVersionMessage.toString()"));
        assertFalse(peer.contains("peerVersionMessage.subVer"));
        assertFalse(peer.contains("peerVersionMessage.theirAddr"));
    }

    @Test
    void errorLogCreationUsesOwnerOnlyLinkSafePath(@TempDir Path tempDirectory) throws IOException {
        Path log = tempDirectory.resolve("error.log");
        try (FileChannel ignored = ConfigHelper.openOwnerOnlyAppendFile(log.toFile())) {
            assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(log));
        }

        Path hardlink = tempDirectory.resolve("error-hardlink.log");
        Files.createLink(hardlink, log);
        assertThrows(IllegalStateException.class,
                () -> ConfigHelper.openOwnerOnlyAppendFile(hardlink.toFile()));
        Files.delete(hardlink);

        Path symlink = tempDirectory.resolve("error-symlink.log");
        Files.createSymbolicLink(symlink, log);
        assertThrows(IllegalStateException.class,
                () -> ConfigHelper.openOwnerOnlyAppendFile(symlink.toFile()));
    }

    @Test
    void appUsesSecureErrorLogHandler() throws IOException {
        String content = Files.readString(APP);
        assertTrue(content.contains("ConfigHelper.openOwnerOnlyAppendFile"));
        assertTrue(content.contains("new StreamHandler"));
        assertFalse(content.contains("new FileHandler("));
    }

    @Test
    void readmeDocumentsPrivateKeyRpcDenial() throws IOException {
        String content = Files.readString(README);
        assertFalse(content.contains(" - Import an address given"));
        assertFalse(content.contains(" - Dump an addresses private key"));
        assertTrue(content.contains("intentionally unavailable"));
        assertTrue(content.contains("method-not-found"));
        assertTrue(content.contains("restricted signing"));
    }

    @Test
    void privateKeyRpcMethodsFailClosedWhileCoreMethodsRemain() throws IOException {
        String content = Files.readString(ASSET_HANDLER);
        int privateKeyStart = content.indexOf("case \"importprivkey\"");
        int signMessageStart = content.indexOf("case \"signmessage\"");

        assertTrue(privateKeyStart >= 0);
        assertTrue(signMessageStart > privateKeyStart);
        String privateKeyCases = content.substring(privateKeyStart, signMessageStart);
        assertTrue(privateKeyCases.contains("case \"dumpprivkey\""));
        assertTrue(privateKeyCases.contains("Private-key RPC methods are disabled."));
        assertFalse(privateKeyCases.contains("coin.importPrivateKey"));
        assertFalse(privateKeyCases.contains("getPrivateKey()"));

        for (String method : new String[] {
                "getinfo", "listunspent", "getnewaddress", "createrawtransaction",
                "signrawtransaction", "sendrawtransaction", "signmessage"}) {
            assertTrue(content.contains("case \"" + method + "\""), method);
        }
    }

    @Test
    void configAndSettingsAreOwnerOnlyOnPosix(@TempDir Path tempDirectory) throws IOException {
        Path cloudChains = tempDirectory.resolve("CloudChains");
        Path settings = cloudChains.resolve("settings");
        Path config = settings.resolve("config-por171.json");
        PosixFileAttributeView posixView = Files.getFileAttributeView(
                tempDirectory, PosixFileAttributeView.class);
        assertTrue(posixView != null, "POSIX permissions are required");
        assertTrue(ConfigHelper.isOwnerOnlyPermissionsSupported());

        String previousConfigDirectory = ConfigHelper.CONFIG_DIR;
        try {
            ConfigHelper.CONFIG_DIR = tempDirectory.toString();
            ConfigHelper helper = new ConfigHelper("por171");
            helper.writeConfig();
            KeyHandler.getBaseSeed("por171-test");
            assertTrue(KeyHandler.importFromMnemonic(
                    List.of("one", "two", "three", "cake", "neutral", "benefit", "quick", "hip", "level", "mother", "fine", "burst"),
                    "Por171^rotate"));

            Set<PosixFilePermission> directoryPermissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
            Set<PosixFilePermission> filePermissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            assertEquals(directoryPermissions, Files.getPosixFilePermissions(cloudChains));
            assertEquals(directoryPermissions, Files.getPosixFilePermissions(settings));
            assertEquals(filePermissions, Files.getPosixFilePermissions(config));
            assertEquals(filePermissions, Files.getPosixFilePermissions(cloudChains.resolve("key.dat")));
            assertEquals(directoryPermissions, Files.getPosixFilePermissions(cloudChains.resolve("backups")));
        } finally {
            ConfigHelper.CONFIG_DIR = previousConfigDirectory;
        }
    }

    @Test
    void concurrentWalletRotationsHaveUniqueOwnerOnlyBackups(@TempDir Path tempDirectory)
            throws Exception {
        String previousConfigDirectory = ConfigHelper.CONFIG_DIR;
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        String mnemonic = "one two three cake neutral benefit quick hip level mother fine burst";
        try {
            ConfigHelper.CONFIG_DIR = tempDirectory.toString();
            for (int i = 0; i < 4; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return KeyHandler.importFromMnemonic(List.of(mnemonic.split(" ")), "Por171^test");
                }));
            }
            start.countDown();
            for (Future<Boolean> result : results)
                assertTrue(result.get(30, TimeUnit.SECONDS));

            Path cloudChains = tempDirectory.resolve("CloudChains");
            Path keyFile = cloudChains.resolve("key.dat");
            Path backups = cloudChains.resolve("backups");
            List<Path> backupFiles;
            try (var paths = Files.list(backups)) {
                backupFiles = paths.filter(path -> path.getFileName().toString().startsWith("key-"))
                        .toList();
            }
            assertEquals(3, backupFiles.size());
            assertEquals(3, backupFiles.stream()
                    .map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toSet()).size());
            assertEquals(3, backupFiles.stream().map(path -> {
                try {
                    return Files.readString(path);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).collect(java.util.stream.Collectors.toSet()).size());
            Set<PosixFilePermission> filePermissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            PosixFileAttributeView posixView = Files.getFileAttributeView(
                    tempDirectory, PosixFileAttributeView.class);
            assertTrue(posixView != null, "POSIX permissions are required");
            assertEquals(filePermissions, Files.getPosixFilePermissions(keyFile));
            assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(backups));
            for (Path backup : backupFiles)
                assertEquals(filePermissions, Files.getPosixFilePermissions(backup));
        } finally {
            executor.shutdownNow();
            ConfigHelper.CONFIG_DIR = previousConfigDirectory;
        }
    }

    @Test
    void permissionFailuresArePropagated(@TempDir Path tempDirectory) throws IOException {
        Path regularFile = tempDirectory.resolve("not-a-directory");
        Files.createFile(regularFile);
        assertThrows(IllegalStateException.class,
                () -> ConfigHelper.applyOwnerOnlyDirectoryPermissions(regularFile.toFile()));
    }

    @Test
    void symlinksAndHardlinksFailClosed(@TempDir Path tempDirectory) throws IOException {
        assertTrue(ConfigHelper.isOwnerOnlyPermissionsSupported());
        Path settings = tempDirectory.resolve("CloudChains/settings");
        Files.createDirectories(settings);
        Path attackerFile = tempDirectory.resolve("attacker-config");
        Files.writeString(attackerFile, "attacker");
        Path hardlinkedConfig = settings.resolve("config-hardlink.json");
        Files.createLink(hardlinkedConfig, attackerFile);

        String previousConfigDirectory = ConfigHelper.CONFIG_DIR;
        try {
            ConfigHelper.CONFIG_DIR = tempDirectory.toString();
            assertThrows(IllegalStateException.class, () -> new ConfigHelper("hardlink"));

            Files.delete(hardlinkedConfig);
            Path symlinkedConfig = settings.resolve("config-symlink.json");
            Files.createSymbolicLink(symlinkedConfig, attackerFile);
            assertThrows(IllegalStateException.class, () -> new ConfigHelper("symlink"));

            ConfigHelper helper = new ConfigHelper("keylink");
            helper.writeConfig();
            Path keyFile = tempDirectory.resolve("CloudChains/key.dat");
            Path attackerKey = tempDirectory.resolve("attacker-key");
            Files.writeString(attackerKey, "salt\nseed\n");
            Files.createLink(keyFile, attackerKey);
            assertThrows(IllegalStateException.class, () -> KeyHandler.getBaseSeed("password"));
        } finally {
            ConfigHelper.CONFIG_DIR = previousConfigDirectory;
        }
    }

    @Test
    void replacementSymlinkCannotRedirectConfigWrites(@TempDir Path tempDirectory) throws IOException {
        String previousConfigDirectory = ConfigHelper.CONFIG_DIR;
        try {
            ConfigHelper.CONFIG_DIR = tempDirectory.toString();
            ConfigHelper helper = new ConfigHelper("replacement");
            helper.writeConfig();
            Path config = tempDirectory.resolve("CloudChains/settings/config-replacement.json");
            Path target = tempDirectory.resolve("attacker-target");
            Files.writeString(target, "must remain unchanged");
            Files.delete(config);
            Files.createSymbolicLink(config, target);

            assertThrows(IllegalStateException.class, helper::writeConfig);
            assertEquals("must remain unchanged", Files.readString(target));
        } finally {
            ConfigHelper.CONFIG_DIR = previousConfigDirectory;
        }
    }

    @Test
    void failedRotationPreservesActiveKey(@TempDir Path tempDirectory) throws IOException {
        String previousConfigDirectory = ConfigHelper.CONFIG_DIR;
        String mnemonic = "one two three cake neutral benefit quick hip level mother fine burst";
        try {
            ConfigHelper.CONFIG_DIR = tempDirectory.toString();
            assertNotNull(KeyHandler.getBaseSeed("Por171^old"));
            Path keyFile = tempDirectory.resolve("CloudChains/key.dat");
            String before = Files.readString(keyFile);

            assertFalse(KeyHandler.importFromMnemonic(List.of(mnemonic.split(" ")), null));
            assertEquals(before, Files.readString(keyFile));
            assertNotNull(KeyHandler.getBaseSeed("Por171^old"));
        } finally {
            ConfigHelper.CONFIG_DIR = previousConfigDirectory;
        }
    }

    @Test
    void failedRotationWritePreservesActiveKey(@TempDir Path tempDirectory) throws IOException {
        String previousConfigDirectory = ConfigHelper.CONFIG_DIR;
        String mnemonic = "one two three cake neutral benefit quick hip level mother fine burst";
        try {
            ConfigHelper.CONFIG_DIR = tempDirectory.toString();
            assertNotNull(KeyHandler.getBaseSeed("Por171^old"));
            Path cloudChains = tempDirectory.resolve("CloudChains");
            Path keyFile = cloudChains.resolve("key.dat");
            String before = Files.readString(keyFile);
            Files.writeString(cloudChains.resolve("backups"), "not-a-directory");

            assertThrows(IllegalStateException.class,
                    () -> KeyHandler.importFromMnemonic(List.of(mnemonic.split(" ")), "Por171^new"));
            assertEquals(before, Files.readString(keyFile));
            assertNotNull(KeyHandler.getBaseSeed("Por171^old"));
        } finally {
            ConfigHelper.CONFIG_DIR = previousConfigDirectory;
        }
    }

    @Test
    void missingActiveKeyRecoversFromBackup(@TempDir Path tempDirectory) throws IOException {
        String previousConfigDirectory = ConfigHelper.CONFIG_DIR;
        String mnemonic = "one two three cake neutral benefit quick hip level mother fine burst";
        try {
            ConfigHelper.CONFIG_DIR = tempDirectory.toString();
            assertNotNull(KeyHandler.getBaseSeed("Por171^old"));
            assertTrue(KeyHandler.importFromMnemonic(List.of(mnemonic.split(" ")), "Por171^new"));
            Path keyFile = tempDirectory.resolve("CloudChains/key.dat");
            Files.delete(keyFile);

            assertTrue(KeyHandler.existsBaseECKeyFromLocal());
            assertNotNull(KeyHandler.getBaseSeed("Por171^old"));
        } finally {
            ConfigHelper.CONFIG_DIR = previousConfigDirectory;
        }
    }

    @Test
    void repeatedBackupsAreNonOverwriting(@TempDir Path tempDirectory) throws IOException {
        String previousConfigDirectory = ConfigHelper.CONFIG_DIR;
        String mnemonic = "one two three cake neutral benefit quick hip level mother fine burst";
        try {
            ConfigHelper.CONFIG_DIR = tempDirectory.toString();
            assertNotNull(KeyHandler.getBaseSeed("Por171^initial"));
            for (int i = 0; i < 6; i++) {
                assertTrue(KeyHandler.importFromMnemonic(List.of(mnemonic.split(" ")), "Por171^" + i));
            }

            Path backups = tempDirectory.resolve("CloudChains/backups");
            List<Path> backupFiles;
            try (var paths = Files.list(backups)) {
                backupFiles = paths.filter(path -> path.getFileName().toString().startsWith("key-"))
                        .toList();
            }
            assertEquals(6, backupFiles.size());
            assertEquals(6, backupFiles.stream().map(path -> path.getFileName().toString()).collect(
                    java.util.stream.Collectors.toSet()).size());
            assertEquals(6, backupFiles.stream().map(path -> {
                try {
                    return Files.readString(path);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).collect(java.util.stream.Collectors.toSet()).size());
        } finally {
            ConfigHelper.CONFIG_DIR = previousConfigDirectory;
        }
    }

    @Test
    void preExistingBackupCollisionCannotOverwrite(@TempDir Path tempDirectory)
            throws IOException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Path first = tempDirectory.resolve("first-key");
        Path second = tempDirectory.resolve("second-key");
        Path backup = tempDirectory.resolve("key-collision.dat");
        Files.writeString(first, "first backup");
        Files.writeString(second, "second backup");

        Method createLink = KeyHandler.class.getDeclaredMethod(
                "createNonOverwritingBackupLink", Path.class, Path.class);
        createLink.setAccessible(true);
        createLink.invoke(null, backup, first);

        InvocationTargetException collision = assertThrows(InvocationTargetException.class,
                () -> createLink.invoke(null, backup, second));
        assertTrue(collision.getCause() instanceof java.nio.file.FileAlreadyExistsException);
        assertEquals("first backup", Files.readString(backup));
    }

    @Test
    void socketPrivateKeyRpcIsDeniedWithoutLoggingPayload(@TempDir Path tempDirectory) throws Exception {
        String previousConfigDirectory = ConfigHelper.CONFIG_DIR;
        Logger logger = LogManager.getLogManager().getLogger(Logger.GLOBAL_LOGGER_NAME);
        StringBuilder messages = new StringBuilder();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.append(record.getMessage()).append('\n');
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Level previousLevel = logger.getLevel();
        CoinInstance coin = null;
        EmbeddedChannel channel = null;
        String secret = "WIF-MUST-NOT-APPEAR";
        try {
            ConfigHelper.CONFIG_DIR = tempDirectory.toString();
            CoinInstance.getCoinInstances().clear();
            logger.addHandler(capture);
            logger.setLevel(Level.ALL);
            coin = CoinInstance.getInstance(CoinTicker.LITECOIN);
            coin.getConfigHelper().setRpcUsername("rpc-user");
            coin.getConfigHelper().setRpcPassword("rpc-pass");
            coin.getConfigHelper().writeConfig();
            assertTrue(coin.init("Test^1234", null, false) == null);

            Constructor<?> constructor = Class.forName(
                    "io.cloudchains.app.net.api.http.server.HTTPServerHandler")
                    .getDeclaredConstructor(CoinInstance.class);
            constructor.setAccessible(true);
            channel = new EmbeddedChannel((ChannelHandler) constructor.newInstance(coin));

            String payload = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"dumpprivkey\",\"params\":[\""
                    + secret + "\"]}";
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/",
                    Unpooled.copiedBuffer(payload, CharsetUtil.UTF_8));
            request.headers().set(HttpHeaderNames.CONTENT_LENGTH, request.content().readableBytes());
            request.headers().set(HttpHeaderNames.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(
                    "rpc-user:rpc-pass".getBytes(StandardCharsets.UTF_8)));
            channel.writeInbound(request);
            FullHttpResponse response = channel.readOutbound();
            assertNotNull(response);
            String responseBody = response.content().toString(CharsetUtil.UTF_8);
            response.release();
            assertTrue(responseBody.contains("-32601"));
            assertTrue(responseBody.contains("Private-key RPC methods are disabled."));
            assertFalse(messages.toString().contains(secret));
        } finally {
            if (channel != null)
                channel.finishAndReleaseAll();
            if (coin != null)
                coin.deinit();
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            CoinInstance.getCoinInstances().clear();
            ConfigHelper.CONFIG_DIR = previousConfigDirectory;
        }
    }
}
