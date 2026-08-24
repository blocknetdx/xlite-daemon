package io.cloudchains.app.console;

import io.cloudchains.app.App;
import io.cloudchains.app.Version;
import io.cloudchains.app.crypto.KeyHandler;
import io.cloudchains.app.crypto.LoginUtils;
import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.net.CoinTicker;
import io.cloudchains.app.net.CoinTickerUtils;
import io.cloudchains.app.net.protocols.blocknet.BlocknetNetworkParameters;
import io.cloudchains.app.util.ConfigHelper;
import io.cloudchains.app.util.background.BackgroundTimerThread;
import org.bitcoinj.core.Context;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class ConsoleMenu {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);
    private String[] arguments;
    private BackgroundTimerThread backgroundTimerThread = null;
    private boolean xliteRPC = false;

    public ConsoleMenu(String[] args) {
        this.arguments = args;
        LOGGER.setLevel(Level.INFO);
    }

    public void logBadPassword(String msg) {
        if (msg == null || msg.isEmpty())
            msg = "Bad password";
        LOGGER.log(Level.INFO, "[master] Error(" + CoinInstance.CoinError.CoinErrorCode.BADPASSWORD.name() + "): " + msg);
    }

    public void logBadMnemonic() {
        LOGGER.log(Level.INFO, "[master] Error(" + CoinInstance.CoinError.CoinErrorCode.BADMNEMONIC.name() + "): Bad mnemonic");
    }

    public void logBadChangePass(String msg) {
        LOGGER.log(Level.INFO, "[master] Error(" + CoinInstance.CoinError.CoinErrorCode.CHANGEPASSWORDFAILED.name() + "): " + msg);
    }

    public void init() {
        if (System.getenv("WALLET_MNEMONIC") != null) {
            String mnemonicImport = System.getenv("WALLET_MNEMONIC");
            if (mnemonicImport == null) {
                LOGGER.log(Level.INFO, "Bad mnemonic.");
                return;
            }

            completeLogin(mnemonicImport, null, true);
            return;
        } else if (System.getenv("WALLET_PASSWORD") != null) {
            String password = System.getenv("WALLET_PASSWORD");
            if (password == null) {
                LOGGER.log(Level.INFO, "Bad password.");
                return;
            }

            int strength = KeyHandler.calculatePasswordStrength(password);

            if (!KeyHandler.existsBaseECKeyFromLocal() && strength < 9) {
                LOGGER.log(Level.INFO, "Bad password.");
                return;
            }

            completeLogin(LoginUtils.loginToEntropy(password), null, false);
        }

        int selection;
        String newWalletStr = "";
        Scanner input = new Scanner(System.in);

        if (KeyHandler.existsBaseECKeyFromLocal()) {
            newWalletStr = "- Disabled. Wallet already exists.";
        }

        if (arguments.length > 0) {
            for (int i = 0; i < arguments.length; i++) {
                String argument = arguments[i];

                switch (argument) {
                    case "--enablerpcandconfigure":
                        autoGenerateRPCConfig();
                        break;
                    case "--development-endpoint": {
                        // sample endpoint url: "https://utils.blocknet.org/"
                        if (i + 1 < arguments.length) {
                            // Check if there is another argument after "--development-endpoint"
                            String customEndpoint = arguments[i + 1];
                            App.BASE_URL = customEndpoint;
                            i++; // Increment i to skip the next argument (custom endpoint)
                        } else {
                            LOGGER.log(Level.WARNING, "Missing custom endpoint after '--development-endpoint'");
                        }
                        break;
                    }
                    case "--version":
                        LOGGER.log(Level.INFO, Version.CLIENT_VERSION);
                        return;
                    case "--createdefaultwallet": {
                        if (KeyHandler.existsBaseECKeyFromLocal()) {
                            LOGGER.log(Level.INFO, "Wallet already exists");
                            return;
                        }

                        String password = readPassword(input, arguments, i + 1, "");
                        int strength = KeyHandler.calculatePasswordStrength(password);
                        if (strength < 9) {
                            logBadPassword(null);
                            return;
                        }

                        String entropy = LoginUtils.loginToEntropy(password);
                        completeLogin(entropy, null, false);

                        return;
                    }
                    case "--createwalletmnemonic": {
                        if (KeyHandler.existsBaseECKeyFromLocal()) {
                            LOGGER.log(Level.INFO, "Wallet already exists");
                            return;
                        }

                        String password = readPassword(input, arguments, i + 1, "");
                        String mnemonic = readPassword(input, arguments, i + 2, "Mnemonic:\n").trim();
                        int strength = KeyHandler.calculatePasswordStrength(password);
                        if (strength < 9) {
                            logBadPassword(null);
                            return;
                        }
                        if (mnemonic.isEmpty()) {
                            logBadMnemonic();
                            return;
                        }

                        String entropy = LoginUtils.loginToEntropy(password);
                        completeLogin(entropy, mnemonic, false);
                        return;
                    }
                    case "--xliterpc": {
                        // Increment RPC port by 1
                        xliteRPC = true;

                        break;
                    }
                    case "--password": {
                        // If password is provided as an arg then use it, otherwise ask for
                        // password via stdin. Don't mistake another cmd option as the
                        // password.
                        String password = readPassword(input, arguments, i + 1, "");
                        int strength = KeyHandler.calculatePasswordStrength(password);

                        if (!KeyHandler.existsBaseECKeyFromLocal() && strength < 9) {
                            LOGGER.log(Level.INFO, "Bad password.");
                            return;
                        }

                        String entropy = LoginUtils.loginToEntropy(password);
                        completeLogin(entropy, null, false);

                        return;
                    }
                    case "--getmnemonic": {
                        String password = readPassword(input, arguments, i + 1, "");

                        if (!KeyHandler.existsBaseECKeyFromLocal()) {
                            LOGGER.log(Level.INFO, "No wallet found.");
                            return;
                        }

                        String entropy = LoginUtils.loginToEntropy(password);
                        String mnemonic = CoinInstance.getMnemonicForPw(entropy);
                        System.out.println(mnemonic);
                        return;
                    }
                    case "--changepassword": {
                        if (!KeyHandler.existsBaseECKeyFromLocal()) {
                            logBadChangePass("Wallet not found");
                            return;
                        }

                        String currentPassword = readPassword(input, arguments, i + 1, "");
                        String newPassword = readPassword(input, arguments, i + 2, "");
                        if (currentPassword.isEmpty() || newPassword.isEmpty()) {
                            LOGGER.log(Level.INFO, "Password cannot be empty");
                            return;
                        }
                        if (currentPassword.equals(newPassword)) {
                            LOGGER.log(Level.INFO, "New password must be different from old password");
                            return;
                        }

                        // Check new password strength
                        int strength = KeyHandler.calculatePasswordStrength(newPassword);
                        if (strength < 9) {
                            LOGGER.log(Level.INFO, "Unable to change the password: New password is not strong enough");
                            return;
                        }

                        CoinInstance.CoinError err = CoinInstance.changePassword(LoginUtils.loginToEntropy(currentPassword),
                                LoginUtils.loginToEntropy(newPassword));
                        if (err != null)
                            logBadChangePass(err.getMessage());
                        else
                            LOGGER.log(Level.INFO, "Wallet password changed successfully");

                        return;
                    }
                    case "--help":
                        displayHelp();
                        return; // Exit after displaying help
                }      
            }
        }

        String entropy = null;

        while (entropy == null) {
            LOGGER.log(Level.INFO, "-------------------------");
            LOGGER.log(Level.INFO, "1 - Create new wallet " + newWalletStr);
            LOGGER.log(Level.INFO, "2 - Decrypt wallet");
            LOGGER.log(Level.INFO, "3 - Import from mnemonic");
            LOGGER.log(Level.INFO, "4 - Quit");

            LOGGER.log(Level.INFO, "Selection: ");
            selection = input.nextInt();
            input.nextLine(); // clear buffer

            switch (selection) {
                case 1: {
                    if (KeyHandler.existsBaseECKeyFromLocal()) {
                        LOGGER.log(Level.INFO, "Key already exists");
                        return;
                    }

                    LOGGER.log(Level.INFO, "Enter new password: ");
                    String password = input.next();
                    int strength = KeyHandler.calculatePasswordStrength(password);

                    if (!KeyHandler.existsBaseECKeyFromLocal() && strength < 9) {
                        LOGGER.log(Level.INFO, "Bad password.");
                        return;
                    }
                    entropy = LoginUtils.loginToEntropy(password);
                    break;
                }
                case 2: {
                    LOGGER.log(Level.INFO, "Enter password: ");
                    String password = new String(System.console().readPassword());
                    int strength = KeyHandler.calculatePasswordStrength(password);

                    if (!KeyHandler.existsBaseECKeyFromLocal() && strength < 9) {
                        LOGGER.log(Level.INFO, "Bad password.");
                        return;
                    }

                    entropy = LoginUtils.loginToEntropy(password);
                    break;
                }
                case 3: {
                    LOGGER.log(Level.INFO, "Enter mnemonic: ");
                    String mnemonicImport = input.nextLine().trim();

                    completeLogin(mnemonicImport, null, true);
                    return;
                }
                case 4: {
                    LOGGER.log(Level.INFO, "Exiting...");
                    System.exit(0);
                }
                default: {
                    LOGGER.log(Level.INFO, "Unknown Option.");
                }
            }
        }

        input.close();
        completeLogin(entropy, null, false);
    }

    public void deinit() {
        if (backgroundTimerThread != null)
            backgroundTimerThread.stop();
        for (CoinTicker cointicker : CoinTicker.coins())
            CoinInstance.getInstance(cointicker).deinit();
    }

    private void completeLogin(String entropy, String userMnemonic, boolean isMnemonic) {
        if (entropy == null && userMnemonic == null) {
            logBadPassword(null);
            System.exit(0);
        }

        CoinInstance.CoinError coinError = CoinInstance.getInstance(CoinTicker.BLOCKNET).init(entropy, userMnemonic, isMnemonic, xliteRPC);
        if (coinError != null) {
            String msg = "[master] Error(" + coinError.getCode().name() + "): " + coinError.getMessage();
            LOGGER.log(Level.SEVERE, msg);
            System.out.println(msg);
            System.exit(0);
        }

        for (CoinTicker cointicker : CoinTicker.coins()) {
            if (cointicker == CoinTicker.BLOCKNET || cointicker == CoinTicker.BLOCKNET_TESTNET5)
                continue;
            coinError = CoinInstance.getInstance(cointicker).init(entropy, userMnemonic, isMnemonic, xliteRPC);
            if (coinError != null) // fail silently
                LOGGER.log(Level.WARNING, "[" + cointicker.name() + "] Error(" + coinError.getCode().name() + "): " + coinError.getMessage());
        }

        App.masterRPC.start();
        backgroundTimerThread = new BackgroundTimerThread();
        (new Thread(backgroundTimerThread)).start();
    }

    private void autoGenerateRPCConfig() {
        for (CoinTicker cointicker : CoinTicker.coins()) {
            ConfigHelper configHelper = new ConfigHelper(CoinTickerUtils.tickerToString(cointicker));

            configHelper.setRpcUsername(generateRandomString(24));
            configHelper.setRpcPassword(generateRandomString(32));
            configHelper.setRpcEnabled(true);
            configHelper.writeConfig();
        }
    }

    private String generateRandomString(int length) {
        SecureRandom secureRandom = new SecureRandom();

        byte[] token = new byte[length];
        secureRandom.nextBytes(token);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    /**
     * Reads the password from args or from stdin if password is not specified.
     * @param input Stdin
     * @param args Program arguments
     * @param argPos Current arg position
     * @param msg Message to display on stdin
     * @return Password
     */
    private String readPassword(Scanner input, String[] args, int argPos, String msg) {
        if (msg.isEmpty())
            msg = "Password:\n";
        if (args.length <= argPos || args[argPos].contains("--")) { // ask pw on stdin
            System.out.println(msg);
            return input.nextLine(); // clear buffer
        }
        // get pw from args
        return args[argPos];
    }

    // Function to display help information
    private static void displayHelp() {
        System.out.println("Usage: xlite-daemon [options]");
        System.out.println("Options:");
        System.out.println("  --enablerpcandconfigure    Enable and configure RPC");
        System.out.println("  --development-endpoint     Set a custom development endpoint");
        System.out.println("                             Example: --development-endpoint <https://url.endpoint.org/>");
        System.out.println("  --version                  Display the version");
        System.out.println("  --createdefaultwallet     Create a default wallet");
        System.out.println("  --createwalletmnemonic    Create a wallet with a mnemonic");
        System.out.println("  --xliterpc                Increment RPC port by 1");
        System.out.println("  --password                Set password without prompt");
        System.out.println("                           Example: --password <your_password>");
        System.out.println("  --getmnemonic             Retrieve mnemonic for a password");
        System.out.println("                           Example: --getmnemonic <your_password>");
        System.out.println("  --changepassword          Change wallet password");
        System.out.println("                           Example: --changepassword <current_password> <new_password>");
    }
}
