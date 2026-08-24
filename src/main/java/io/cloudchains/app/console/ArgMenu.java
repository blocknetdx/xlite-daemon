package io.cloudchains.app.console;

import io.cloudchains.app.crypto.KeyHandler;
import io.cloudchains.app.crypto.LoginUtils;
import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.net.CoinTicker;

public class ArgMenu {
    private String[] arguments;

    public ArgMenu(String[] args) {
        this.arguments = args;
    }

    public void init() {
        int selection = 2;
        String password = "";

        System.out.println("-------------------------");
        System.out.println("Help: ");
        System.out.println("Create new wallet: --new-wallet (password)");
        System.out.println("Decrypt wallet: --decrypt-wallet (password)");

        if (arguments.length == 0) {
            System.out.println("No arguments given.");
            System.exit(0);
        } else {
            if (arguments.length == 1) {
                password = arguments[0];
            } else if (arguments.length == 2 && arguments[0].equals("--new-wallet")) {
                selection = 1;
                password = arguments[1];
            } else if (arguments.length == 2 && arguments[0].equals("--decrypt-wallet")) {
                password = arguments[1];
            }
        }

        String entropy = null;

        switch (selection) {
            case 1: {
                if (KeyHandler.existsBaseECKeyFromLocal()) {
                    System.out.println("Key already exists");
                    return;
                }

                int strength = KeyHandler.calculatePasswordStrength(password);

                if (!KeyHandler.existsBaseECKeyFromLocal() && strength < 9) {
                    System.out.println("Bad password.");
                    return;
                }

                entropy = LoginUtils.loginToEntropy(password);
                break;
            }
            case 2: {
                int strength = KeyHandler.calculatePasswordStrength(password);

                if (!KeyHandler.existsBaseECKeyFromLocal() && strength < 9) {
                    System.out.println("Bad password.");
                    return;
                }

                entropy = LoginUtils.loginToEntropy(password);
                break;
            }
            case 3: {
                System.out.println("Exiting...");
                System.exit(0);
            }
            break;
            default:
                throw new IllegalStateException("Unexpected value: " + selection);
        }

        if (entropy == null)
            return;

        completeLogin(entropy, null);
    }

    private void completeLogin(String entropy, String userMnemonic) {
        CoinInstance.CoinError coinError = CoinInstance.getInstance(CoinTicker.BLOCKNET).init(entropy, userMnemonic, false);
        if (coinError != null) {
            System.out.println("[master] Error(" + coinError.getCode().name() + "): " + coinError.getMessage());
            System.exit(0);
        }

        for (CoinTicker cointicker : CoinTicker.coins()) {
            if (cointicker == CoinTicker.BLOCKNET || cointicker == CoinTicker.BLOCKNET_TESTNET5 || cointicker == CoinTicker.BITCOIN)
                continue;
            coinError = CoinInstance.getInstance(cointicker).init(entropy, userMnemonic, false);
            if (coinError != null)
                System.out.println("[" + cointicker.name() + "] Error(" + coinError.getCode().name() + "): " + coinError.getMessage());
        }
    }
}
