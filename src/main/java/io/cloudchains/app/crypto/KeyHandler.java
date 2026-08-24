package io.cloudchains.app.crypto;

import com.google.common.base.Joiner;
import com.subgraph.orchid.encoders.Base64;
import io.cloudchains.app.util.ConfigHelper;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.crypto.MnemonicException;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.UnreadableWalletException;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;


public class KeyHandler {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private ECKey ecKey;

    public KeyHandler(ECKey key) {
        this.ecKey = key;
    }

    public ECKey getBaseECKey() {
        return this.ecKey;
    }

    public ECKey getPublicKey() {
        return ECKey.fromPublicOnly(this.ecKey.getPubKey());
    }


    public static boolean existsBaseECKeyFromLocal() {
        String keyPath = ConfigHelper.getLocalDataDirectory() + "key.dat";
        File keyFile = new File(keyPath);

        return keyFile.exists();
    }

    private static String encryptBaseSeed(String passphrase, byte[] seedBytes, byte[] salt) {
        try {
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, 16384, 256);
            SecretKey tmp = skf.generateSecret(spec);
            SecretKey key = new SecretKeySpec(tmp.getEncoded(), "AES");

            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encrypted = cipher.doFinal(seedBytes);
            byte[] encryptedValue = Base64.encode(encrypted);
            return new String(encryptedValue);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static List<String> getBaseSeed(String passphrase) {
        File keyFile = new File(ConfigHelper.getLocalDataDirectory() + "key.dat");
        BufferedReader bufferedReader;

        if (existsBaseECKeyFromLocal()) {
            try {
                bufferedReader = new BufferedReader(new FileReader(keyFile));
                String saltB64 = bufferedReader.readLine();
                String seedEncryptedB64 = bufferedReader.readLine();
                bufferedReader.close();

                byte[] salt = Base64.decode(saltB64);
                byte[] seedEncrypted = Base64.decode(seedEncryptedB64);

                SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
                PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, 16384, 256);
                SecretKey tmp = skf.generateSecret(spec);
                SecretKey key = new SecretKeySpec(tmp.getEncoded(), "AES");

                Cipher cipher = Cipher.getInstance("AES");
                cipher.init(Cipher.DECRYPT_MODE, key);
                String seed = new String(cipher.doFinal(seedEncrypted));

                return Arrays.asList(seed.split(" "));
            } catch (Exception e) {
                LOGGER.log(Level.FINER, "Error while obtaining base seed: " + e);
                LOGGER.log(Level.FINER, "Bad password.");

                return null;
            }
        } else {

            DeterministicSeed seed = null;
            seed = new DeterministicSeed(new SecureRandom(), 128, "", System.currentTimeMillis() / 1000);

            String mnemonic = Joiner.on(" ").join(Objects.requireNonNull(seed.getMnemonicCode()));

            if (writeInitialData(keyFile, mnemonic, passphrase)) {
                return seed.getMnemonicCode();
            } else {
                return null;
            }
        }
    }

    public static boolean importFromMnemonic(List<String> mnemonicList, String passphrase) {
        File keyFile = new File(ConfigHelper.getLocalDataDirectory() + "key.dat");
        byte[] entropy;

        try {
            MnemonicCode mnemonicCode = new MnemonicCode();
            entropy = mnemonicCode.toEntropy(mnemonicList);
        } catch (IOException | MnemonicException.MnemonicWordException | MnemonicException.MnemonicChecksumException | MnemonicException.MnemonicLengthException e) {
            e.printStackTrace();
            return false;
        }

        DeterministicSeed seed = new DeterministicSeed(entropy , "", System.currentTimeMillis() / 1000);

        String mnemonic = Joiner.on(" ").join(Objects.requireNonNull(seed.getMnemonicCode()));

        if (!seed.getMnemonicCode().toString().equals(mnemonicList.toString()))
            return false;

        return writeInitialData(keyFile, mnemonic, passphrase);
    }

    public static byte[] mnemonicToEntropy(List<String> mnemonicList) {
        byte[] entropy = null;

        try {
            MnemonicCode mnemonicCode = new MnemonicCode();
            entropy = mnemonicCode.toEntropy(mnemonicList);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return entropy;
    }

    private static File findRenameFile() {
        for (int i = 0; i < 100; i++) {
            File file = new File(ConfigHelper.getLocalDataDirectory() + "key-" + i + ".dat");

            if (!file.exists()) {
                return file;
            }
        }

        return null;
    }

    private static boolean writeInitialData(File keyFile, String mnemonic, String passphrase) {
        BufferedWriter bufferedWriter;

        // Move current wallet file to backups
        if (keyFile.exists()) {
            // Backups dir
            String backups = ConfigHelper.getLocalDataDirectory() + "backups" + File.separator;
            File backupsDir = new File(backups);
            if (!backupsDir.exists() && !backupsDir.mkdir())
                LOGGER.warning("Failed to create backups dir " + backupsDir.getPath());

            Date date = new Date();
            SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss");
            String newFileName = backups + "key-" + formatter.format(date) + ".dat";

            File oldFile = new File(keyFile.getPath());
            if (!oldFile.renameTo(new File(newFileName)))
                LOGGER.info("Failed to rename old wallet file");
            else
                LOGGER.info("Created wallet backup " + newFileName);
        }

        Random r = new SecureRandom();
        byte[] salt = new byte[20];
        r.nextBytes(salt);

        byte[] mnemonicByte = mnemonic.getBytes();

        String encryptedSeed = encryptBaseSeed(passphrase, mnemonicByte, salt);
        
        // Print mnemonic to console
        // System.out.println(mnemonic + "\n");

        try {
            bufferedWriter = new BufferedWriter(new FileWriter(keyFile));
            if (encryptedSeed != null) {
                bufferedWriter.write(new String(Base64.encode(salt)));
                bufferedWriter.newLine();
                bufferedWriter.write(encryptedSeed);
                bufferedWriter.newLine();
            }
            bufferedWriter.flush();
            bufferedWriter.close();
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static List<String> getMnemonicFromString(String mnemonic) {
        return Arrays.asList(mnemonic.split(" "));
    }

    public static int calculatePasswordStrength(String password){
        // Password must be greater than 8 characters, contain at least one digit, one lowercase letter, one uppercase letter and one special character.

        int totalScore = 0;

        if( password.length() < 8 )
            return 0;
        else if( password.length() >= 10 )
            totalScore += 2;
        else
            totalScore += 1;

        //if it contains one digit, add 2 to total score
        if( password.matches("(?=.*[0-9]).*") )
            totalScore += 2;

        //if it contains one lower case letter, add 2 to total score
        if( password.matches("(?=.*[a-z]).*") )
            totalScore += 2;

        //if it contains one upper case letter, add 2 to total score
        if( password.matches("(?=.*[A-Z]).*") )
            totalScore += 2;

        //if it contains one special character, add 2 to total score
        if( password.matches("(?=.*[~!@#$%^&*()_-]).*") )
            totalScore += 2;

        return totalScore;
    }
}
